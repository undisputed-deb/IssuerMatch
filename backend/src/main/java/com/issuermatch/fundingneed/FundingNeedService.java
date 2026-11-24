package com.issuermatch.fundingneed;

import com.issuermatch.audit.AuditService;
import com.issuermatch.common.NotFoundException;
import com.issuermatch.issuer.Issuer;
import com.issuermatch.issuer.IssuerRepository;
import com.issuermatch.messaging.OutboxService;
import com.issuermatch.messaging.Topics;
import com.issuermatch.security.CurrentUser;
import com.issuermatch.security.Role;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Staff see every need. An issuer only the needs of companies it owns; anything else is a plain 404. */
@Service
@Transactional
class FundingNeedService {

    private final FundingNeedRepository needs;
    private final IssuerRepository issuers;
    private final OutboxService outbox;
    private final AuditService audit;

    FundingNeedService(FundingNeedRepository needs, IssuerRepository issuers, OutboxService outbox, AuditService audit) {
        this.needs = needs;
        this.issuers = issuers;
        this.outbox = outbox;
        this.audit = audit;
    }

    FundingNeed create(FundingNeedDtos.CreateRequest r) {
        CurrentUser me = CurrentUser.get();
        Issuer issuer = issuers.findById(r.issuerId()).orElseThrow(() -> new NotFoundException("Issuer not found"));
        // An admin may submit for any company. An issuer only for its own (otherwise: "not found").
        if (!me.has(Role.ADMIN) && !issuer.getOwnerUserId().equals(me.id())) {
            throw new NotFoundException("Issuer not found");
        }
        FundingNeed need = needs.saveAndFlush(new FundingNeed(r.issuerId(), r.title(), r.description(), r.sector(),
                r.region(), r.amount(), r.instrument(), r.riskLevel()));
        // Same transaction as the insert (outbox pattern): the need and its event commit together or not at all.
        // requestedBy travels with the event so the agent run is attributable to the person who asked for it.
        outbox.publish(Topics.FUNDING_NEED_SUBMITTED, need.getId().toString(), "FundingNeedSubmitted",
                Map.of("needId", need.getId().toString(), "issuerId", need.getIssuerId().toString(),
                        "requestedBy", me.id().toString()));
        // ids and numbers only. The title and description are free text and stay out of the audit trail.
        audit.record("FUNDING_NEED_SUBMITTED", "funding-need:" + need.getId(),
                Map.of("issuerId", need.getIssuerId().toString(), "amount", need.getAmount().toPlainString()));
        return need;
    }

    @Transactional(readOnly = true)
    FundingNeed get(UUID id) {
        CurrentUser me = CurrentUser.get();
        FundingNeed need = needs.findById(id).orElseThrow(() -> new NotFoundException("Funding need not found"));
        if (!me.isStaff()) {
            boolean owns = issuers.findById(need.getIssuerId()).map(i -> i.getOwnerUserId().equals(me.id())).orElse(false);
            if (!owns) {
                throw new NotFoundException("Funding need not found");
            }
        }
        return need;
    }

    @Transactional(readOnly = true)
    Page<FundingNeed> list(UUID issuerId, Pageable pageable) {
        CurrentUser me = CurrentUser.get();
        if (me.isStaff()) {
            return issuerId == null ? needs.findAll(pageable) : needs.findByIssuerId(issuerId, pageable);
        }
        return issuerId == null ? needs.findOwnedBy(me.id(), pageable) : needs.findOwnedBy(me.id(), issuerId, pageable);
    }
}
