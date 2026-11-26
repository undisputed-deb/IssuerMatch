package com.issuermatch.deal;

import com.issuermatch.security.CurrentUser;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Offers, passes and Q&A on matched deals. Roles gate here; DealService decides which deals the caller may touch. */
@RestController
class DealController {

    private final DealService service;

    DealController(DealService service) {
        this.service = service;
    }

    @GetMapping("/funding-needs/{needId}/offers")
    @PreAuthorize("hasAnyRole('ISSUER','ANALYST','ADMIN')")
    List<DealDtos.Offer> offers(@PathVariable UUID needId) {
        return service.offers(needId, CurrentUser.get());
    }

    @GetMapping("/investor/offers")
    @PreAuthorize("hasRole('INVESTOR')")
    List<DealDtos.MyOffer> myOffers() {
        return service.myOffers(CurrentUser.get().id());
    }

    @PostMapping("/investor/matches/{matchResultId}/pass")
    @PreAuthorize("hasRole('INVESTOR')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void pass(@PathVariable UUID matchResultId, @Valid @RequestBody DealDtos.PassRequest request) {
        service.pass(matchResultId, CurrentUser.get().id(), request.reason());
    }

    @GetMapping("/funding-needs/{needId}/questions")
    @PreAuthorize("hasAnyRole('ISSUER','INVESTOR','ANALYST','ADMIN')")
    List<DealDtos.Question> questions(@PathVariable UUID needId) {
        return service.questions(needId, CurrentUser.get());
    }

    @PostMapping("/funding-needs/{needId}/questions")
    @PreAuthorize("hasRole('INVESTOR')")
    @ResponseStatus(HttpStatus.CREATED)
    DealDtos.Question ask(@PathVariable UUID needId, @Valid @RequestBody DealDtos.AskRequest request) {
        return service.ask(needId, CurrentUser.get().id(), request.question());
    }

    @PostMapping("/funding-needs/{needId}/questions/{questionId}/answer")
    @PreAuthorize("hasRole('ISSUER')")
    DealDtos.Question answer(@PathVariable UUID needId, @PathVariable UUID questionId,
                             @Valid @RequestBody DealDtos.AnswerRequest request) {
        return service.answer(needId, questionId, CurrentUser.get().id(), request.answer());
    }
}
