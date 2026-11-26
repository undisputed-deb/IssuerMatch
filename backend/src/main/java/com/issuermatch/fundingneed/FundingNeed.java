package com.issuermatch.fundingneed;

import com.issuermatch.common.Instrument;
import com.issuermatch.common.Region;
import com.issuermatch.common.RiskLevel;
import com.issuermatch.common.Sector;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.ColumnTransformer;
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
    private String currency = "USD";
    @Enumerated(EnumType.STRING) private CompanyStage companyStage;
    private LocalDate targetCloseDate;
    @Convert(converter = JsonColumns.UseOfFunds.class) @ColumnTransformer(write = "?::jsonb")
    @Column(columnDefinition = "jsonb") private List<String> useOfFunds = List.of();
    private BigDecimal minTicketSize;
    @Convert(converter = JsonColumns.Constraints.class) @ColumnTransformer(write = "?::jsonb")
    @Column(columnDefinition = "jsonb") private HardConstraints hardConstraints = HardConstraints.NONE;
    @Convert(converter = JsonColumns.Terms.class) @ColumnTransformer(write = "?::jsonb")
    @Column(columnDefinition = "jsonb") private InstrumentTerms instrumentTerms;
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

    /** The optional institutional details. Anything null keeps its default. */
    public FundingNeed withIntake(String currency, CompanyStage stage, LocalDate targetClose, List<String> useOfFunds,
                                  BigDecimal minTicket, HardConstraints constraints, InstrumentTerms terms) {
        if (currency != null) this.currency = currency;
        this.companyStage = stage;
        this.targetCloseDate = targetClose;
        if (useOfFunds != null) this.useOfFunds = List.copyOf(useOfFunds);
        this.minTicketSize = minTicket;
        if (constraints != null) this.hardConstraints = constraints;
        this.instrumentTerms = terms;
        return this;
    }

    public String getCurrency() { return currency; }
    public CompanyStage getCompanyStage() { return companyStage; }
    public LocalDate getTargetCloseDate() { return targetCloseDate; }
    public List<String> getUseOfFunds() { return useOfFunds; }
    public BigDecimal getMinTicketSize() { return minTicketSize; }
    public HardConstraints getHardConstraints() { return hardConstraints; }
    public InstrumentTerms getInstrumentTerms() { return instrumentTerms; }
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
