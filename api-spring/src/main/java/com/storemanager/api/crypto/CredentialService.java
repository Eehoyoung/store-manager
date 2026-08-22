package com.storemanager.api.crypto;

import com.storemanager.api.audit.AuditLog;
import com.storemanager.api.audit.AuditLogRepository;
import com.storemanager.api.common.ApiException;
import com.storemanager.api.common.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.security.MessageDigest;

/**
 * 플랫폼 계정 자격증명(LOGINPWD)을 다루는 유일한 통로.
 * 이 클래스 외부에서 enc_password 를 SELECT 하지 않는다.
 */
@Service
public class CredentialService {

    private final PlatformAccountRepository platformAccountRepository;
    private final AuditLogRepository auditLogRepository;
    private final EnvelopeCipher envelopeCipher;

    public CredentialService(PlatformAccountRepository platformAccountRepository,
            AuditLogRepository auditLogRepository, EnvelopeCipher envelopeCipher) {
        this.platformAccountRepository = platformAccountRepository;
        this.auditLogRepository = auditLogRepository;
        this.envelopeCipher = envelopeCipher;
    }

    /** 자격증명을 봉투암호화해 저장한다. rawPassword 는 이 메서드 스택 밖으로 나가지 않는다. */
    @Transactional
    public PlatformAccount save(Long ownerId, String platform, String loginId, String rawPassword) {
        EnvelopeCipher.EncryptedSecret secret = envelopeCipher.encrypt(rawPassword);
        PlatformAccount account = PlatformAccount.builder()
                .ownerId(ownerId)
                .platform(platform)
                .loginId(loginId)
                .encPassword(secret.ciphertext())
                .encDek(secret.encDek())
                .encNonce(secret.nonce())
                .passwordFingerprint(envelopeCipher.fingerprint(rawPassword))
                .kmsKeyId(secret.keyId())
                .encAlgorithm(secret.algorithm())
                .build();
        return platformAccountRepository.saveAndFlush(account);
    }

    /** 자격증명을 복호화해 반환한다. 호출할 때마다 audit_log 에 CREDENTIAL_READ 를 남긴다. */
    @Transactional
    public String loadPasswordForOwner(Long accountId, Long ownerId) {
        PlatformAccount account = platformAccountRepository.findByIdAndOwnerIdAndRevokedAtIsNull(accountId, ownerId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));

        EnvelopeCipher.EncryptedSecret secret = new EnvelopeCipher.EncryptedSecret(
                account.getEncPassword(), account.getEncDek(), account.getEncNonce(),
                account.getKmsKeyId(), account.getEncAlgorithm());
        // 내부 워커 전용 경로다. accountId만으로 호출할 수 없고 소유자 경계를 함께 검증한다.
        auditLogRepository.save(AuditLog.builder()
                .actorType("SYSTEM")
                .action("CREDENTIAL_READ")
                .targetType("PLATFORM_ACCOUNT")
                .targetId(accountId)
                .build());

        String password = envelopeCipher.decrypt(secret);
        if (!MessageDigest.isEqual(account.getPasswordFingerprint(), envelopeCipher.fingerprint(password))) {
            throw new IllegalStateException("자격증명 무결성 검증 실패");
        }
        return password;
    }

    /** 패키지 내부 테스트 호환용. 외부 서비스·HTTP에서 호출할 수 없도록 공개하지 않는다. */
    @Transactional
    String loadPassword(Long accountId) {
        PlatformAccount account = platformAccountRepository.findById(accountId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
        return loadPasswordForOwner(accountId, account.getOwnerId());
    }

    @Transactional
    public void revoke(PlatformAccount account) {
        account.revoke();
        platformAccountRepository.save(account);
    }
}
