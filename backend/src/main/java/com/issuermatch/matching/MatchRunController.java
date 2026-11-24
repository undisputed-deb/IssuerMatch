package com.issuermatch.matching;

import com.issuermatch.common.PageResponse;
import com.issuermatch.matching.MatchRunDtos.Detail;
import com.issuermatch.matching.MatchRunDtos.Result;
import com.issuermatch.matching.MatchRunDtos.Summary;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/match-runs")
class MatchRunController {

    private final MatchRunQueries queries;
    private final MatchReviewService review;

    MatchRunController(MatchRunQueries queries, MatchReviewService review) {
        this.queries = queries;
        this.review = review;
    }

    @GetMapping
    PageResponse<Summary> list(@RequestParam(required = false) UUID needId, Pageable pageable) {
        return PageResponse.of(queries.list(needId, pageable), s -> s);
    }

    @GetMapping("/{id}")
    Detail get(@PathVariable UUID id) {
        return queries.detail(id);
    }

    @GetMapping("/{id}/results")
    List<Result> results(@PathVariable UUID id) {
        return queries.results(id);
    }

    @PostMapping("/{id}/approve")
    Summary approve(@PathVariable UUID id) {
        review.review(id, true);
        return queries.summary(id);
    }

    @PostMapping("/{id}/reject")
    Summary reject(@PathVariable UUID id) {
        review.review(id, false);
        return queries.summary(id);
    }
}
