package com.issuermatch.investor;

import com.issuermatch.common.NotFoundException;
import com.issuermatch.investor.InvestorDtos.*;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Returns DTOs (not entities) because mapping touches lazy collections and must happen inside the transaction. */
@Service
@Transactional
class InvestorService {

    private final InvestorRepository investors;
    private final MandateRepository mandates;

    InvestorService(InvestorRepository investors, MandateRepository mandates) {
        this.investors = investors;
        this.mandates = mandates;
    }

    InvestorResponse create(InvestorRequest r) {
        return InvestorResponse.from(investors.saveAndFlush(new Investor(r.name(), r.type(), r.region())));
    }

    @Transactional(readOnly = true)
    InvestorResponse get(UUID id) {
        return InvestorResponse.from(find(id));
    }

    @Transactional(readOnly = true)
    Page<InvestorResponse> list(Pageable pageable) {
        return investors.findAll(pageable).map(InvestorResponse::from);
    }

    InvestorResponse update(UUID id, InvestorRequest r) {
        Investor investor = find(id);
        investor.update(r.name(), r.type(), r.region());
        return InvestorResponse.from(investors.saveAndFlush(investor));
    }

    void delete(UUID id) {
        investors.delete(find(id));
        investors.flush();
    }

    // ---- mandates ----

    MandateResponse addMandate(UUID investorId, MandateRequest r) {
        Mandate m = new Mandate(find(investorId), r.sector(), r.region(), r.minTicket(), r.maxTicket(),
                r.riskLevel(), r.instrumentNames());
        return MandateResponse.from(mandates.saveAndFlush(m));
    }

    @Transactional(readOnly = true)
    Page<MandateResponse> listMandates(UUID investorId, Pageable pageable) {
        find(investorId);
        return mandates.findByInvestorId(investorId, pageable).map(MandateResponse::from);
    }

    @Transactional(readOnly = true)
    MandateResponse getMandate(UUID investorId, UUID mandateId) {
        return MandateResponse.from(findMandate(investorId, mandateId));
    }

    MandateResponse updateMandate(UUID investorId, UUID mandateId, MandateRequest r) {
        Mandate m = findMandate(investorId, mandateId);
        m.update(r.sector(), r.region(), r.minTicket(), r.maxTicket(), r.riskLevel(), r.instrumentNames());
        return MandateResponse.from(mandates.saveAndFlush(m));
    }

    void deleteMandate(UUID investorId, UUID mandateId) {
        mandates.delete(findMandate(investorId, mandateId));
        mandates.flush();
    }

    private Investor find(UUID id) {
        return investors.findById(id).orElseThrow(() -> new NotFoundException("Investor not found"));
    }

    private Mandate findMandate(UUID investorId, UUID mandateId) {
        return mandates.findByIdAndInvestorId(mandateId, investorId)
                .orElseThrow(() -> new NotFoundException("Mandate not found"));
    }
}
