package com.issuermatch.commitment;

import static org.assertj.core.api.Assertions.assertThat;

import com.issuermatch.support.IntegrationTest;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@IntegrationTest
@Transactional
class IdempotencyCleanupTest {

    @Autowired IdempotencyCleanup cleanup;
    @Autowired JdbcTemplate db;

    String keyAged(String age) {
        String key = "age-" + UUID.randomUUID();
        db.update("INSERT INTO idempotency_keys(actor, idem_key, request_hash, created_at) VALUES (?,?,'h', now() - ?::interval)",
                UUID.randomUUID(), key, age);
        return key;
    }

    boolean exists(String key) {
        return db.queryForObject("SELECT count(*) FROM idempotency_keys WHERE idem_key = ?", Integer.class, key) == 1;
    }

    @Test
    void removesOnlyKeysOlderThanTheLimit() {
        String old = keyAged("25 hours");
        String recent = keyAged("23 hours");

        int removed = cleanup.removeOlderThan(Duration.ofHours(24));

        assertThat(removed).isGreaterThanOrEqualTo(1);
        assertThat(exists(old)).isFalse();
        assertThat(exists(recent)).isTrue(); // a client may still be retrying with this one
    }
}
