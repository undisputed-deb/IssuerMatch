package com.issuermatch.matching;

import com.issuermatch.team.InvestorMembershipService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The single definition of "this investor may see this deal": a human APPROVED a match run that ranked the firm this
 * user is a member of. Everything an investor can reach goes through here.
 */
@Component
public class MatchVisibility {

    private static final String APPROVED_FOR_FIRM = " FROM match_runs m JOIN match_results r ON r.run_id = m.id"
            + " WHERE m.status = 'APPROVED' AND r.investor_id = ?";

    private final JdbcTemplate db;
    private final InvestorMembershipService memberships;

    MatchVisibility(JdbcTemplate db, InvestorMembershipService memberships) {
        this.db = db;
        this.memberships = memberships;
    }

    public List<UUID> needsMatchedTo(UUID userId) {
        return memberships.find(userId).map(me -> db.queryForList("SELECT DISTINCT m.need_id" + APPROVED_FOR_FIRM, UUID.class,
                me.investorId())).orElse(List.of());
    }

    public boolean isMatched(UUID userId, UUID needId) {
        return matchedFirm(userId, needId).isPresent();
    }

    /** The caller's firm and role, only if a human approved that firm for this need. */
    public Optional<InvestorMembershipService.Membership> matchedFirm(UUID userId, UUID needId) {
        return memberships.find(userId).filter(me -> {
            Integer n = db.queryForObject("SELECT count(*)" + APPROVED_FOR_FIRM + " AND m.need_id = ?", Integer.class,
                    me.investorId(), needId);
            return n != null && n > 0;
        });
    }
}
