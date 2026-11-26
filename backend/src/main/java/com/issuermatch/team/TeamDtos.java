package com.issuermatch.team;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

final class TeamDtos {
    private TeamDtos() {}

    record Member(UUID userId, String email, String displayName, OrgRole orgRole, Instant joinedAt, boolean you) {}

    record Invite(UUID id, String email, OrgRole orgRole, Instant createdAt, Instant expiresAt) {}

    /** pendingInvites is empty for anyone but a managing director. */
    record Team(UUID investorId, OrgRole myOrgRole, List<Member> members, List<Invite> pendingInvites) {}

    record InviteRequest(@NotBlank @Email @Size(max = 254) String email, @NotNull OrgRole orgRole) {}

    /** invitePath holds the token. It is returned this once and cannot be recovered. */
    record CreatedInvite(UUID id, String email, OrgRole orgRole, Instant createdAt, Instant expiresAt, String invitePath) {}

    record RoleRequest(@NotNull OrgRole orgRole) {}

    /** What a link holder learns before joining. */
    record Preview(String firmName, OrgRole orgRole, String maskedEmail, Instant expiresAt, String status) {}

    record Joined(UUID investorId, String firmName, OrgRole orgRole) {}
}
