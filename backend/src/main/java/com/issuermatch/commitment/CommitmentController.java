package com.issuermatch.commitment;

import com.issuermatch.commitment.CommitmentDtos.CreateRequest;
import com.issuermatch.commitment.CommitmentDtos.Response;
import com.issuermatch.commitment.IdempotencyService.Saved;
import com.issuermatch.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/commitments")
@PreAuthorize("hasAnyRole('ANALYST','ADMIN')") // staff record and review commitments; investors may only create (below)
class CommitmentController {

    private final CommitmentService service;
    private final IssuerDecisionService issuerDecisions;

    CommitmentController(CommitmentService service, IssuerDecisionService issuerDecisions) {
        this.service = service;
        this.issuerDecisions = issuerDecisions;
    }

    /**
     * Requires an Idempotency-Key header. Retrying with the same key and body returns the first response.
     * 201 ACCEPTED, 202 PENDING_REVIEW (flagged), 422 REJECTED (blocked, with the reasons). All three are stored decisions.
     */
    @PostMapping
    @PreAuthorize("hasAnyRole('INVESTOR','ANALYST','ADMIN')") // an investor submits offers for its own profile only
    ResponseEntity<String> create(@RequestHeader(value = "Idempotency-Key", required = false) String key,
                                  @Valid @RequestBody CreateRequest request) {
        CurrentUser me = CurrentUser.get();
        Saved saved = service.create(me.id(), !me.isStaff(), key, request);
        ResponseEntity.BodyBuilder response = ResponseEntity.status(saved.status()).contentType(MediaType.APPLICATION_JSON);
        if (saved.replayed()) {
            response.header("Idempotent-Replayed", "true");
        }
        return response.body(saved.body());
    }

    /** The risk-review queue: ?status=PENDING_REVIEW. */
    @GetMapping
    com.issuermatch.common.PageResponse<CommitmentDtos.Summary> list(
            @org.springframework.web.bind.annotation.RequestParam(required = false) CommitmentDtos.Status status,
            org.springframework.data.domain.Pageable pageable) {
        return com.issuermatch.common.PageResponse.of(service.list(status, pageable), x -> x);
    }

    /** One commitment with the reason every rule gave. */
    @GetMapping("/{id}")
    Response get(@PathVariable UUID id) {
        return service.get(id);
    }

    /** A flagged commitment needs a second person. Approve: it becomes ACCEPTED. */
    @PostMapping("/{id}/approve")
    Response approve(@PathVariable UUID id) {
        return service.review(id, true, CurrentUser.get().id());
    }

    /** Reject: the reserved capacity goes back to the raise. */
    @PostMapping("/{id}/reject")
    Response reject(@PathVariable UUID id) {
        return service.review(id, false, CurrentUser.get().id());
    }

    /** The company raising the money takes an accepted offer. */
    @PostMapping("/{id}/issuer-accept")
    @PreAuthorize("hasRole('ISSUER')")
    IssuerDecisionService.Decided issuerAccept(@PathVariable UUID id) {
        return issuerDecisions.decide(id, true, CurrentUser.get().id());
    }

    /** The company turns an accepted offer down: its capacity is open again. */
    @PostMapping("/{id}/issuer-decline")
    @PreAuthorize("hasRole('ISSUER')")
    IssuerDecisionService.Decided issuerDecline(@PathVariable UUID id) {
        return issuerDecisions.decide(id, false, CurrentUser.get().id());
    }
}
