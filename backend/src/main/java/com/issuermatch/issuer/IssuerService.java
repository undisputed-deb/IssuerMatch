package com.issuermatch.issuer;

import com.issuermatch.admin.IdentityChange;
import com.issuermatch.admin.VerificationService;
import com.issuermatch.audit.AuditService;
import com.issuermatch.common.ApiException;
import com.issuermatch.common.NotFoundException;
import com.issuermatch.security.CurrentUser;
import com.issuermatch.security.Role;
import java.time.Year;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Role gates live on the controller. Ownership lives here, next to the data:
 * staff (analyst/admin) see every issuer, an issuer only its own. An issuer asking for someone else's company
 * gets 404, the same answer as for an id that does not exist, so ids cannot be probed.
 */
@Service
@Transactional
class IssuerService {

    private final IssuerRepository repo;
    private final AuditService audit;
    private final VerificationService verification;

    IssuerService(IssuerRepository repo, AuditService audit, VerificationService verification) {
        this.repo = repo;
        this.audit = audit;
        this.verification = verification;
    }

    Issuer create(IssuerDtos.CreateRequest r) {
        CurrentUser me = CurrentUser.get();
        UUID owner = me.id();
        if (r.ownerUserId() != null && !r.ownerUserId().equals(me.id())) {
            if (!me.has(Role.ADMIN)) {
                throw new AccessDeniedException("Only an admin can create a company for another owner");
            }
            owner = r.ownerUserId();
        }
        // saveAndFlush so a duplicate name fails here (-> 409), not at commit time
        Issuer issuer = new Issuer(r.name(), r.sector(), r.region(), owner);
        issuer.describe(checked(r.profile()));
        Issuer saved = repo.saveAndFlush(issuer);
        audit.record("ISSUER_CREATED", "issuer:" + saved.getId(), Map.of("ownerUserId", owner.toString()));
        return saved;
    }

    @Transactional(readOnly = true)
    Issuer get(UUID id) {
        CurrentUser me = CurrentUser.get();
        return repo.findById(id).filter(i -> me.isStaff() || i.getOwnerUserId().equals(me.id()))
                .orElseThrow(() -> new NotFoundException("Issuer not found"));
    }

    @Transactional(readOnly = true)
    Page<Issuer> list(Pageable pageable) {
        CurrentUser me = CurrentUser.get();
        return me.isStaff() ? repo.findAll(pageable) : repo.findByOwnerUserId(me.id(), pageable);
    }

    Issuer update(UUID id, IssuerDtos.UpdateRequest r) {
        Issuer issuer = getWritable(id);
        String oldName = issuer.getName();
        String oldWebsite = issuer.getProfile().websiteUrl();
        issuer.update(r.name(), r.sector(), r.region());
        if (r.profile() != null) {
            issuer.describe(checked(r.profile()));
        }
        Issuer saved = repo.saveAndFlush(issuer);
        audit.record("ISSUER_UPDATED", "issuer:" + id, Map.of());
        if (verification.revokeAfterIdentityChange(VerificationService.Subject.ISSUER, id,
                IdentityChange.between(oldName, oldWebsite, saved.getName(), saved.getProfile().websiteUrl()))) {
            saved.badgeWithdrawn();
        }
        return saved;
    }

    void delete(UUID id) {
        repo.delete(getWritable(id));
        repo.flush();
        audit.record("ISSUER_DELETED", "issuer:" + id, Map.of());
    }

    private static CompanyProfile checked(CompanyProfile profile) {
        if (profile != null && profile.foundedYear() != null && profile.foundedYear() > Year.now().getValue()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "foundedYear cannot be in the future");
        }
        return profile;
    }

    /** Visible AND changeable: an admin, or the issuer-role owner. An analyst who can see it still cannot change it. */
    private Issuer getWritable(UUID id) {
        Issuer issuer = get(id);
        CurrentUser me = CurrentUser.get();
        boolean owner = me.has(Role.ISSUER) && issuer.getOwnerUserId().equals(me.id());
        if (!me.has(Role.ADMIN) && !owner) {
            throw new AccessDeniedException("Not allowed to change this issuer");
        }
        return issuer;
    }
}
