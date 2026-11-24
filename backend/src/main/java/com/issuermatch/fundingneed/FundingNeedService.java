package com.issuermatch.fundingneed;

import com.issuermatch.common.NotFoundException;
import com.issuermatch.issuer.IssuerRepository;
import com.issuermatch.messaging.OutboxService;
import com.issuermatch.messaging.Topics;
import java.util.Map;
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
    private final OutboxService outbox;

    FundingNeedService(FundingNeedRepository needs, IssuerRepository issuers, OutboxService outbox) {
        this.needs = needs;
        this.issuers = issuers;
        this.outbox = outbox;
    }

    FundingNeed create(FundingNeedDtos.CreateRequest r) {
        if (!issuers.existsById(r.issuerId())) {
            throw new NotFoundException("Issuer not found");
        }
        FundingNeed need = needs.saveAndFlush(new FundingNeed(r.issuerId(), r.title(), r.description(), r.sector(),
                r.region(), r.amount(), r.instrument(), r.riskLevel()));
        // Same transaction as the insert (outbox pattern): the need and its event commit together or not at all.
        outbox.publish(Topics.FUNDING_NEED_SUBMITTED, need.getId().toString(), "FundingNeedSubmitted",
                Map.of("needId", need.getId().toString(), "issuerId", need.getIssuerId().toString()));
        return need;
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
