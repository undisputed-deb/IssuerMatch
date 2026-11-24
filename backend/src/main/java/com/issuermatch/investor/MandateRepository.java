package com.issuermatch.investor;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

interface MandateRepository extends JpaRepository<Mandate, UUID> {

    Page<Mandate> findByInvestorId(UUID investorId, Pageable pageable);

    /** Looking up by both ids stops /investors/A/mandates/{id of B's mandate}. */
    Optional<Mandate> findByIdAndInvestorId(UUID id, UUID investorId);
}
