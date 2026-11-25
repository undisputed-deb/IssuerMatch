package com.issuermatch.fundingneed;

import com.issuermatch.common.PageResponse;
import com.issuermatch.fundingneed.FundingNeedDtos.*;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/funding-needs")
@PreAuthorize("hasAnyRole('ISSUER','ANALYST','ADMIN')")
class FundingNeedController {

    private final FundingNeedService service;

    FundingNeedController(FundingNeedService service) {
        this.service = service;
    }

    @PreAuthorize("hasAnyRole('ISSUER','ADMIN')") // analysts review, they do not submit
    /** 202: accepted. Matching happens asynchronously after the event is processed. */
    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    Response create(@Valid @RequestBody CreateRequest r) {
        return Response.from(service.create(r));
    }

    @GetMapping
    PageResponse<Response> list(@RequestParam(required = false) UUID issuerId, Pageable pageable) {
        return PageResponse.of(service.list(issuerId, pageable), Response::from);
    }

    @GetMapping("/{id}")
    Response get(@PathVariable UUID id) {
        return Response.from(service.get(id));
    }

    /** Run matching again after a failed or rejected run. 202: it happens asynchronously. */
    @PostMapping("/{id}/match")
    @ResponseStatus(HttpStatus.ACCEPTED)
    Response match(@PathVariable UUID id) {
        return Response.from(service.rematch(id));
    }
}
