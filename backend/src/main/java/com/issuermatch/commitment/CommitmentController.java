package com.issuermatch.commitment;

import com.issuermatch.commitment.CommitmentDtos.CreateRequest;
import com.issuermatch.commitment.IdempotencyService.Saved;
import com.issuermatch.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/commitments")
@PreAuthorize("hasAnyRole('ANALYST','ADMIN')") // analysts record commitments on behalf of investors
class CommitmentController {

    private final CommitmentService service;

    CommitmentController(CommitmentService service) {
        this.service = service;
    }

    /** Requires an Idempotency-Key header. Retrying with the same key and body returns the first response. */
    @PostMapping
    ResponseEntity<String> create(@RequestHeader(value = "Idempotency-Key", required = false) String key,
                                  @Valid @RequestBody CreateRequest request) {
        Saved saved = service.create(CurrentUser.get().id(), key, request);
        ResponseEntity.BodyBuilder response = ResponseEntity.status(saved.status()).contentType(MediaType.APPLICATION_JSON);
        if (saved.replayed()) {
            response.header("Idempotent-Replayed", "true");
        }
        return response.body(saved.body());
    }
}
