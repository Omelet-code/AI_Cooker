package com.cooker.recipe.controller;

import com.cooker.common.ApiResponse;
import com.cooker.recipe.dto.RecipeIdParser;
import com.cooker.recipe.service.RecipeReadService;
import com.cooker.recipe.vo.PageView;
import com.cooker.recipe.vo.RecipeCard;
import com.cooker.recipe.vo.RecipeDetail;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/recipes")
public class RecipeReadController {
    private final RecipeReadService recipes;
    public RecipeReadController(RecipeReadService recipes) { this.recipes = recipes; }

    @GetMapping
    public ApiResponse<PageView<RecipeCard>> list(@RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size, @RequestParam(required = false) String keyword) {
        return ApiResponse.ok(recipes.list(page, size, keyword));
    }

    @GetMapping("/{id}")
    public ApiResponse<RecipeDetail> detail(@PathVariable String id) {
        return ApiResponse.ok(recipes.detail(RecipeIdParser.parse(id)));
    }
}
