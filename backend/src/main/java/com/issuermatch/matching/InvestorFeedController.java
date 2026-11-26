package com.issuermatch.matching;

import com.issuermatch.security.CurrentUser;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** An investor's Matched Deals Feed: only deals a human approved for the profile this user owns. */
@RestController
@RequestMapping("/investor/matches")
@PreAuthorize("hasRole('INVESTOR')")
class InvestorFeedController {

    private final InvestorFeed feed;

    InvestorFeedController(InvestorFeed feed) {
        this.feed = feed;
    }

    @GetMapping
    List<InvestorFeed.Deal> matches() {
        return feed.of(CurrentUser.get().id());
    }
}
