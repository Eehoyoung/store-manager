package com.storemanager.api.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Base64;
import org.junit.jupiter.api.Test;

/** EnvelopeCipher 단위 테스트. 평문/DEK 가 암호문 밖으로 새어나가지 않는지가 핵심 검증 대상이다. */
class EnvelopeCipherTest {

    // application-test.yml 과 동일한 테스트 전용 더미 마스터키(32바이트) - 운영 값 아님
    private static final String MASTER_KEY = "K/tAaCqZxldEye4SHcdQWB1so2ySJybU3mJfvDDpZ0g=";

    private final EnvelopeCipher cipher = new EnvelopeCipher(MASTER_KEY, "test-key");

    @Test
    void 암복호화_왕복시_원문이_복원된다() {
        String plaintext = "실제매장비밀번호!23";

        EnvelopeCipher.EncryptedSecret secret = cipher.encrypt(plaintext);
        String decrypted = cipher.decrypt(secret);

        assertThat(decrypted).isEqualTo(plaintext);
    }

    @Test
    void 두번_암호화하면_DEK와_nonce와_암호문이_모두_다르다() {
        String plaintext = "동일한비밀번호";

        EnvelopeCipher.EncryptedSecret first = cipher.encrypt(plaintext);
        EnvelopeCipher.EncryptedSecret second = cipher.encrypt(plaintext);

        assertThat(first.ciphertext()).isNotEqualTo(second.ciphertext());
        assertThat(first.encDek()).isNotEqualTo(second.encDek());
        assertThat(first.nonce()).isNotEqualTo(second.nonce());
        // 둘 다 복호화하면 여전히 같은 원문이어야 한다
        assertThat(cipher.decrypt(first)).isEqualTo(cipher.decrypt(second));
    }

    @Test
    void 암호문_바이트에는_평문이_포함되지_않는다() {
        String plaintext = "MySuperSecretPw2026";

        EnvelopeCipher.EncryptedSecret secret = cipher.encrypt(plaintext);

        String ciphertextAsLatin1 = new String(secret.ciphertext(), java.nio.charset.StandardCharsets.ISO_8859_1);
        assertThat(ciphertextAsLatin1).doesNotContain(plaintext);
        assertThat(Base64.getEncoder().encodeToString(secret.ciphertext())).doesNotContain(plaintext);
    }

    @Test
    void 변조된_암호문은_복호화시_예외를_던진다() {
        EnvelopeCipher.EncryptedSecret secret = cipher.encrypt("변조테스트");
        byte[] tampered = secret.ciphertext().clone();
        tampered[0] ^= 0x01; // 한 바이트 플립 → GCM 태그 검증 실패 유도

        EnvelopeCipher.EncryptedSecret tamperedSecret = new EnvelopeCipher.EncryptedSecret(
                tampered, secret.encDek(), secret.nonce(), secret.keyId(), secret.algorithm());

        assertThrows(RuntimeException.class, () -> cipher.decrypt(tamperedSecret));
    }

    @Test
    void toString은_평문이나_DEK를_노출하지_않는다() {
        EnvelopeCipher.EncryptedSecret secret = cipher.encrypt("절대노출금지비밀번호");

        assertThat(secret.toString()).doesNotContain("절대노출금지비밀번호");
        assertThat(secret.toString()).contains("test-key").contains("AES-256-GCM");
    }
}
