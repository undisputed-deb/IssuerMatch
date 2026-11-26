package com.issuermatch.issuer;

import com.issuermatch.common.Region;
import com.issuermatch.common.Sector;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;

@Entity
@Table(name = "issuers")
public class Issuer {

    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    private String name;
    @Enumerated(EnumType.STRING) private Sector sector;
    @Enumerated(EnumType.STRING) private Region region;
    private UUID ownerUserId;
    @Embedded private CompanyProfile profile;
    @Column(insertable = false, updatable = false) private boolean verified; // written by VerificationService only
    @CreationTimestamp private Instant createdAt;

    protected Issuer() {}

    public Issuer(String name, Sector sector, Region region, UUID ownerUserId) {
        this.name = name;
        this.sector = sector;
        this.region = region;
        this.ownerUserId = ownerUserId;
    }

    public void update(String name, Sector sector, Region region) {
        this.name = name;
        this.sector = sector;
        this.region = region;
    }

    public void describe(CompanyProfile profile) {
        this.profile = profile;
    }

    /** Never null: Hibernate reads a row with every profile column empty as no object at all. */
    public CompanyProfile getProfile() { return profile == null ? CompanyProfile.EMPTY : profile; }
    public boolean isVerified() { return verified; }
    public UUID getId() { return id; }
    public String getName() { return name; }
    public Sector getSector() { return sector; }
    public Region getRegion() { return region; }
    public UUID getOwnerUserId() { return ownerUserId; }
    public Instant getCreatedAt() { return createdAt; }
}
