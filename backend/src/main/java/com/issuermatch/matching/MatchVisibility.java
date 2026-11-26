package com.issuermatch.matching;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The single definition of "this investor may see this deal": a human APPROVED a match run that ranked the investor
 * profile owned by that user. Everything an investor can reach goes through here.
 */
@Component
public class MatchVisibility {

    private static final String APPROVED_FOR_USER = " FROM match_runs m JOIN match_results r ON r.run_id = m.id"
            + " JOIN investors i ON i.id = r.investor_id WHERE m.status = 'APPROVED' AND i.owner_user_id = ?";

    private final JdbcTemplate db;

    MatchVisibility(JdbcTemplate db) {
        this.db = db;
    }

    public List<UUID> needsMatchedTo(UUID userId) {
        return db.queryForList("SELECT DISTINCT m.need_id" + APPROVED_FOR_USER, UUID.class, userId);
    }

    public boolean isMatched(UUID userId, UUID needId) {
        Integer n = db.queryForObject("SELECT count(*)" + APPROVED_FOR_USER + " AND m.need_id = ?", Integer.class, userId, needId);
        return n != null && n > 0;
    }
}
