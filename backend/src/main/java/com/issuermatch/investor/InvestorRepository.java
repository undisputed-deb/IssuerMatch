package com.issuermatch.investor;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface InvestorRepository extends JpaRepository<Investor, UUID> {

    java.util.Optional<Investor> findByOwnerUserId(UUID ownerUserId);
}
