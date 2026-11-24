package com.issuermatch.fundingneed;

import com.issuermatch.common.Instrument;
import com.issuermatch.common.Region;
import com.issuermatch.common.RiskLevel;
import com.issuermatch.common.Sector;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;

@Entity
@Table(name = "funding_needs")
public class FundingNeed {

    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    private UUID issuerId;
    private String title;
    private String description;
    @Enumerated(EnumType.STRING) private Sector sector;
    @Enumerated(EnumType.STRING) private Region region;
    private BigDecimal amount;
    @Enumerated(EnumType.STRING) private Instrument instrument;
    @Enumerated(EnumType.STRING) private RiskLevel riskLevel;
    @Enumerated(EnumType.STRING) private NeedStatus status = NeedStatus.SUBMITTED;
    private BigDecimal remainingCapacity;
    @Version private long version;
    @CreationTimestamp private Instant createdAt;

    protected FundingNeed() {}

    public FundingNeed(UUID issuerId, String title, String description, Sector sector, Region region,
                       BigDecimal amount, Instrument instrument, RiskLevel riskLevel) {
        this.issuerId = issuerId;
        this.title = title;
        this.description = description;
        this.sector = sector;
        this.region = region;
        this.amount = amount;
        this.instrument = instrument;
        this.riskLevel = riskLevel;
        this.remainingCapacity = amount;
    }

    public UUID getId() { return id; }
    public UUID getIssuerId() { return issuerId; }
    public String getTitle() { return title; }
    public String getDescription() { return description; }
    public Sector getSector() { return sector; }
    public Region getRegion() { return region; }
    public BigDecimal getAmount() { return amount; }
    public Instrument getInstrument() { return instrument; }
    public RiskLevel getRiskLevel() { return riskLevel; }
    public NeedStatus getStatus() { return status; }
    public BigDecimal getRemainingCapacity() { return remainingCapacity; }
    public long getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
}
