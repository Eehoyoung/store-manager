package com.storemanager.api.crypto;

import com.storemanager.api.audit.AuditLog;
import com.storemanager.api.audit.AuditLogRepository;
import com.storemanager.api.common.ApiException;
import com.storemanager.api.common.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
                .kmsKeyId(secret.keyId())
                .encAlgorithm(secret.algorithm())
                .build();
        return platformAccountRepository.save(account);
    }

    /** 자격증명을 복호화해 반환한다. 호출할 때마다 audit_log 에 CREDENTIAL_READ 를 남긴다. */
    @Transactional
    public String loadPassword(Long accountId) {
        PlatformAccount account = platformAccountRepository.findById(accountId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));

        EnvelopeCipher.EncryptedSecret secret = new EnvelopeCipher.EncryptedSecret(
                account.getEncPassword(), account.getEncDek(), account.getEncNonce(),
                account.getKmsKeyId(), account.getEncAlgorithm());
        String password = envelopeCipher.decrypt(secret);

        auditLogRepository.save(AuditLog.builder()
                .actorType("SYSTEM")
                .action("CREDENTIAL_READ")
                .targetType("PLATFORM_ACCOUNT")
                .targetId(accountId)
                .build());

        return password;
    }
}
