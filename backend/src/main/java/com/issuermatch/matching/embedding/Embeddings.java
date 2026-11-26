package com.issuermatch.matching.embedding;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Keeps vectors in the database. Embedding is never allowed to break a feature: if the provider fails, the thesis
 * simply stays unindexed (and is retried) and a need is scored without thesis alignment.
 */
@Service
public class Embeddings {

    private static final Logger log = LoggerFactory.getLogger(Embeddings.class);
    private static final int BATCH = 20;

    private final JdbcTemplate db;
    private final EmbeddingClient client;

    Embeddings(JdbcTemplate db, EmbeddingClient client) {
        this.db = db;
        this.client = client;
    }

    @Scheduled(fixedDelayString = "${app.embeddings.interval:15s}", initialDelayString = "${app.embeddings.initial-delay:5s}")
    void scheduled() {
        try {
            indexPendingTheses();
        } catch (Exception e) {
            log.warn("Thesis indexing failed, will retry: {}", e.toString());
        }
    }

    /** Embed theses that are new or changed. @return how many were indexed */
    public int indexPendingTheses() {
        var pending = db.queryForList("SELECT id, long_thesis_description AS thesis FROM investors"
                + " WHERE long_thesis_description IS NOT NULL AND thesis_embedded_hash IS DISTINCT FROM md5(long_thesis_description)"
                + " LIMIT " + BATCH);
        int done = 0;
        for (var row : pending) {
            String thesis = (String) row.get("thesis");
            try {
                // the WHERE guard drops the result if the thesis changed while we were embedding it
                done += db.update("UPDATE investors SET thesis_embedding = ?::vector, thesis_embedded_hash = md5(long_thesis_description)"
                        + " WHERE id = ? AND long_thesis_description = ?", literal(client.embed(thesis)), row.get("id"), thesis);
            } catch (RuntimeException e) {
                log.warn("Could not embed the thesis of investor {}: {}", row.get("id"), e.getClass().getSimpleName());
            }
        }
        return done;
    }

    /** The need's vector as a pgvector literal, computing and storing it on first use. Empty if it cannot be computed. */
    public Optional<String> ofNeed(UUID needId) {
        List<String> stored = db.queryForList("SELECT embedding::text FROM funding_needs WHERE id = ?", String.class, needId);
        if (stored.isEmpty()) {
            return Optional.empty();
        }
        if (stored.get(0) != null) {
            return Optional.of(stored.get(0));
        }
        String text = db.queryForObject("SELECT title || '. ' || sector || ' company in ' || region || '. ' || description"
                + " FROM funding_needs WHERE id = ?", String.class, needId);
        try {
            String vector = literal(client.embed(text.replace('_', ' ').toLowerCase()));
            db.update("UPDATE funding_needs SET embedding = ?::vector WHERE id = ?", vector, needId);
            return Optional.of(vector);
        } catch (RuntimeException e) {
            log.warn("Could not embed need {}: {}", needId, e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    private static String literal(float[] v) {
        StringBuilder sb = new StringBuilder(v.length * 10).append('[');
        for (int i = 0; i < v.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(v[i]);
        }
        return sb.append(']').toString();
    }
}
