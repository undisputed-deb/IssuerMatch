package com.issuermatch.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.issuermatch.support.IntegrationTest;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

/**
 * Attacker-style inputs against every kind of input. The rule: a client mistake is a 4xx in our error format.
 * Never a 5xx, never a stack trace, SQL text or class name in the answer.
 */
@IntegrationTest
@Transactional
class HostileInputTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate db;

    final List<String> failures = new ArrayList<>();

    /** The six characters backslash-u-0000: valid JSON that decodes to a NUL character. */
    static final String NUL = "\\" + "u0000";

    static String issuer(String name) {
        return "{\"name\":\"" + name + "\",\"sector\":\"FINTECH\",\"region\":\"EUROPE\"}";
    }

    String need(UUID issuerId, String title, String description, String amount) {
        return "{\"issuerId\":\"" + issuerId + "\",\"title\":\"" + title + "\",\"description\":\"" + description
                + "\",\"sector\":\"FINTECH\",\"region\":\"EUROPE\",\"amount\":" + amount + ",\"instrument\":\"EQUITY\",\"riskLevel\":\"LOW\"}";
    }

    UUID anIssuer() {
        return db.queryForObject("INSERT INTO issuers(name,sector,region,owner_user_id) VALUES (?,'FINTECH','EUROPE',?) RETURNING id",
                UUID.class, "Hostile " + UUID.randomUUID(), UUID.randomUUID());
    }

    /** Sends the request and records a failure unless the answer is a clean non-5xx. A savepoint keeps the test tx usable. */
    int probe(String label, MockHttpServletRequestBuilder request) {
        db.execute("SAVEPOINT probe");
        try {
            var response = mvc.perform(request).andReturn().getResponse();
            String body = response.getContentAsString();
            int status = response.getStatus();
            if (status >= 500) failures.add(label + " -> " + status);
            if (body.matches("(?s).*(Exception|org\\.springframework|org\\.postgresql|SELECT |INSERT |at com\\.).*") && status >= 400) {
                failures.add(label + " leaks internals: " + body.substring(0, Math.min(200, body.length())));
            }
            return status;
        } catch (Exception e) {
            failures.add(label + " -> threw " + e.getClass().getSimpleName());
            return 500;
        } finally {
            db.execute("ROLLBACK TO SAVEPOINT probe");
        }
    }

    MockHttpServletRequestBuilder json(String url, String body) {
        return post(url).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    @Test
    void sqlInjectionStringsAreJustStrings() {
        long before = db.queryForObject("SELECT count(*) FROM issuers", Long.class);
        for (String evil : new String[] {"'; DROP TABLE issuers; --", "' OR '1'='1", "\\\\'; SELECT pg_sleep(10); --", "1) UNION SELECT NULL--"}) {
            probe("sqli name " + evil, json("/issuers", issuer(evil)));
            probe("sqli sort " + evil, get("/issuers").param("sort", evil));
            probe("sqli filter " + evil, get("/funding-needs").param("issuerId", evil));
            probe("sqli dead letter topic " + evil, get("/admin/dead-letters").param("topic", evil));
            probe("sqli path " + evil, get("/issuers/{id}", evil));
        }
        assertThat(db.queryForObject("SELECT count(*) FROM issuers", Long.class)).isEqualTo(before); // table intact
        assertThat(failures).isEmpty();
    }

    @Test
    void nulBytesAreRejectedNotCrashing() {
        UUID id = anIssuer();
        assertThat(probe("NUL in issuer name", json("/issuers", issuer("bad" + NUL + "name")))).isEqualTo(400);
        assertThat(probe("NUL in need title", json("/funding-needs", need(id, "a" + NUL, "d", "10")))).isEqualTo(400);
        assertThat(probe("NUL in description", json("/funding-needs", need(id, "t", "x" + NUL + "y", "10")))).isEqualTo(400);
        probe("NUL in query", get("/admin/dead-letters").param("topic", "a\u0000b"));
        assertThat(failures).isEmpty();
    }

    @Test
    void hugeAndNegativePagingValuesAreHarmless() {
        for (String url : new String[] {"/issuers", "/investors", "/funding-needs", "/match-runs", "/admin/dead-letters"}) {
            probe(url + " huge page", get(url + "?page=2147483647&size=100"));
            probe(url + " huge size", get(url + "?size=2147483647"));
            probe(url + " negative", get(url + "?page=-1&size=-100"));
            probe(url + " text", get(url + "?page=abc&size=def"));
        }
        assertThat(failures).isEmpty();
    }

    @Test
    void wrongTypesNullsAndBrokenJson() {
        UUID id = anIssuer();
        String[] bodies = {"", " ", "null", "[]", "42", "\"text\"", "{", "}{", "{\"name\":null}", "{\"name\":[1]}", "{\"name\":{\"a\":1}}",
                "{\"sector\":5}", "{\"name\":\"a\",\"name\":\"b\"}", "{\"__proto__\":{}}", "[".repeat(2000) + "]".repeat(2000),
                "{\"a\":".repeat(2000) + "1" + "}".repeat(2000)};
        for (String b : bodies) {
            for (String url : new String[] {"/issuers", "/investors", "/funding-needs", "/commitments"}) {
                probe(url + " body " + b.substring(0, Math.min(25, b.length())),
                        json(url, b).header("Idempotency-Key", "hostile-" + UUID.randomUUID()));
            }
        }
        for (String amount : new String[] {"1e400", "-1e400", "1e1000000", "\"NaN\"", "\"10\"", "10.001", "99999999999999999999999", "-0"}) {
            probe("need amount " + amount, json("/funding-needs", need(id, "t", "d", amount)));
            probe("commit amount " + amount, json("/commitments", "{\"needId\":\"" + id + "\",\"investorId\":\"" + id + "\",\"amount\":" + amount + "}")
                    .header("Idempotency-Key", "hostile-" + UUID.randomUUID()));
        }
        assertThat(failures).isEmpty();
    }

    @Test
    void oddEncodingsAndContentTypes() {
        probe("invalid utf8", post("/issuers").contentType(MediaType.APPLICATION_JSON)
                .content(new byte[] {'{', '"', 'n', 'a', 'm', 'e', '"', ':', '"', (byte) 0xC3, (byte) 0x28, '"', '}'}));
        probe("no content type", post("/issuers").content(issuer("x")));
        probe("xml", post("/issuers").contentType(MediaType.APPLICATION_XML).content("<a/>"));
        probe("wrong charset", post("/issuers").contentType("application/json;charset=nonsense").content(issuer("x")));
        assertThat(probe("emoji and rtl", json("/issuers", issuer("🚀 \u202Eevil\u202C é " + UUID.randomUUID())))).isEqualTo(201);
        probe("blank name", json("/issuers", issuer("   ")));
        probe("lone surrogate", post("/issuers").contentType(MediaType.APPLICATION_JSON)
                .content(issuer("bad\\ud800name").getBytes(StandardCharsets.UTF_8)));
        assertThat(failures).isEmpty();
    }

    @Test
    void anOversizedBodyIsRefusedWith413() {
        String huge = "x".repeat(2_000_000);
        assertThat(probe("2MB body", json("/funding-needs", need(anIssuer(), "t", huge, "10")))).isEqualTo(413);
        assertThat(failures).isEmpty();
    }
}
