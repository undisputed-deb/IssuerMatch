package com.issuermatch.deadletter;

import com.issuermatch.common.PageResponse;
import com.issuermatch.deadletter.DeadLetterService.Status;
import com.issuermatch.deadletter.DeadLetterService.View;
import com.issuermatch.security.CurrentUser;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/dead-letters")
@PreAuthorize("hasRole('ADMIN')") // payloads and failure details are operator data
class DeadLetterController {

    private final DeadLetterService service;

    DeadLetterController(DeadLetterService service) {
        this.service = service;
    }

    @GetMapping
    PageResponse<View> list(@RequestParam(required = false) String topic, @RequestParam(required = false) Status status,
                            Pageable pageable) {
        return PageResponse.of(service.list(topic, status, pageable), v -> v);
    }

    @GetMapping("/{id}")
    View get(@PathVariable UUID id) {
        return service.get(id);
    }

    /** 202: queued for redelivery. Allowed once per dead letter. */
    @PostMapping("/{id}/replay")
    @ResponseStatus(HttpStatus.ACCEPTED)
    View replay(@PathVariable UUID id) {
        return service.replay(id, CurrentUser.get().id());
    }
}
