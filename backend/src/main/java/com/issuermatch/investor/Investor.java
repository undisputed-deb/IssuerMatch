package com.issuermatch.investor;

import com.issuermatch.common.Region;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.BatchSize;
import org.hibernate.annotations.CreationTimestamp;

@Entity
@Table(name = "investors")
public class Investor {

    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    private String name;
    @Enumerated(EnumType.STRING) private InvestorType investorType;
    @Enumerated(EnumType.STRING) private Region region;
    private UUID ownerUserId; // null for profiles entered by staff
    private String contactName;
    private String roleTitle;
    private String companyName;
    private String websiteUrl;
    private BigDecimal aumOrRevenueUsd;
    private BigDecimal deployableCapitalUsd;
    private String currency = "USD";
    private String longThesisDescription;
    private boolean profileCompleted;
    @Enumerated(EnumType.STRING) private ParticipationRole participationRole;
    @Enumerated(EnumType.STRING) private DeploymentStatus deploymentStatus = DeploymentStatus.ACTIVE;
    @Column(insertable = false, updatable = false) private boolean verified; // written by VerificationService only
    @CreationTimestamp private Instant createdAt;

    // N+1 fix: load mandates for up to 100 investors in ONE query (WHERE investor_id IN (...)).
    // JOIN FETCH would break paging (Hibernate would page in memory).
    @BatchSize(size = 100)
    @OneToMany(mappedBy = "investor", cascade = CascadeType.REMOVE)
    private List<Mandate> mandates = new ArrayList<>();

    protected Investor() {}

    public Investor(String name, InvestorType investorType, Region region) {
        this.name = name;
        this.investorType = investorType;
        this.region = region;
    }

    public void update(String name, InvestorType investorType, Region region) {
        this.name = name;
        this.investorType = investorType;
        this.region = region;
    }

    /** The full institutional profile, given by the investor. All required parts are validated, so it is complete. */
    public void completeProfile(InvestorDtos.ProfileRequest p) {
        update(p.companyName(), p.investorType(), p.region());
        this.contactName = p.contactName();
        this.roleTitle = p.roleTitle();
        this.companyName = p.companyName();
        this.websiteUrl = p.websiteUrl();
        this.aumOrRevenueUsd = p.aumOrRevenueUsd();
        this.deployableCapitalUsd = p.deployableCapitalUsd();
        this.currency = p.currency();
        this.longThesisDescription = p.longThesisDescription();
        this.participationRole = p.participationRole();
        if (p.deploymentStatus() != null) {
            this.deploymentStatus = p.deploymentStatus();
        }
        this.profileCompleted = true;
    }

    /** Link the profile to the user who owns it. Set once, at creation. */
    public Investor ownedBy(UUID userId) {
        this.ownerUserId = userId;
        return this;
    }

    public UUID getId() { return id; }
    public UUID getOwnerUserId() { return ownerUserId; }
    public String getName() { return name; }
    public InvestorType getInvestorType() { return investorType; }
    public String getContactName() { return contactName; }
    public String getRoleTitle() { return roleTitle; }
    public String getCompanyName() { return companyName; }
    public String getWebsiteUrl() { return websiteUrl; }
    public BigDecimal getAumOrRevenueUsd() { return aumOrRevenueUsd; }
    public BigDecimal getDeployableCapitalUsd() { return deployableCapitalUsd; }
    public String getCurrency() { return currency; }
    public String getLongThesisDescription() { return longThesisDescription; }
    public boolean isProfileCompleted() { return profileCompleted; }
    /** Mirrors a revocation in memory. The column itself is written by VerificationService. */
    public void badgeWithdrawn() {
        this.verified = false;
    }

    public ParticipationRole getParticipationRole() { return participationRole; }
    public DeploymentStatus getDeploymentStatus() { return deploymentStatus; }
    public boolean isVerified() { return verified; }
    public Region getRegion() { return region; }
    public Instant getCreatedAt() { return createdAt; }
    public List<Mandate> getMandates() { return mandates; }
}
