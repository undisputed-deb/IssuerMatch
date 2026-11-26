package com.issuermatch.fundingneed;

import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FundingNeedRepository extends JpaRepository<FundingNeed, UUID> {

    Page<FundingNeed> findByIssuerId(UUID issuerId, Pageable pageable);

    /**
     * Claim a need for another matching attempt: only from SUBMITTED and only if an earlier run FAILED or was
     * REJECTED. One conditional update, so two clicks at once cannot start two runs.
     *
     * @return 1 if claimed, 0 otherwise
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE funding_needs SET status = 'MATCHING', version = version + 1 WHERE id = :id AND status = 'SUBMITTED'"
            + " AND EXISTS (SELECT 1 FROM match_runs WHERE need_id = :id AND status IN ('FAILED','REJECTED'))", nativeQuery = true)
    int claimForRematch(@Param("id") UUID id);

    /**
     * What a non-staff user may see: needs of companies they own (when acting as issuer) plus needs they were
     * matched to as an investor. matched must not be empty: pass a random id when there are none.
     */
    @Query("select n from FundingNeed n where (:asIssuer = true and n.issuerId in"
            + " (select i.id from Issuer i where i.ownerUserId = :user)) or n.id in :matched")
    Page<FundingNeed> findVisible(@Param("user") UUID user, @Param("asIssuer") boolean asIssuer,
                                  @Param("matched") java.util.Collection<UUID> matched, Pageable pageable);

    @Query("select n from FundingNeed n where n.issuerId = :issuerId and ((:asIssuer = true and n.issuerId in"
            + " (select i.id from Issuer i where i.ownerUserId = :user)) or n.id in :matched)")
    Page<FundingNeed> findVisibleOfIssuer(@Param("user") UUID user, @Param("asIssuer") boolean asIssuer,
                                          @Param("matched") java.util.Collection<UUID> matched, @Param("issuerId") UUID issuerId,
                                          Pageable pageable);
}
