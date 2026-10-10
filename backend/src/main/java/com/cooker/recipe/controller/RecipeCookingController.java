package com.cooker.recipe.controller;

import com.cooker.common.ApiResponse;
import com.cooker.recipe.dto.CreateCookingRecordRequest;
import com.cooker.recipe.service.RecipeCookingService;
import com.cooker.recipe.vo.CookedRecipeCard;
import com.cooker.recipe.vo.CookingRecord;
import com.cooker.recipe.vo.PageView;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RecipeCookingController {
    private final RecipeCookingService cooking;
    public RecipeCookingController(RecipeCookingService cooking) { this.cooking = cooking; }

    @PostMapping("/cooking-records")
    public ResponseEntity<ApiResponse<CookingRecord>> complete(@RequestBody CreateCookingRecordRequest request) {
        var result = cooking.complete(request);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(ApiResponse.ok(result.record()));
    }

    @GetMapping("/users/me/cooked-recipes")
    public ApiResponse<PageView<CookedRecipeCard>> history(@RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(cooking.history(page, size));
    }
}
