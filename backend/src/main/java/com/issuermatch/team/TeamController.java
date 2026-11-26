package com.issuermatch.team;

import com.issuermatch.security.CurrentUser;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The caller's own firm. The firm comes from the caller's membership, so there is no firm id to tamper with. */
@RestController
@RequestMapping("/investors/me")
@PreAuthorize("hasRole('INVESTOR')")
class TeamController {

    private final TeamService service;

    TeamController(TeamService service) {
        this.service = service;
    }

    @GetMapping("/team")
    TeamDtos.Team team() {
        return service.team(CurrentUser.get().id(), CurrentUser.email().orElse(null));
    }

    @PostMapping("/invites")
    @ResponseStatus(HttpStatus.CREATED)
    TeamDtos.CreatedInvite invite(@Valid @RequestBody TeamDtos.InviteRequest request) {
        return service.invite(CurrentUser.get().id(), request);
    }

    @DeleteMapping("/invites/{inviteId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void revoke(@PathVariable UUID inviteId) {
        service.revokeInvite(CurrentUser.get().id(), inviteId);
    }

    @PatchMapping("/members/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void changeRole(@PathVariable UUID userId, @Valid @RequestBody TeamDtos.RoleRequest request) {
        service.changeRole(CurrentUser.get().id(), userId, request.orgRole());
    }

    @DeleteMapping("/members/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void remove(@PathVariable UUID userId) {
        service.remove(CurrentUser.get().id(), userId);
    }
}
