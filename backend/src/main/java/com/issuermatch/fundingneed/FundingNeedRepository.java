package com.issuermatch.fundingneed;

import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FundingNeedRepository extends JpaRepository<FundingNeed, UUID> {

    Page<FundingNeed> findByIssuerId(UUID issuerId, Pageable pageable);

    /** Needs of companies owned by this user: what an issuer is allowed to see. */
    @Query("select n from FundingNeed n where n.issuerId in (select i.id from Issuer i where i.ownerUserId = :owner)")
    Page<FundingNeed> findOwnedBy(@Param("owner") UUID owner, Pageable pageable);

    @Query("select n from FundingNeed n where n.issuerId = :issuerId"
            + " and n.issuerId in (select i.id from Issuer i where i.ownerUserId = :owner)")
    Page<FundingNeed> findOwnedBy(@Param("owner") UUID owner, @Param("issuerId") UUID issuerId, Pageable pageable);
}
