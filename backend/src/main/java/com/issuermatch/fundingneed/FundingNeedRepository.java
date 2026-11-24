package com.issuermatch.fundingneed;

import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FundingNeedRepository extends JpaRepository<FundingNeed, UUID> {

    Page<FundingNeed> findByIssuerId(UUID issuerId, Pageable pageable);
}
