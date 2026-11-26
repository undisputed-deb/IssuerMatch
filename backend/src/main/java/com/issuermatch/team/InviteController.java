package com.issuermatch.team;

import com.issuermatch.security.CurrentUser;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The preview is public (SecurityConfig) and rate limited. Joining needs a signed-in investor with the invited address. */
@RestController
@RequestMapping("/investor-invites/{token}")
class InviteController {

    private final InviteService service;

    InviteController(InviteService service) {
        this.service = service;
    }

    @GetMapping("/preview")
    TeamDtos.Preview preview(@PathVariable String token) {
        return service.preview(token);
    }

    @PostMapping("/accept")
    @PreAuthorize("hasRole('INVESTOR')")
    TeamDtos.Joined accept(@PathVariable String token) {
        return service.accept(token, CurrentUser.get().id(), CurrentUser.email().orElse(null), CurrentUser.fullName().orElse(null));
    }
}
