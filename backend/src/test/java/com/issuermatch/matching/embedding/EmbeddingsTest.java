package com.issuermatch.matching.embedding;

import static org.assertj.core.api.Assertions.assertThat;

import com.issuermatch.support.IntegrationTest;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/** A provider outage must cost one attempt, not one per investor being scored. */
@IntegrationTest
@Transactional
class EmbeddingsTest {

    @Autowired JdbcTemplate db;

    final AtomicInteger calls = new AtomicInteger();
    boolean down = true;

    final EmbeddingClient client = text -> {
        calls.incrementAndGet();
        if (down) {
            throw new IllegalStateException("provider outage");
        }
        float[] v = new float[EmbeddingClient.DIMENSIONS];
        v[0] = 1;
        return v;
    };

    UUID need() {
        UUID issuer = db.queryForObject("INSERT INTO issuers(name,sector,region,owner_user_id) VALUES (?,'FINTECH','EUROPE',?) RETURNING id",
                UUID.class, "Emb " + UUID.randomUUID(), UUID.randomUUID());
        return db.queryForObject("INSERT INTO funding_needs(issuer_id,title,description,sector,region,amount,instrument,risk_level,remaining_capacity)"
                + " VALUES (?,'Round','d','FINTECH','EUROPE',1000,'EQUITY','LOW',1000) RETURNING id", UUID.class, issuer);
    }

    @Test
    void afterAFailureTheSameNeedIsNotAskedForAgainStraightAway() {
        Embeddings embeddings = new Embeddings(db, client, Duration.ofMinutes(1));
        UUID first = need();
        for (int candidate = 0; candidate < 20; candidate++) {
            assertThat(embeddings.ofNeed(first)).isEmpty();
        }
        assertThat(calls).hasValue(1);
        assertThat(embeddings.ofNeed(need())).isEmpty(); // another need still gets its own attempt
        assertThat(calls).hasValue(2);
    }

    @Test
    void theNeedIsTriedAgainOnceTheWaitIsOver() {
        Embeddings embeddings = new Embeddings(db, client, Duration.ZERO);
        UUID need = need();
        assertThat(embeddings.ofNeed(need)).isEmpty();
        down = false;
        assertThat(embeddings.ofNeed(need)).isPresent();
        assertThat(embeddings.ofNeed(need)).isPresent();
        assertThat(calls).hasValue(2); // once failed, once stored; the stored vector is reused
    }
}
