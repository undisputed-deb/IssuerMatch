package com.issuermatch.issuer;

import com.issuermatch.common.Region;
import com.issuermatch.common.Sector;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;

final class IssuerDtos {
    private IssuerDtos() {}

    // ownerUserId comes from the request until Phase 4, when it is taken from the JWT instead.
    record CreateRequest(
            @NotBlank @Size(max = 200) String name,
            @NotNull Sector sector,
            @NotNull Region region,
            @NotNull UUID ownerUserId) {}

    record UpdateRequest(
            @NotBlank @Size(max = 200) String name,
            @NotNull Sector sector,
            @NotNull Region region) {}

    record Response(UUID id, String name, Sector sector, Region region, UUID ownerUserId, Instant createdAt) {
        static Response from(Issuer i) {
            return new Response(i.getId(), i.getName(), i.getSector(), i.getRegion(), i.getOwnerUserId(), i.getCreatedAt());
        }
    }
}
