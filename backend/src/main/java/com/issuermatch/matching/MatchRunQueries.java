package com.issuermatch.matching;

import com.issuermatch.common.NotFoundException;
import com.issuermatch.matching.MatchRunDtos.Detail;
import com.issuermatch.matching.MatchRunDtos.Result;
import com.issuermatch.matching.MatchRunDtos.Summary;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
class MatchRunQueries {

    private static final String COLUMNS = "id, need_id, status, tokens_in, tokens_out, latency_ms, error, reviewed_at, created_at, input_flags";

    private final JdbcTemplate db;
    private final JsonMapper json;

    MatchRunQueries(JdbcTemplate db, JsonMapper json) {
        this.db = db;
        this.json = json;
    }

    Page<Summary> list(UUID needId, Pageable pageable) {
        String where = needId == null ? "" : " WHERE need_id = ?";
        Object[] filter = needId == null ? new Object[0] : new Object[] {needId};
        Long total = db.queryForObject("SELECT count(*) FROM match_runs" + where, Long.class, filter);
        // newest first; the sort query parameter is not supported on this endpoint
        List<Summary> rows = db.query("SELECT " + COLUMNS + " FROM match_runs" + where
                        + " ORDER BY created_at DESC LIMIT " + pageable.getPageSize() + " OFFSET " + pageable.getOffset(),
                (rs, n) -> summary(rs), filter);
        return new PageImpl<>(rows, pageable, total == null ? 0 : total);
    }

    Summary summary(UUID runId) {
        return db.query("SELECT " + COLUMNS + " FROM match_runs WHERE id = ?", (rs, n) -> summary(rs), runId)
                .stream().findFirst().orElseThrow(() -> new NotFoundException("Match run not found"));
    }

    Detail detail(UUID runId) {
        Summary s = summary(runId);
        String calls = db.queryForObject("SELECT tool_calls::text FROM match_runs WHERE id = ?", String.class, runId);
        return new Detail(s, json.readTree(calls));
    }

    List<Result> results(UUID runId) {
        summary(runId); // 404 for an unknown run
        return db.query("SELECT r.rank, r.investor_id, i.name, r.score, r.explanation, r.cited_tools::text AS cited"
                        + " FROM match_results r JOIN investors i ON i.id = r.investor_id WHERE r.run_id = ? ORDER BY r.rank",
                (rs, n) -> {
                    JsonNode cited = json.readTree(rs.getString("cited"));
                    List<String> tools = cited.valueStream().map(JsonNode::asString).toList();
                    return new Result(rs.getInt("rank"), rs.getObject("investor_id", UUID.class), rs.getString("name"),
                            rs.getBigDecimal("score"), rs.getString("explanation"), tools);
                }, runId);
    }

    private static Summary summary(ResultSet rs) throws SQLException {
        return new Summary(rs.getObject("id", UUID.class), rs.getObject("need_id", UUID.class), rs.getString("status"),
                (Integer) rs.getObject("tokens_in"), (Integer) rs.getObject("tokens_out"), (Integer) rs.getObject("latency_ms"),
                rs.getString("error"), instant(rs, "reviewed_at"), instant(rs, "created_at"),
                List.of((String[]) rs.getArray("input_flags").getArray()));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        var ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }
}
