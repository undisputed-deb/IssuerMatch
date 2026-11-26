package com.issuermatch.signup;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Public (no token). Rate limited per address and overall: see RateLimitFilters. */
@RestController
@RequestMapping("/auth")
class SignupController {

    /**
     * role is text checked against an exact allow list. ADMIN and ANALYST are given by an administrator only.
     * (Not an enum: Jackson would then accept a number such as 3 as "the fourth role".)
     */
    record Request(
            @NotBlank @Email @Size(max = 254) String email,
            @NotNull @Size(min = 10, max = 72) String password, // 72: bcrypt ignores anything longer
            @NotBlank @Size(max = 100) String fullName,
            @NotNull @Pattern(regexp = "ISSUER|INVESTOR", message = "must be ISSUER or INVESTOR") String role,
            @Size(max = 64) String inviteToken) { // signing up through a team invitation: the email must be the invited one

        @Override
        public String toString() {
            return "SignupRequest[role=" + role + "]";
        }
    }

    record Response(UUID userId, String role) {}

    private final SignupService service;

    SignupController(SignupService service) {
        this.service = service;
    }

    @PostMapping("/signup")
    @ResponseStatus(HttpStatus.CREATED)
    Response signup(@Valid @RequestBody Request request) {
        return service.signup(request);
    }
}
