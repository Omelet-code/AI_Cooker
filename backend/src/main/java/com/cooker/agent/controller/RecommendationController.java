package com.cooker.agent.controller;

import com.cooker.agent.dto.RecommendationTurnView;
import com.cooker.agent.dto.SendRecommendationRequest;
import com.cooker.agent.service.RecommendationService;
import com.cooker.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/conversations")
public class RecommendationController {
    private final RecommendationService recommendations;

    public RecommendationController(RecommendationService recommendations) {
        this.recommendations = recommendations;
    }

    @PostMapping("/{id}/turns")
    public ResponseEntity<ApiResponse<RecommendationTurnView>> send(@PathVariable String id,
            @Valid @RequestBody SendRecommendationRequest request) {
        var submitted = recommendations.send(id, request);
        var response = ResponseEntity.status(submitted.status()).cacheControl(CacheControl.noStore());
        if (submitted.location() != null) response.header(HttpHeaders.LOCATION, submitted.location());
        return response.body(ApiResponse.ok(submitted.body()));
    }

    @GetMapping("/{id}/turns/{turnId}/recommendation")
    public ResponseEntity<ApiResponse<RecommendationTurnView>> get(@PathVariable String id,
            @PathVariable String turnId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.ok(recommendations.get(id, turnId)));
    }
}
