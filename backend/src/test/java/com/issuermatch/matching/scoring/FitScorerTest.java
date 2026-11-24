package com.issuermatch.matching.scoring;

import static org.assertj.core.api.Assertions.assertThat;

import com.issuermatch.common.Instrument;
import com.issuermatch.common.Region;
import com.issuermatch.common.RiskLevel;
import com.issuermatch.common.Sector;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class FitScorerTest {

    static final NeedProfile NEED = new NeedProfile(Sector.FINTECH, Region.EUROPE, bd(1_000_000),
            Instrument.EQUITY, RiskLevel.MEDIUM);

    static BigDecimal bd(long v) {
        return BigDecimal.valueOf(v);
    }

    static MandateProfile mandate(Sector s, Region r, long min, long max, RiskLevel risk, String... instruments) {
        return new MandateProfile(s, r, bd(min), bd(max), risk, List.of(instruments));
    }

    static MandateProfile perfect() {
        return mandate(Sector.FINTECH, Region.EUROPE, 500_000, 2_000_000, RiskLevel.MEDIUM, "EQUITY", "DEBT");
    }

    double total(MandateProfile m) {
        return FitScorer.score(NEED, m).total().doubleValue();
    }

    @Test
    void perfectFitScoresOneHundred() {
        assertThat(total(perfect())).isEqualTo(100.0);
    }

    @Test
    void sectorMismatchCostsThirty() {
        assertThat(total(mandate(Sector.ENERGY, Region.EUROPE, 500_000, 2_000_000, RiskLevel.MEDIUM, "EQUITY"))).isEqualTo(70.0);
    }

    @Test
    void regionMismatchCostsTwenty() {
        assertThat(total(mandate(Sector.FINTECH, Region.LATAM, 500_000, 2_000_000, RiskLevel.MEDIUM, "EQUITY"))).isEqualTo(80.0);
    }

    @Test
    void ticketOutsideRangeLosesPointsInProportionToDistance() {
        // need 1M, mandate min 2M -> ratio 0.5 -> 15 of 30 ticket points
        assertThat(FitScorer.score(NEED, mandate(Sector.FINTECH, Region.EUROPE, 2_000_000, 4_000_000, RiskLevel.MEDIUM, "EQUITY"))
                .ticket().doubleValue()).isEqualTo(15.0);
        // need 1M, mandate max 500k -> ratio 0.5 -> 15
        assertThat(FitScorer.score(NEED, mandate(Sector.FINTECH, Region.EUROPE, 100_000, 500_000, RiskLevel.MEDIUM, "EQUITY"))
                .ticket().doubleValue()).isEqualTo(15.0);
    }

    @Test
    void ticketAtExactBoundaryCountsAsInside() {
        assertThat(FitScorer.score(NEED, mandate(Sector.FINTECH, Region.EUROPE, 1_000_000, 1_000_000, RiskLevel.MEDIUM, "EQUITY"))
                .ticket().doubleValue()).isEqualTo(30.0);
    }

    @Test
    void riskScoresFullAdjacentOrZero() {
        assertThat(FitScorer.score(NEED, mandate(Sector.FINTECH, Region.EUROPE, 1, 2_000_000, RiskLevel.MEDIUM, "EQUITY")).risk().doubleValue()).isEqualTo(10.0);
        assertThat(FitScorer.score(NEED, mandate(Sector.FINTECH, Region.EUROPE, 1, 2_000_000, RiskLevel.LOW, "EQUITY")).risk().doubleValue()).isEqualTo(5.0);
        NeedProfile highNeed = new NeedProfile(Sector.FINTECH, Region.EUROPE, bd(1_000_000), Instrument.EQUITY, RiskLevel.HIGH);
        assertThat(FitScorer.score(highNeed, mandate(Sector.FINTECH, Region.EUROPE, 1, 2_000_000, RiskLevel.LOW, "EQUITY")).risk().doubleValue()).isEqualTo(0.0);
    }

    @Test
    void instrumentMustBeInTheMandate() {
        assertThat(total(mandate(Sector.FINTECH, Region.EUROPE, 500_000, 2_000_000, RiskLevel.MEDIUM, "DEBT"))).isEqualTo(90.0);
    }

    @Test
    void sameInputsAlwaysGiveTheSameScore() {
        assertThat(FitScorer.score(NEED, perfect())).isEqualTo(FitScorer.score(NEED, perfect()));
    }

    @Test
    void bestOfSeveralMandatesIgnoresOrder() {
        MandateProfile weak = mandate(Sector.ENERGY, Region.LATAM, 5_000_000, 9_000_000, RiskLevel.HIGH, "DEBT");
        assertThat(FitScorer.best(NEED, List.of(weak, perfect())).orElseThrow().total().doubleValue()).isEqualTo(100.0);
        assertThat(FitScorer.best(NEED, List.of(perfect(), weak)).orElseThrow().total().doubleValue()).isEqualTo(100.0);
        assertThat(FitScorer.best(NEED, List.of())).isEmpty();
    }

    @Test
    void scoreStaysWithinZeroAndOneHundred() {
        MandateProfile worst = mandate(Sector.ENERGY, Region.LATAM, 900_000_000, 999_000_000, RiskLevel.HIGH, "DEBT");
        NeedProfile lowNeed = new NeedProfile(Sector.FINTECH, Region.EUROPE, bd(1), Instrument.EQUITY, RiskLevel.LOW);
        double t = FitScorer.score(lowNeed, worst).total().doubleValue();
        assertThat(t).isBetween(0.0, 100.0);
    }
}
