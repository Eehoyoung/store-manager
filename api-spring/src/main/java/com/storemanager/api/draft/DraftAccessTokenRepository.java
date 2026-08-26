package com.storemanager.api.draft;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DraftAccessTokenRepository extends JpaRepository<DraftAccessToken, Long> {

    /** ★ 평문이 아니라 해시로 찾는다. 평문은 DB 에 없다. */
    Optional<DraftAccessToken> findByTokenHash(String tokenHash);
}
