package com.storemanager.api.crypto;

import com.storemanager.api.common.ApiException;
import com.storemanager.api.common.ErrorCode;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 봉투암호화 마스터키 공급자.
 *
 * <p>★ 왜 인터페이스가 아니라 이 클래스 하나인가 — 구현이 하나뿐인데 인터페이스를 두면
 * 코드만 늘어난다. KMS 어댑터를 붙일 때 이 클래스를 인터페이스로 바꾸면 되고, 그때
 * {@code EnvelopeCipher} 는 손대지 않아도 된다. 지금 필요한 것은 <b>키 교체가 가능한 구조</b>다.
 *
 * <p>★ 키 교체(rotation)가 왜 필요한가 — 실제로 겪은 사고다(2026-08-23).
 * {@code CREDENTIAL_MASTER_KEY} 를 새로 만들었더니 기존에 암호화된 배달앱 자격증명을
 * 전부 복호화할 수 없게 될 뻔했다. {@code platform_account.kms_key_id} 컬럼이 행마다
 * 있는데 <b>아무도 쓰지 않고 있었다.</b>
 *
 * <p>동작:
 * <ul>
 *   <li>암호화는 항상 <b>현재 키</b>로 한다. 그 키의 id 를 행에 남긴다
 *   <li>복호화는 행에 적힌 <b>id 로 키를 찾아</b> 한다 — 옛 키로 암호화된 행도 읽힌다
 *   <li>옛 키를 {@code app.crypto.previous-keys} 에 남겨 두면 교체 후에도 서비스가 계속 돈다
 * </ul>
 *
 * <p>★ 운영 전환 절차: 새 키를 현재 키로 올리고 옛 키를 previous-keys 에 남긴다 →
 * 모든 행이 새 키로 재암호화된 뒤에 옛 키를 지운다. <b>순서를 바꾸면 데이터를 잃는다.</b>
 */
@Component
public class MasterKeyProvider {

    private static final int KEY_BYTES = 32;

    private final String currentKeyId;
    private final SecretKeySpec currentKey;
    /** id → 키. 현재 키와 옛 키를 모두 담는다. 복호화는 여기서만 찾는다. */
    private final Map<String, SecretKeySpec> keysById = new LinkedHashMap<>();

    public MasterKeyProvider(
            @Value("${app.crypto.master-key}") String masterKeyBase64,
            @Value("${app.crypto.key-id:local-dev}") String keyId,
            @Value("${app.crypto.previous-keys:}") String previousKeys,
            @Value("${app.crypto.require-kms:false}") boolean requireKms) {
        if (requireKms) {
            // 운영 KMS 어댑터가 없는 상태에서 require-kms=true 면 기동을 막는다.
            // "설정은 켰는데 실제로는 로컬 키" 가 가장 나쁜 상태다.
            throw new IllegalStateException("운영 KMS 어댑터가 아직 없어 자격증명 저장을 시작할 수 없습니다.");
        }
        this.currentKeyId = keyId;
        this.currentKey = toKey(masterKeyBase64, "app.crypto.master-key");
        keysById.put(keyId, currentKey);

        // "id1:base64key1,id2:base64key2" — 교체 이전 키들. 복호화에만 쓴다.
        if (previousKeys != null && !previousKeys.isBlank()) {
            for (String entry : previousKeys.split(",")) {
                String[] parts = entry.trim().split(":", 2);
                if (parts.length != 2 || parts[0].isBlank()) {
                    throw new IllegalStateException(
                            "app.crypto.previous-keys 형식이 잘못됐습니다. 'id:base64키' 를 쉼표로 나열하세요.");
                }
                keysById.putIfAbsent(parts[0].trim(), toKey(parts[1].trim(), "app.crypto.previous-keys"));
            }
        }
    }

    private static SecretKeySpec toKey(String base64, String where) {
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(where + " 는 Base64 여야 합니다.", e);
        }
        if (bytes.length != KEY_BYTES) {
            throw new IllegalStateException(where + " 는 Base64 인코딩된 " + KEY_BYTES + "바이트여야 합니다.");
        }
        return new SecretKeySpec(bytes, "AES");
    }

    /** 새로 암호화할 때 쓰는 키의 id. 이 값을 행에 남겨야 나중에 복호화할 수 있다. */
    public String currentKeyId() {
        return currentKeyId;
    }

    public SecretKeySpec currentKey() {
        return currentKey;
    }

    /**
     * 행에 적힌 id 로 키를 찾는다.
     *
     * <p>★ 못 찾으면 조용히 현재 키로 시도하지 않는다. 그러면 "복호화 실패" 가 아니라
     * "변조된 암호문" 으로 보여 원인을 찾을 수 없다. 어떤 키가 없는지 말해 준다.
     * <p>★ 예외 메시지에 키 자체는 넣지 않는다(절대규칙 5).
     */
    public SecretKeySpec keyFor(String keyId) {
        SecretKeySpec key = keysById.get(keyId == null ? currentKeyId : keyId);
        if (key == null) {
            throw new ApiException(ErrorCode.SERVICE_UNAVAILABLE,
                    Map.of("reason", "암호화 키를 찾을 수 없습니다: keyId=" + keyId
                            + ". 키를 교체했다면 app.crypto.previous-keys 에 옛 키를 남겨야 합니다."));
        }
        return key;
    }

    /** 이 id 의 행이 현재 키로 재암호화가 필요한가. 키 교체 진행 상황을 재는 데 쓴다. */
    public boolean needsRewrap(String keyId) {
        return !currentKeyId.equals(keyId);
    }
}
