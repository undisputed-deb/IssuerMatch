package com.issuermatch.investor;

import com.issuermatch.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** An investor's own profile. The owner is always the token's user: there is no id in the URL or body to tamper with. */
@RestController
@RequestMapping("/investors/me")
@PreAuthorize("hasRole('INVESTOR')")
class MyInvestorProfileController {

    private final InvestorService service;

    MyInvestorProfileController(InvestorService service) {
        this.service = service;
    }

    @GetMapping
    InvestorDtos.InvestorResponse get() {
        return service.mine(CurrentUser.get().id());
    }

    @PutMapping
    InvestorDtos.InvestorResponse save(@Valid @RequestBody InvestorDtos.ProfileRequest request) {
        return service.saveMine(CurrentUser.get().id(), request);
    }
}
