package com.issuermatch.matching;

import com.issuermatch.common.ApiException;
import com.issuermatch.common.NotFoundException;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The human gate. A run can be approved or rejected exactly once, and only from PENDING_REVIEW.
 * The agent never calls this: it has no write tools.
 */
@Service
class MatchReviewService {

    private final JdbcTemplate db;

    MatchReviewService(JdbcTemplate db) {
        this.db = db;
    }

    @Transactional
    void review(UUID runId, boolean approve, UUID reviewer) {
        // Conditional update = atomic compare-and-set. Two analysts clicking at once: one wins, one gets 409.
        int changed = db.update("UPDATE match_runs SET status = ?, reviewed_at = now(), reviewed_by = ?"
                + " WHERE id = ? AND status = 'PENDING_REVIEW'", approve ? "APPROVED" : "REJECTED", reviewer, runId);
        if (changed == 0) {
            Integer exists = db.queryForObject("SELECT count(*) FROM match_runs WHERE id = ?", Integer.class, runId);
            if (exists == null || exists == 0) {
                throw new NotFoundException("Match run not found");
            }
            throw new ApiException(HttpStatus.CONFLICT, "Run is not pending review");
        }
        // approved -> the need is matched. rejected -> it goes back to SUBMITTED so it can be matched again later.
        db.update("UPDATE funding_needs SET status = ? WHERE id = (SELECT need_id FROM match_runs WHERE id = ?)",
                approve ? "MATCHED" : "SUBMITTED", runId);
    }
}
