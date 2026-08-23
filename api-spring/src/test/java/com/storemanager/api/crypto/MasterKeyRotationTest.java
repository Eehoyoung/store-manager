package com.storemanager.api.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.storemanager.api.common.ApiException;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/**
 * 마스터키 교체 회귀 테스트.
 *
 * <p>★ 실제로 겪은 사고다(2026-08-23). {@code CREDENTIAL_MASTER_KEY} 를 새로 만들었더니
 * 기존에 암호화된 배달앱 자격증명을 전부 복호화할 수 없게 될 뻔했다.
 * {@code platform_account.kms_key_id} 컬럼이 행마다 있는데 아무도 쓰지 않고 있었다.
 *
 * <p>★ 이 테스트가 깨지면 키 교체가 데이터 손실로 이어진다. 테스트를 고치기 전에
 * 구현이 맞는지 먼저 보라.
 */
class MasterKeyRotationTest {

    private static final String OLD_KEY = Base64.getEncoder().encodeToString(bytes((byte) 1));
    private static final String NEW_KEY = Base64.getEncoder().encodeToString(bytes((byte) 2));

    private static byte[] bytes(byte fill) {
        byte[] b = new byte[32];
        java.util.Arrays.fill(b, fill);
        return b;
    }

    private static EnvelopeCipher cipher(String currentKey, String currentId, String previous) {
        return new EnvelopeCipher(new MasterKeyProvider(currentKey, currentId, previous, false));
    }

    @Test
    void 키를_교체해도_옛_키로_암호화된_값을_읽는다() {
        // 교체 전: 옛 키로 저장
        EnvelopeCipher before = cipher(OLD_KEY, "key-2025", "");
        var secret = before.encrypt("배달앱비밀번호");
        assertThat(secret.keyId()).isEqualTo("key-2025");

        // 교체 후: 새 키가 현재 키, 옛 키는 previous 로 남긴다
        EnvelopeCipher after = cipher(NEW_KEY, "key-2026", "key-2025:" + OLD_KEY);

        assertThat(after.decrypt(secret)).isEqualTo("배달앱비밀번호");
    }

    @Test
    void 교체_후_새로_저장하는_값은_새_키를_쓴다() {
        EnvelopeCipher after = cipher(NEW_KEY, "key-2026", "key-2025:" + OLD_KEY);
        assertThat(after.encrypt("새비밀번호").keyId()).isEqualTo("key-2026");
    }

    @Test
    void 옛_키를_남기지_않으면_원인을_말하며_실패한다() {
        // ★ 조용히 현재 키로 시도하면 "복호화 실패 - 변조되었을 수 있습니다" 로 보여
        //   진짜 원인(키를 안 남겼다)을 찾을 수 없다.
        EnvelopeCipher before = cipher(OLD_KEY, "key-2025", "");
        var secret = before.encrypt("배달앱비밀번호");

        EnvelopeCipher after = cipher(NEW_KEY, "key-2026", "");  // previous 없음

        // ApiException 의 message 는 사용자용 문구이고, 원인은 details 로 내려간다.
        // 운영자가 응답 본문에서 어떤 keyId 가 없는지 볼 수 있어야 한다.
        assertThatThrownBy(() -> after.decrypt(secret)).isInstanceOf(ApiException.class);
        try {
            after.decrypt(secret);
        } catch (ApiException e) {
            assertThat(String.valueOf(e.getDetails())).contains("키를 찾을 수 없습니다").contains("key-2025");
        }
    }

    @Test
    void 재암호화가_필요한지_판별한다() {
        MasterKeyProvider provider = new MasterKeyProvider(NEW_KEY, "key-2026", "key-2025:" + OLD_KEY, false);
        assertThat(provider.needsRewrap("key-2025")).isTrue();
        assertThat(provider.needsRewrap("key-2026")).isFalse();
    }

    @Test
    void 잘못된_키_설정은_기동_시점에_막는다() {
        // 짧은 키가 조용히 통과하면 암호 강도가 떨어진 채로 운영된다.
        assertThatThrownBy(() -> new MasterKeyProvider(
                Base64.getEncoder().encodeToString(new byte[16]), "k", "", false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32바이트");

        assertThatThrownBy(() -> new MasterKeyProvider(NEW_KEY, "k", "형식이틀림", false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("previous-keys");
    }

    @Test
    void require_kms_는_어댑터가_없으면_기동을_막는다() {
        // "설정은 켰는데 실제로는 로컬 키" 가 가장 나쁜 상태다.
        assertThatThrownBy(() -> new MasterKeyProvider(NEW_KEY, "k", "", true))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 옛_키가_여러_개여도_전부_읽는다() {
        String older = Base64.getEncoder().encodeToString(bytes((byte) 3));
        var s1 = cipher(older, "key-2024", "").encrypt("아주오래된값");
        var s2 = cipher(OLD_KEY, "key-2025", "").encrypt("오래된값");

        EnvelopeCipher now = cipher(NEW_KEY, "key-2026",
                "key-2024:" + older + ",key-2025:" + OLD_KEY);

        assertThatCode(() -> {
            assertThat(now.decrypt(s1)).isEqualTo("아주오래된값");
            assertThat(now.decrypt(s2)).isEqualTo("오래된값");
        }).doesNotThrowAnyException();
    }
}
