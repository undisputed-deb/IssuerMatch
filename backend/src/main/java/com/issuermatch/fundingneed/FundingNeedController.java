package com.issuermatch.fundingneed;

import com.issuermatch.common.PageResponse;
import com.issuermatch.fundingneed.FundingNeedDtos.*;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/funding-needs")
class FundingNeedController {

    private final FundingNeedService service;

    FundingNeedController(FundingNeedService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
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
}
