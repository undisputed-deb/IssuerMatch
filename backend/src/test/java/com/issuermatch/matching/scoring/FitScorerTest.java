package com.issuermatch.matching.scoring;

import static org.assertj.core.api.Assertions.assertThat;

import com.issuermatch.common.Instrument;
import com.issuermatch.common.Region;
import com.issuermatch.common.RiskLevel;
import com.issuermatch.common.Sector;
import com.issuermatch.fundingneed.HardConstraints;
import com.issuermatch.investor.InvestorType;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class FitScorerTest {

    static BigDecimal bd(long v) {
        return BigDecimal.valueOf(v);
    }

    static NeedProfile need(Long minTicket, HardConstraints c) {
        return new NeedProfile(Sector.FINTECH, Region.EUROPE, bd(1_000_000), minTicket == null ? null : bd(minTicket),
                Instrument.EQUITY, RiskLevel.MEDIUM, c);
    }

    static final NeedProfile NEED = need(null, HardConstraints.NONE);

    static MandateProfile mandate(Sector s, Region r, long min, long max, RiskLevel risk, String... instruments) {
        return new MandateProfile(s, r, bd(min), bd(max), risk, List.of(instruments));
    }

    static MandateProfile perfectMandate() {
        return mandate(Sector.FINTECH, Region.EUROPE, 500_000, 2_000_000, RiskLevel.MEDIUM, "EQUITY", "DEBT");
    }

    static InvestorFacts investor(Double similarity, int sectorDeals, int regionDeals, MandateProfile... mandates) {
        return new InvestorFacts(InvestorType.VC, Region.EUROPE, bd(50_000_000), List.of(mandates), sectorDeals, regionDeals, similarity);
    }

    static ScoreBreakdown score(MandateProfile m) {
        return FitScorer.score(NEED, investor(null, 0, 0, m));
    }

    static double d(BigDecimal v) {
        return v.doubleValue();
    }

    @Test
    void everyCriterionAtItsMaximumIsOneHundred() {
        ScoreBreakdown b = FitScorer.score(NEED, investor(1.0, 5, 5, perfectMandate()));
        assertThat(List.of(d(b.mandateFit()), d(b.ticketFit()), d(b.regionFit()), d(b.riskFit()), d(b.trackRecord()), d(b.thesisAlignment())))
                .containsExactly(25.0, 25.0, 15.0, 10.0, 10.0, 15.0);
        assertThat(d(b.total())).isEqualTo(100.0);
        assertThat(b.qualified()).isTrue();
    }

    @Test
    void sectorAndInstrumentMakeUpTheMandateFit() {
        assertThat(d(score(mandate(Sector.ENERGY, Region.EUROPE, 1, 2_000_000, RiskLevel.MEDIUM, "EQUITY")).mandateFit())).isEqualTo(5.0);
        assertThat(d(score(mandate(Sector.FINTECH, Region.EUROPE, 1, 2_000_000, RiskLevel.MEDIUM, "DEBT")).mandateFit())).isEqualTo(20.0);
        assertThat(d(score(mandate(Sector.ENERGY, Region.EUROPE, 1, 2_000_000, RiskLevel.MEDIUM, "DEBT")).mandateFit())).isZero();
    }

    @Test
    void regionAndRiskArePreferences() {
        assertThat(d(score(mandate(Sector.FINTECH, Region.LATAM, 1, 2_000_000, RiskLevel.MEDIUM, "EQUITY")).regionFit())).isZero();
        assertThat(d(score(mandate(Sector.FINTECH, Region.EUROPE, 1, 2_000_000, RiskLevel.LOW, "EQUITY")).riskFit())).isEqualTo(5.0);
    }

    @Test
    void ticketOutsideTheWindowScoresByCloseness() {
        assertThat(d(score(mandate(Sector.FINTECH, Region.EUROPE, 2_000_000, 4_000_000, RiskLevel.MEDIUM, "EQUITY")).ticketFit())).isEqualTo(12.5);
        assertThat(d(score(mandate(Sector.FINTECH, Region.EUROPE, 100_000, 500_000, RiskLevel.MEDIUM, "EQUITY")).ticketFit())).isEqualTo(12.5);
        assertThat(d(score(mandate(Sector.FINTECH, Region.EUROPE, 1_000_000, 1_000_000, RiskLevel.MEDIUM, "EQUITY")).ticketFit())).isEqualTo(25.0);
    }

    @Test
    void aSmallerTicketCountsWhenTheIssuerAcceptsIt() {
        MandateProfile small = mandate(Sector.FINTECH, Region.EUROPE, 100_000, 300_000, RiskLevel.MEDIUM, "EQUITY");
        assertThat(d(FitScorer.score(need(250_000L, HardConstraints.NONE), investor(null, 0, 0, small)).ticketFit())).isEqualTo(25.0);
    }

    @Test
    void trackRecordAndThesisAddPointsAndAreCapped() {
        assertThat(d(FitScorer.score(NEED, investor(null, 1, 0, perfectMandate())).trackRecord())).isEqualTo(4.0);
        assertThat(d(FitScorer.score(NEED, investor(null, 1, 2, perfectMandate())).trackRecord())).isEqualTo(8.0);
        assertThat(d(FitScorer.score(NEED, investor(null, 9, 9, perfectMandate())).trackRecord())).isEqualTo(10.0);
        assertThat(d(FitScorer.score(NEED, investor(0.5, 0, 0, perfectMandate())).thesisAlignment())).isEqualTo(7.5);
        assertThat(d(FitScorer.score(NEED, investor(-0.4, 0, 0, perfectMandate())).thesisAlignment())).isZero();
        assertThat(d(FitScorer.score(NEED, investor(null, 0, 0, perfectMandate())).thesisAlignment())).isZero();
    }

    // ---- hard constraints ----

    static List<String> broken(HardConstraints c, Long minTicket, InvestorFacts who) {
        return FitScorer.score(need(minTicket, c), who).disqualifiers();
    }

    @Test
    void eachHardConstraintDisqualifiesWhateverTheScore() {
        InvestorFacts good = investor(1.0, 5, 5, perfectMandate());
        assertThat(broken(new HardConstraints(null, List.of(InvestorType.VC), null, null, null), null, good)).singleElement().asString().contains("excluded");
        assertThat(broken(new HardConstraints(List.of(Region.LATAM), null, null, null, null), null, good)).singleElement().asString().contains("region");
        InvestorFacts debtOnly = investor(null, 0, 0, mandate(Sector.FINTECH, Region.LATAM, 1, 2_000_000, RiskLevel.MEDIUM, "DEBT"));
        assertThat(broken(new HardConstraints(null, null, true, null, null), null, debtOnly)).singleElement().asString().contains("EQUITY");
        assertThat(broken(new HardConstraints(null, null, null, true, null), null, debtOnly)).singleElement().asString().contains("covers EUROPE");
        assertThat(broken(new HardConstraints(null, null, null, null, bd(100_000_000)), null, good)).singleElement().asString().contains("deployable");
        InvestorFacts unknownCapital = new InvestorFacts(InvestorType.VC, Region.EUROPE, null, List.of(perfectMandate()), 0, 0, null);
        assertThat(broken(new HardConstraints(null, null, null, null, bd(1)), null, unknownCapital)).hasSize(1);
        assertThat(broken(HardConstraints.NONE, 5_000_000L, good)).singleElement().asString().contains("smallest ticket");
    }

    @Test
    void satisfiedOrEmptyConstraintsDisqualifyNobody() {
        InvestorFacts good = investor(null, 0, 0, perfectMandate());
        assertThat(broken(HardConstraints.NONE, null, good)).isEmpty();
        assertThat(broken(new HardConstraints(List.of(), List.of(), false, false, null), null, good)).isEmpty();
        assertThat(broken(new HardConstraints(List.of(Region.EUROPE), List.of(InvestorType.PE), true, true, bd(1_000_000)), 100_000L, good)).isEmpty();
    }

    @Test
    void theBestMandateCountsWhateverTheOrder() {
        MandateProfile weak = mandate(Sector.ENERGY, Region.LATAM, 5_000_000, 9_000_000, RiskLevel.HIGH, "DEBT");
        assertThat(FitScorer.score(NEED, investor(null, 0, 0, weak, perfectMandate())))
                .isEqualTo(FitScorer.score(NEED, investor(null, 0, 0, perfectMandate(), weak)));
        assertThat(d(FitScorer.score(NEED, investor(null, 0, 0, weak, perfectMandate())).total())).isEqualTo(75.0);
        assertThat(d(FitScorer.score(NEED, investor(null, 0, 0)).total())).isZero();
    }
}
