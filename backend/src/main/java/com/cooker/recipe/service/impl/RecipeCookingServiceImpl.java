package com.cooker.recipe.service.impl;

import com.cooker.common.ApiException;
import com.cooker.identity.service.CurrentUserService;
import com.cooker.recipe.dto.CookedRecipeRow;
import com.cooker.recipe.dto.CreateCookingRecordRequest;
import com.cooker.recipe.dto.RecipeIdParser;
import com.cooker.recipe.mapper.RecipeCookingMapper;
import com.cooker.recipe.service.RecipeCookingService;
import com.cooker.recipe.service.RecipeReadService;
import com.cooker.recipe.vo.CookedRecipeCard;
import com.cooker.recipe.vo.CookingRecord;
import com.cooker.recipe.vo.CookingRecordResult;
import com.cooker.recipe.vo.PageView;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RecipeCookingServiceImpl implements RecipeCookingService {
    private final CurrentUserService currentUser;
    private final RecipeReadService read;
    private final RecipeCookingMapper mapper;

    public RecipeCookingServiceImpl(CurrentUserService currentUser, RecipeReadService read,
            RecipeCookingMapper mapper) {
        this.currentUser = currentUser; this.read = read; this.mapper = mapper;
    }

    @Override @Transactional
    public CookingRecordResult complete(CreateCookingRecordRequest request) {
        if (request == null) throw invalid("请求参数不合法");
        Long recipeId = RecipeIdParser.parse(request.recipeId());
        UUID key = parseKey(request.idempotencyKey());
        Integer rating = null;
        if (request.rating() != null && !request.rating().isNull()) {
            if (!request.rating().isInt()) throw invalid("评分必须是整数");
            rating = request.rating().intValue();
        }
        if (rating != null && (rating < 1 || rating > 5)) throw invalid("评分必须为 1～5");
        Long userId = currentUser.requireUserId();
        // 先统一检查可见性，避免不同用户以同一幂等键探测他人的私有菜谱。
        read.requireVisible(recipeId);
        // 唯一约束承担并发仲裁：失败的冲突写入不抛异常，后续 SELECT 可读取赢家提交的事件。
        boolean created = mapper.insertIgnore(userId, recipeId, key.toString(), rating) == 1;
        var row = mapper.findByKey(userId, key.toString());
        if (row == null) throw new IllegalStateException("幂等事件未找到");
        if (!row.recipeId.equals(recipeId) || !Objects.equals(row.rating, rating))
            throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT", "幂等键已用于另一项完成记录");
        return new CookingRecordResult(new CookingRecord(row.id.toString(), row.recipeId.toString(),
                row.cookedAt, row.rating), created);
    }

    @Override @Transactional(readOnly = true)
    public PageView<CookedRecipeCard> history(int page, int size) {
        RecipeReadServiceImpl.validatePage(page, size);
        Long userId = currentUser.requireUserId();
        long total = mapper.countCookedRecipes(userId);
        var items = mapper.listCookedRecipes(userId, size, RecipeReadServiceImpl.offset(page, size))
                .stream().map(RecipeCookingServiceImpl::card).toList();
        return new PageView<>(items, page, size, total);
    }

    private static CookedRecipeCard card(CookedRecipeRow row) {
        String code = row.cookCount >= 6 ? "FREQUENT" : row.cookCount >= 3 ? "FAMILIAR" : "FIRST_TRY";
        String label = row.cookCount >= 6 ? "常做" : row.cookCount >= 3 ? "熟悉" : "初尝";
        return new CookedRecipeCard(RecipeReadServiceImpl.card(row), row.cookCount, row.lastCookedAt,
                code, label, row.latestRating);
    }

    private static UUID parseKey(String raw) {
        if (raw == null) throw invalid("幂等键必须是 UUID");
        try {
            UUID key = UUID.fromString(raw);
            if (!key.toString().equals(raw)) throw invalid("幂等键必须是规范 UUID");
            return key;
        } catch (IllegalArgumentException e) { throw invalid("幂等键必须是 UUID"); }
    }

    private static ApiException invalid(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message);
    }
}
