package com.issuermatch.fundingneed;

import com.issuermatch.common.NotFoundException;
import com.issuermatch.issuer.IssuerRepository;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
class FundingNeedService {

    private final FundingNeedRepository needs;
    private final IssuerRepository issuers;

    FundingNeedService(FundingNeedRepository needs, IssuerRepository issuers) {
        this.needs = needs;
        this.issuers = issuers;
    }

    FundingNeed create(FundingNeedDtos.CreateRequest r) {
        if (!issuers.existsById(r.issuerId())) {
            throw new NotFoundException("Issuer not found");
        }
        return needs.saveAndFlush(new FundingNeed(r.issuerId(), r.title(), r.description(), r.sector(),
                r.region(), r.amount(), r.instrument(), r.riskLevel()));
    }

    @Transactional(readOnly = true)
    FundingNeed get(UUID id) {
        return needs.findById(id).orElseThrow(() -> new NotFoundException("Funding need not found"));
    }

    @Transactional(readOnly = true)
    Page<FundingNeed> list(UUID issuerId, Pageable pageable) {
        return issuerId == null ? needs.findAll(pageable) : needs.findByIssuerId(issuerId, pageable);
    }
}
