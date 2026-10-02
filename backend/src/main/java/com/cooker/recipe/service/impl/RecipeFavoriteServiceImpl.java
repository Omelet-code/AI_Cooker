package com.cooker.recipe.service.impl;

import com.cooker.identity.service.CurrentUserService;
import com.cooker.recipe.mapper.RecipeFavoriteMapper;
import com.cooker.recipe.mapper.RecipeReadMapper;
import com.cooker.recipe.service.RecipeFavoriteService;
import com.cooker.recipe.service.RecipeReadService;
import com.cooker.recipe.vo.FavoriteState;
import com.cooker.recipe.vo.PageView;
import com.cooker.recipe.vo.RecipeCard;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RecipeFavoriteServiceImpl implements RecipeFavoriteService {
    private final CurrentUserService currentUser;
    private final RecipeReadService read;
    private final RecipeReadMapper readMapper;
    private final RecipeFavoriteMapper favorites;

    public RecipeFavoriteServiceImpl(CurrentUserService currentUser, RecipeReadService read,
            RecipeReadMapper readMapper, RecipeFavoriteMapper favorites) {
        this.currentUser = currentUser; this.read = read; this.readMapper = readMapper; this.favorites = favorites;
    }

    @Override @Transactional
    public FavoriteState add(Long recipeId) {
        read.requireVisible(recipeId);
        Long userId = currentUser.requireUserId();
        // 数据库唯一约束与 ON CONFLICT 处理两次点击和并发，不修改首次收藏时间。
        favorites.insertIgnore(userId, recipeId);
        return new FavoriteState(recipeId.toString(), true);
    }

    @Override @Transactional
    public FavoriteState remove(Long recipeId) {
        read.requireVisible(recipeId);
        favorites.deleteFavorite(currentUser.requireUserId(), recipeId);
        return new FavoriteState(recipeId.toString(), false);
    }

    @Override @Transactional(readOnly = true)
    public PageView<RecipeCard> list(int page, int size) {
        RecipeReadServiceImpl.validatePage(page, size);
        Long userId = currentUser.requireUserId();
        long total = readMapper.countFavorites(userId);
        var items = readMapper.listFavorites(userId, size, RecipeReadServiceImpl.offset(page, size))
                .stream().map(RecipeReadServiceImpl::card).toList();
        return new PageView<>(items, page, size, total);
    }
}
