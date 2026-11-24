package com.issuermatch.investor;

import com.issuermatch.common.Region;
import com.issuermatch.common.RiskLevel;
import com.issuermatch.common.Sector;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "mandates")
public class Mandate {

    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "investor_id") private Investor investor;
    @Enumerated(EnumType.STRING) private Sector sector;
    @Enumerated(EnumType.STRING) private Region region;
    private BigDecimal minTicket;
    private BigDecimal maxTicket;
    @Enumerated(EnumType.STRING) private RiskLevel riskLevel;
    @JdbcTypeCode(SqlTypes.ARRAY) @Column(columnDefinition = "text[]") private List<String> instruments;
    @CreationTimestamp private Instant createdAt;

    protected Mandate() {}

    public Mandate(Investor investor, Sector sector, Region region, BigDecimal minTicket, BigDecimal maxTicket,
                   RiskLevel riskLevel, List<String> instruments) {
        this.investor = investor;
        update(sector, region, minTicket, maxTicket, riskLevel, instruments);
    }

    public void update(Sector sector, Region region, BigDecimal minTicket, BigDecimal maxTicket,
                       RiskLevel riskLevel, List<String> instruments) {
        this.sector = sector;
        this.region = region;
        this.minTicket = minTicket;
        this.maxTicket = maxTicket;
        this.riskLevel = riskLevel;
        this.instruments = List.copyOf(instruments);
    }

    public UUID getId() { return id; }
    public Investor getInvestor() { return investor; }
    public Sector getSector() { return sector; }
    public Region getRegion() { return region; }
    public BigDecimal getMinTicket() { return minTicket; }
    public BigDecimal getMaxTicket() { return maxTicket; }
    public RiskLevel getRiskLevel() { return riskLevel; }
    public List<String> getInstruments() { return instruments; }
    public Instant getCreatedAt() { return createdAt; }
}
