package com.storemanager.api.crypto;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * KMS 봉투암호화(envelope encryption) 유틸.
 * 매 암호화마다 새 DEK(Data Encryption Key)를 생성해 평문을 감싸고, DEK 자체는 마스터키로 다시 감싼다.
 * 평문·DEK 는 어떤 경우에도 로그·예외 메시지·toString 에 남기지 않는다 (CLAUDE.md 절대규칙 5).
 *
 * ponytail: 로컬 개발 전용 구현. 운영에서는 CREDENTIAL_REQUIRE_KMS=true로 기동을 차단하고
 * AWS KMS GenerateDataKey 어댑터(T-10)로 교체해야 한다.
 */
@Component
public class EnvelopeCipher {

    private static final String ALGO_NAME = "AES-256-GCM";
    private static final String CIPHER_SPEC = "AES/GCM/NoPadding";
    private static final int NONCE_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final int DEK_BYTES = 32;
    private static final String FINGERPRINT_ALGORITHM = "HmacSHA256";

    private final MasterKeyProvider keyProvider;
    private final SecureRandom random = new SecureRandom();

    /** 기존 단위 테스트·로컬 호출 호환용. Base64 마스터키 하나로 만든다. */
    public EnvelopeCipher(String masterKeyBase64, String keyId) {
        this(new MasterKeyProvider(masterKeyBase64, keyId, "", false));
    }

    @Autowired
    public EnvelopeCipher(MasterKeyProvider keyProvider) {
        this.keyProvider = keyProvider;
    }

    /** 평문을 봉투암호화한다. DEK 는 매 호출마다 새로 생성되며 결과 재사용에도 매번 다른 암호문이 나온다. */
    public EncryptedSecret encrypt(String plaintext) {
        byte[] dek = new byte[DEK_BYTES];
        random.nextBytes(dek);
        try {
            SecretKeySpec dekKey = new SecretKeySpec(dek, "AES");

            byte[] dataNonce = randomNonce();
            byte[] ciphertext = doFinal(Cipher.ENCRYPT_MODE, dekKey, dataNonce,
                    plaintext.getBytes(StandardCharsets.UTF_8));

            byte[] dekNonce = randomNonce();
            byte[] dekCiphertext = doFinal(Cipher.ENCRYPT_MODE, keyProvider.currentKey(), dekNonce, dek);
            byte[] encDek = concat(dekNonce, dekCiphertext);

            return new EncryptedSecret(ciphertext, encDek, dataNonce, keyProvider.currentKeyId(), ALGO_NAME);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("암호화 실패", e);
        } finally {
            Arrays.fill(dek, (byte) 0);
        }
    }

    /** 봉투암호화된 값을 복호화한다. 변조된 암호문·불일치 nonce 는 예외를 던진다. */
    public String decrypt(EncryptedSecret secret) {
        byte[] dek = null;
        try {
            byte[] dekNonce = Arrays.copyOfRange(secret.encDek(), 0, NONCE_BYTES);
            byte[] dekCiphertext = Arrays.copyOfRange(secret.encDek(), NONCE_BYTES, secret.encDek().length);
            // ★ 행에 적힌 keyId 로 키를 고른다. 현재 키로만 풀면 교체 즉시 옛 행을 못 읽는다.
            dek = doFinal(Cipher.DECRYPT_MODE, keyProvider.keyFor(secret.keyId()), dekNonce, dekCiphertext);

            SecretKeySpec dekKey = new SecretKeySpec(dek, "AES");
            byte[] plaintext = doFinal(Cipher.DECRYPT_MODE, dekKey, secret.nonce(), secret.ciphertext());
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("복호화 실패: 암호문이 변조되었을 수 있습니다.", e);
        } finally {
            if (dek != null) {
                Arrays.fill(dek, (byte) 0);
            }
        }
    }

    /** 복호화할 수 없는 자격증명 지문. 중복·유출 탐지용이며 DataAPI 비밀번호 대체값이 아니다. */
    public byte[] fingerprint(String plaintext) {
        try {
            Mac mac = Mac.getInstance(FINGERPRINT_ALGORITHM);
            // 지문은 현재 키로 만든다. 키를 바꾸면 지문도 달라지지만 중복·유출 탐지용이라
            // 과거 값과 비교할 일이 없다. 복호화에는 쓰지 않는다.
            mac.init(keyProvider.currentKey());
            mac.update("LOGINPWD-FINGERPRINT\0".getBytes(StandardCharsets.UTF_8));
            return mac.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("자격증명 지문 생성 실패", e);
        }
    }

    private byte[] doFinal(int mode, SecretKeySpec key, byte[] nonce, byte[] input) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance(CIPHER_SPEC);
        cipher.init(mode, key, new GCMParameterSpec(GCM_TAG_BITS, nonce));
        return cipher.doFinal(input);
    }

    private byte[] randomNonce() {
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        return nonce;
    }

    private byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    /** 봉투암호화 결과 1건. toString 은 암호문·DEK 실데이터를 노출하지 않고 길이만 표기한다. */
    public record EncryptedSecret(byte[] ciphertext, byte[] encDek, byte[] nonce, String keyId, String algorithm) {
        @Override
        public String toString() {
            return "EncryptedSecret[keyId=%s, algorithm=%s, ciphertextBytes=%d, encDekBytes=%d]"
                    .formatted(keyId, algorithm, ciphertext.length, encDek.length);
        }
    }
}
