package com.issuermatch.admin;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Only an admin grants or removes the "Verified" badge. Body: {"verified": true}. Repeating a call changes nothing. */
@RestController
@RequestMapping("/admin")
@PreAuthorize("hasRole('ADMIN')")
class VerificationController {

    record Request(@NotNull Boolean verified) {}

    record Response(UUID id, boolean verified) {}

    private final VerificationService service;

    VerificationController(VerificationService service) {
        this.service = service;
    }

    @PatchMapping("/issuers/{id}/verify")
    Response issuer(@PathVariable UUID id, @Valid @RequestBody Request request) {
        service.set(VerificationService.Subject.ISSUER, id, request.verified());
        return new Response(id, request.verified());
    }

    @PatchMapping("/investors/{id}/verify")
    Response investor(@PathVariable UUID id, @Valid @RequestBody Request request) {
        service.set(VerificationService.Subject.INVESTOR, id, request.verified());
        return new Response(id, request.verified());
    }
}
