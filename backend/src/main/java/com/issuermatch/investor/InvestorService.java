package com.issuermatch.investor;

import com.issuermatch.admin.IdentityChange;
import com.issuermatch.admin.VerificationService;
import com.issuermatch.audit.AuditService;
import com.issuermatch.common.NotFoundException;
import com.issuermatch.investor.InvestorDtos.*;
import com.issuermatch.security.CurrentUser;
import com.issuermatch.team.InvestorMembershipService;
import com.issuermatch.team.OrgRole;
import java.util.List;
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

    private final AuditService audit;
    private final InvestorMembershipService memberships;
    private final VerificationService verification;

    InvestorService(InvestorRepository investors, MandateRepository mandates, AuditService audit,
                    InvestorMembershipService memberships, VerificationService verification) {
        this.memberships = memberships;
        this.verification = verification;
        this.investors = investors;
        this.mandates = mandates;
        this.audit = audit;
    }

    // ---- the signed-in investor's own profile ----

    @Transactional(readOnly = true)
    InvestorResponse mine(UUID userId) {
        var me = memberships.require(userId, OrgRole.VIEWER);
        Investor investor = find(me.investorId());
        return InvestorResponse.from(investor, mandates.findAllByInvestorId(investor.getId())).as(me.orgRole());
    }

    /** A user without a firm creates one and runs it. A member changes the firm's profile only as managing director. */
    InvestorResponse saveMine(UUID userId, ProfileRequest r) {
        var membership = memberships.find(userId);
        Investor investor;
        if (membership.isPresent()) {
            InvestorMembershipService.check(membership.get(), OrgRole.MANAGING_DIRECTOR);
            investor = find(membership.get().investorId());
        } else {
            investor = new Investor(r.companyName(), r.investorType(), r.region());
            // owner_user_id is unique and kept: someone removed from an older firm they created starts a new one without it
            if (investors.findByOwnerUserId(userId).isEmpty()) {
                investor.ownedBy(userId);
            }
        }
        String oldName = investor.getName();
        String oldWebsite = investor.getWebsiteUrl();
        investor.completeProfile(r);
        Investor saved = investors.saveAndFlush(investor); // a taken name or a racing second profile becomes 409
        if (membership.isEmpty()) {
            memberships.addFounder(saved.getId(), userId, CurrentUser.email().orElse(null), r.contactName());
        } else {
            revokeBadgeIfRenamed(saved, oldName, oldWebsite);
        }

        // the profile form owns the mandate list: replace it as a whole
        mandates.deleteAll(mandates.findAllByInvestorId(saved.getId()));
        mandates.flush();
        List<Mandate> fresh = mandates.saveAllAndFlush(r.mandates().stream()
                .map(m -> new Mandate(saved, m.sector(), m.region(), m.minTicket(), m.maxTicket(), m.riskLevel(), m.instrumentNames(),
                        m.stageNames()))
                .toList());
        audit.record("INVESTOR_PROFILE_SAVED", "investor:" + saved.getId(), java.util.Map.of("mandates", fresh.size()));
        return InvestorResponse.from(saved, fresh).as(OrgRole.MANAGING_DIRECTOR);
    }

    InvestorResponse create(InvestorRequest r) {
        return InvestorResponse.from(investors.saveAndFlush(new Investor(r.name(), r.investorType(), r.region())));
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
        String oldName = investor.getName();
        investor.update(r.name(), r.investorType(), r.region());
        Investor saved = investors.saveAndFlush(investor);
        revokeBadgeIfRenamed(saved, oldName, saved.getWebsiteUrl());
        return InvestorResponse.from(saved);
    }

    void delete(UUID id) {
        investors.delete(find(id));
        investors.flush();
    }

    // ---- mandates ----

    MandateResponse addMandate(UUID investorId, MandateRequest r) {
        Mandate m = new Mandate(find(investorId), r.sector(), r.region(), r.minTicket(), r.maxTicket(),
                r.riskLevel(), r.instrumentNames(), r.stageNames());
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
        m.update(r.sector(), r.region(), r.minTicket(), r.maxTicket(), r.riskLevel(), r.instrumentNames(), r.stageNames());
        return MandateResponse.from(mandates.saveAndFlush(m));
    }

    void deleteMandate(UUID investorId, UUID mandateId) {
        mandates.delete(findMandate(investorId, mandateId));
        mandates.flush();
    }

    private void revokeBadgeIfRenamed(Investor saved, String oldName, String oldWebsite) {
        if (verification.revokeAfterIdentityChange(VerificationService.Subject.INVESTOR, saved.getId(),
                IdentityChange.between(oldName, oldWebsite, saved.getName(), saved.getWebsiteUrl()))) {
            saved.badgeWithdrawn();
        }
    }

    private Investor find(UUID id) {
        return investors.findById(id).orElseThrow(() -> new NotFoundException("Investor not found"));
    }

    private Mandate findMandate(UUID investorId, UUID mandateId) {
        return mandates.findByIdAndInvestorId(mandateId, investorId)
                .orElseThrow(() -> new NotFoundException("Mandate not found"));
    }
}
