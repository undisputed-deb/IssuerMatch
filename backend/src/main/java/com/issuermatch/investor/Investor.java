package com.issuermatch.investor;

import com.issuermatch.common.Region;
import jakarta.persistence.*;
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
    @Enumerated(EnumType.STRING) private InvestorType type;
    @Enumerated(EnumType.STRING) private Region region;
    private UUID ownerUserId; // null for profiles entered by staff
    @CreationTimestamp private Instant createdAt;

    // N+1 fix: load mandates for up to 100 investors in ONE query (WHERE investor_id IN (...)).
    // JOIN FETCH would break paging (Hibernate would page in memory).
    @BatchSize(size = 100)
    @OneToMany(mappedBy = "investor", cascade = CascadeType.REMOVE)
    private List<Mandate> mandates = new ArrayList<>();

    protected Investor() {}

    public Investor(String name, InvestorType type, Region region) {
        this.name = name;
        this.type = type;
        this.region = region;
    }

    public void update(String name, InvestorType type, Region region) {
        this.name = name;
        this.type = type;
        this.region = region;
    }

    /** Link the profile to the user who owns it. Set once, at creation. */
    public Investor ownedBy(UUID userId) {
        this.ownerUserId = userId;
        return this;
    }

    public UUID getId() { return id; }
    public UUID getOwnerUserId() { return ownerUserId; }
    public String getName() { return name; }
    public InvestorType getType() { return type; }
    public Region getRegion() { return region; }
    public Instant getCreatedAt() { return createdAt; }
    public List<Mandate> getMandates() { return mandates; }
}
