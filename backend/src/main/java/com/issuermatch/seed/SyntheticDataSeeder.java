package com.issuermatch.seed;

import com.issuermatch.common.Instrument;
import com.issuermatch.common.Region;
import com.issuermatch.common.RiskLevel;
import com.issuermatch.common.Sector;
import com.issuermatch.investor.InvestorType;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Fake investors, mandates, issuers and funding needs. All names are invented.
 * Seeded Random, so every run produces the same shape of data. Skips if investors already exist.
 */
@Component
public class SyntheticDataSeeder {

    static final int INVESTORS = 50;
    static final int ISSUERS = 20;

    private static final String[] INVESTOR_PREFIX = {"Northwind", "Blue Harbor", "Granite", "Lighthouse", "Evergreen",
            "Summit", "Ironwood", "Silverline", "Redwood", "Meridian"};
    private static final String[] INVESTOR_SUFFIX = {"Capital", "Ventures", "Partners", "Growth Fund", "Holdings"};
    private static final String[] ISSUER_PREFIX = {"Acme", "Orbit", "Helix", "Quanta", "Vertex", "Nimbus", "Cobalt",
            "Zenith", "Aurora", "Pioneer"};
    private static final String[] ISSUER_SUFFIX = {"Labs", "Systems"};
    private static final long[] MIN_TICKETS = {250_000, 500_000, 1_000_000, 2_000_000, 5_000_000};
    private static final int[] TICKET_SPREAD = {2, 4, 10};

    private final JdbcTemplate db;

    SyntheticDataSeeder(JdbcTemplate db) {
        this.db = db;
    }

    /** @return true if data was created, false if the database already had investors */
    @Transactional
    public boolean seed() {
        Integer existing = db.queryForObject("SELECT count(*) FROM investors", Integer.class);
        if (existing != null && existing > 0) {
            return false;
        }
        Random rnd = new Random(42);
        for (int i = 0; i < INVESTORS; i++) {
            seedInvestor(rnd, INVESTOR_PREFIX[i % 10] + " " + INVESTOR_SUFFIX[i / 10]);
        }
        for (int i = 0; i < ISSUERS; i++) {
            seedIssuer(rnd, ISSUER_PREFIX[i % 10] + " " + ISSUER_SUFFIX[i / 10]);
        }
        return true;
    }

    private void seedInvestor(Random rnd, String name) {
        Region region = pick(rnd, Region.values());
        UUID id = db.queryForObject("INSERT INTO investors(name,type,region) VALUES (?,?,?) RETURNING id",
                UUID.class, name, pick(rnd, InvestorType.values()).name(), region.name());
        int deals = rnd.nextInt(5); // 0..4 past deals
        for (int d = 0; d < deals; d++) {
            long amount = (1 + rnd.nextInt(40)) * 250_000L;
            db.update("INSERT INTO investor_deals(investor_id,issuer_name,sector,region,amount,instrument,closed_on)"
                            + " VALUES (?,?,?,?,?,?, current_date - ?)",
                    id, ISSUER_PREFIX[rnd.nextInt(10)] + " " + ISSUER_SUFFIX[rnd.nextInt(2)] + " (past)",
                    pick(rnd, Sector.values()).name(), pick(rnd, Region.values()).name(), BigDecimal.valueOf(amount),
                    pick(rnd, Instrument.values()).name(), 30 + rnd.nextInt(1400));
        }
        int mandates = 1 + rnd.nextInt(3);
        for (int m = 0; m < mandates; m++) {
            long min = pick(rnd, MIN_TICKETS);
            long max = min * pick(rnd, TICKET_SPREAD);
            Region mandateRegion = rnd.nextInt(10) < 6 ? region : pick(rnd, Region.values());
            db.update("INSERT INTO mandates(investor_id,sector,region,min_ticket,max_ticket,risk_level,instruments)"
                            + " VALUES (?,?,?,?,?,?,?::text[])",
                    id, pick(rnd, Sector.values()).name(), mandateRegion.name(), BigDecimal.valueOf(min),
                    BigDecimal.valueOf(max), pick(rnd, RiskLevel.values()).name(), instrumentArray(rnd));
        }
    }

    private void seedIssuer(Random rnd, String name) {
        Sector sector = pick(rnd, Sector.values());
        Region region = pick(rnd, Region.values());
        UUID id = db.queryForObject(
                "INSERT INTO issuers(name,sector,region,owner_user_id) VALUES (?,?,?,?) RETURNING id",
                UUID.class, name, sector.name(), region.name(), UUID.nameUUIDFromBytes(name.getBytes()));
        int needs = 1 + rnd.nextInt(2);
        for (int n = 0; n < needs; n++) {
            long amount = (2 + rnd.nextInt(79)) * 250_000L; // 500k .. 20M
            Instrument instrument = pick(rnd, Instrument.values());
            db.update("INSERT INTO funding_needs(issuer_id,title,description,sector,region,amount,instrument,"
                            + "risk_level,remaining_capacity) VALUES (?,?,?,?,?,?,?,?,?)",
                    id, name + " " + instrument.name().toLowerCase() + " round",
                    name + " is raising capital to expand its " + sector.name().toLowerCase().replace('_', ' ')
                            + " business in " + region.name().toLowerCase().replace('_', ' ') + ".",
                    sector.name(), region.name(), BigDecimal.valueOf(amount), instrument.name(),
                    pick(rnd, RiskLevel.values()).name(), BigDecimal.valueOf(amount));
        }
    }

    private static String instrumentArray(Random rnd) {
        List<String> chosen = new ArrayList<>();
        for (Instrument i : Instrument.values()) {
            if (rnd.nextBoolean()) chosen.add(i.name());
        }
        if (chosen.isEmpty()) chosen.add(pick(rnd, Instrument.values()).name());
        return "{" + String.join(",", chosen) + "}";
    }

    private static <T> T pick(Random rnd, T[] values) {
        return values[rnd.nextInt(values.length)];
    }

    private static long pick(Random rnd, long[] values) {
        return values[rnd.nextInt(values.length)];
    }

    private static int pick(Random rnd, int[] values) {
        return values[rnd.nextInt(values.length)];
    }
}
