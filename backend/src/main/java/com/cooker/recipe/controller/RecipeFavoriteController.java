package com.cooker.recipe.controller;

import com.cooker.common.ApiResponse;
import com.cooker.recipe.dto.RecipeIdParser;
import com.cooker.recipe.service.RecipeFavoriteService;
import com.cooker.recipe.vo.FavoriteState;
import com.cooker.recipe.vo.PageView;
import com.cooker.recipe.vo.RecipeCard;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RecipeFavoriteController {
    private final RecipeFavoriteService favorites;
    public RecipeFavoriteController(RecipeFavoriteService favorites) { this.favorites = favorites; }

    @PostMapping("/recipes/{id}/favorites")
    public ApiResponse<FavoriteState> add(@PathVariable String id) {
        return ApiResponse.ok(favorites.add(RecipeIdParser.parse(id)));
    }

    @DeleteMapping("/recipes/{id}/favorites")
    public ApiResponse<FavoriteState> remove(@PathVariable String id) {
        return ApiResponse.ok(favorites.remove(RecipeIdParser.parse(id)));
    }

    @GetMapping("/favorites/recipes")
    public ApiResponse<PageView<RecipeCard>> list(@RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(favorites.list(page, size));
    }
}
