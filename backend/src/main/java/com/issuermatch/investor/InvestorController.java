package com.issuermatch.investor;

import com.issuermatch.common.PageResponse;
import com.issuermatch.investor.InvestorDtos.*;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/investors")
@PreAuthorize("hasAnyRole('ANALYST','ADMIN')") // investor data is confidential to staff
class InvestorController {

    private final InvestorService service;

    InvestorController(InvestorService service) {
        this.service = service;
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    InvestorResponse create(@Valid @RequestBody InvestorRequest r) {
        return service.create(r);
    }

    @GetMapping
    PageResponse<InvestorResponse> list(Pageable pageable) {
        return PageResponse.of(service.list(pageable), x -> x);
    }

    @GetMapping("/{id}")
    InvestorResponse get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping("/{id}")
    InvestorResponse update(@PathVariable UUID id, @Valid @RequestBody InvestorRequest r) {
        return service.update(id, r);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable UUID id) {
        service.delete(id);
    }

    // ---- mandates ----

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/{id}/mandates")
    @ResponseStatus(HttpStatus.CREATED)
    MandateResponse addMandate(@PathVariable UUID id, @Valid @RequestBody MandateRequest r) {
        return service.addMandate(id, r);
    }

    @GetMapping("/{id}/mandates")
    PageResponse<MandateResponse> listMandates(@PathVariable UUID id, Pageable pageable) {
        return PageResponse.of(service.listMandates(id, pageable), x -> x);
    }

    @GetMapping("/{id}/mandates/{mid}")
    MandateResponse getMandate(@PathVariable UUID id, @PathVariable UUID mid) {
        return service.getMandate(id, mid);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping("/{id}/mandates/{mid}")
    MandateResponse updateMandate(@PathVariable UUID id, @PathVariable UUID mid, @Valid @RequestBody MandateRequest r) {
        return service.updateMandate(id, mid, r);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/{id}/mandates/{mid}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deleteMandate(@PathVariable UUID id, @PathVariable UUID mid) {
        service.deleteMandate(id, mid);
    }
}
