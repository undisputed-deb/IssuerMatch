package com.issuermatch.issuer;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface IssuerRepository extends JpaRepository<Issuer, UUID> {}
