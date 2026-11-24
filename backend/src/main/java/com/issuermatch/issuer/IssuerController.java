package com.issuermatch.issuer;

import com.issuermatch.common.PageResponse;
import com.issuermatch.issuer.IssuerDtos.*;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/issuers")
class IssuerController {

    private final IssuerService service;

    IssuerController(IssuerService service) {
        this.service = service;
    }

    @PreAuthorize("hasAnyRole('ISSUER','ADMIN')")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    Response create(@Valid @RequestBody CreateRequest r) {
        return Response.from(service.create(r));
    }

    @PreAuthorize("hasAnyRole('ISSUER','ANALYST','ADMIN')")
    @GetMapping
    PageResponse<Response> list(Pageable pageable) {
        return PageResponse.of(service.list(pageable), Response::from);
    }

    @PreAuthorize("hasAnyRole('ISSUER','ANALYST','ADMIN')")
    @GetMapping("/{id}")
    Response get(@PathVariable UUID id) {
        return Response.from(service.get(id));
    }

    @PreAuthorize("hasAnyRole('ISSUER','ADMIN')")
    @PutMapping("/{id}")
    Response update(@PathVariable UUID id, @Valid @RequestBody UpdateRequest r) {
        return Response.from(service.update(id, r));
    }

    @PreAuthorize("hasAnyRole('ISSUER','ADMIN')")
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable UUID id) {
        service.delete(id);
    }
}
