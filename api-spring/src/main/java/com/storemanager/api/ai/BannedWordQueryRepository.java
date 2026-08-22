package com.storemanager.api.ai;

import com.storemanager.api.ai.AiClientDtos.BannedWordIn;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class BannedWordQueryRepository {

    private final JdbcTemplate jdbcTemplate;

    public BannedWordQueryRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<BannedWordIn> findActiveGlobal() {
        return jdbcTemplate.query(
                "SELECT word, category, match_type FROM banned_word WHERE active = TRUE AND scope = 'GLOBAL' ORDER BY id",
                (rs, rowNum) -> new BannedWordIn(rs.getString("word"), rs.getString("category"),
                        rs.getString("match_type")));
    }
}
