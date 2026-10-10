package com.cooker.recipe.service.impl;

import com.cooker.common.ApiException;
import com.cooker.identity.service.UserDiningProfileService;
import com.cooker.recipe.dto.*;
import com.cooker.recipe.mapper.MealFactsMapper;
import com.cooker.recipe.service.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class RecipeMealFactsServiceImpl implements RecipeMealFactsService {
    private final UserDiningProfileService users;
    private final RecipeSearchEligibilityService eligibility;
    private final MealFactsMapper mapper;
    private final MealRoleRules roles;
    private final ObjectMapper fingerprintJson = new ObjectMapper();

    public RecipeMealFactsServiceImpl(UserDiningProfileService users, RecipeSearchEligibilityService eligibility,
            MealFactsMapper mapper, MealRoleRules roles) {
        this.users = users; this.eligibility = eligibility; this.mapper = mapper; this.roles = roles;
    }

    @Override public void verifyViewer(long viewerUserId) {
        if (viewerUserId <= 0) throw invalid();
        // 只复用身份公开接口核验账号，不读取档案值推断目标人数。
        users.readForUser(viewerUserId);
    }

    @Override public List<String> categoriesFor(MealRole role) { return roles.categoriesFor(role); }

    @Override @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.REPEATABLE_READ)
    public List<MealRecipeFacts> readFacts(long viewerUserId, List<Long> recipeIds) {
        verifyViewer(viewerUserId);
        validateIds(recipeIds, 1000);
        return read(recipeIds, viewerUserId);
    }

    @Override @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.REPEATABLE_READ)
    public List<MealRecipeFacts> eligiblePool(RecipeEligibilityFilter filter, int maximumPoolSize) {
        validateFilter(filter);
        if (maximumPoolSize < 1 || maximumPoolSize > 1000) throw invalid();
        var ids = eligibility.eligibleRecipes(filter).stream().map(RecipeSearchEligibility::recipeId).distinct().sorted().toList();
        if (ids.size() > maximumPoolSize)
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "RESOURCE_LIMIT", "合格候选池超过上限，请缩小条件");
        return ids.isEmpty() ? List.of() : read(ids, filter.viewerUserId());
    }

    @Override @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.REPEATABLE_READ)
    public List<MealRecipeFacts> recheck(RecipeEligibilityFilter filter, List<MealRecipeFacts> previous) {
        validateFilter(filter);
        if (previous == null || previous.size() > 40) throw invalid();
        if (previous.isEmpty()) return List.of();
        var ids = previous.stream().map(MealRecipeFacts::recipeId).toList();
        validateIds(ids, 40);
        Set<Long> eligible = eligibility.eligibleRecipes(filter).stream().map(RecipeSearchEligibility::recipeId)
                .collect(Collectors.toSet());
        // 不返回删除、撤权或事实改变后的旧快照；短缺由检索层如实报告。
        var visible = mapper.recipes(filter.viewerUserId(), ids).stream().map(row -> row.recipeId).collect(Collectors.toSet());
        var retained = ids.stream().filter(id -> eligible.contains(id) && visible.contains(id)).toList();
        if (retained.isEmpty()) return List.of();
        var current = read(retained, filter.viewerUserId()).stream()
                .collect(Collectors.toMap(MealRecipeFacts::recipeId, Function.identity()));
        return previous.stream().filter(old -> current.containsKey(old.recipeId())
                && old.factFingerprint().equals(current.get(old.recipeId()).factFingerprint()))
                .map(old -> current.get(old.recipeId())).toList();
    }

    @Override @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.REPEATABLE_READ)
    public MealMaterials materials(long viewerUserId, List<Long> recipeIds, int targetDiningCount) {
        verifyViewer(viewerUserId);
        validateIds(recipeIds, 20);
        if (targetDiningCount <= 0) throw invalid();
        return MealMaterialCalculator.calculate(read(recipeIds, viewerUserId), targetDiningCount);
    }

    private List<MealRecipeFacts> read(List<Long> ids, long viewer) {
        var rows = mapper.recipes(viewer, ids);
        if (rows.size() != ids.size())
            throw new ApiException(HttpStatus.NOT_FOUND, "RECIPE_NOT_FOUND", "菜谱不存在或不可访问");
        // 固定四条批量SQL，人数与菜数不会导致逐材料或步骤N+1。
        var ingredients = mapper.ingredients(viewer, ids).stream().collect(Collectors.groupingBy(row -> row.recipeId));
        var steps = mapper.steps(viewer, ids).stream().collect(Collectors.groupingBy(row -> row.recipeId));
        var tools = mapper.tools(viewer, ids).stream().collect(Collectors.groupingBy(row -> row.recipeId));
        var byId = new HashMap<Long, MealRecipeFacts>();
        for (var row : rows) {
            var evidence = roles.classify(row.sourceType, row.sourceKey, row.categoryCode, row.sourceContentHash);
            var items = ingredients.getOrDefault(row.recipeId, List.of()).stream().map(item -> new MealRecipeFacts.Ingredient(
                    item.recipeIngredientId, item.ingredientId, item.normalizationStatus, item.rawName, item.rawLine,
                    item.amountMin, item.amountMax, item.unitCode, item.unitLabel, item.optional, item.sortOrder, row.recipeId)).toList();
            var recipeSteps = steps.getOrDefault(row.recipeId, List.of()).stream()
                    .map(step -> new MealRecipeFacts.Step(step.stepId, step.stepNo, step.content)).toList();
            var recipeTools = tools.getOrDefault(row.recipeId, List.of()).stream()
                    .map(tool -> new MealRecipeFacts.Tool(tool.toolId, tool.name, tool.optional)).toList();
            var unsigned = new MealRecipeFacts(row.recipeId, row.name, row.categoryCode, evidence, row.sourceDocumentId,
                    row.sourceContentHash, row.servings, row.estimatedMinutes, items, recipeSteps, recipeTools, row.additionalContent, null);
            var signed = new MealRecipeFacts(unsigned.recipeId(), unsigned.name(), unsigned.categoryCode(), evidence,
                    unsigned.sourceDocumentId(), unsigned.sourceContentHash(), unsigned.servings(), unsigned.estimatedMinutes(),
                    items, recipeSteps, recipeTools, unsigned.additionalContent(), fingerprint(unsigned));
            byId.put(row.recipeId, signed);
        }
        return ids.stream().map(byId::get).toList();
    }

    private String fingerprint(MealRecipeFacts facts) {
        try {
            // DTO字段顺序固定、SQL子项稳定排序；包括全部结构化事实与角色版本，避免只校验Markdown。
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                    fingerprintJson.writeValueAsString(facts).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | JsonProcessingException ex) {
            throw new IllegalStateException("菜谱事实指纹生成失败", ex);
        }
    }

    private void validateFilter(RecipeEligibilityFilter filter) {
        if (filter == null || filter.viewerUserId() == null || filter.maxCookingMinutes() != null && filter.maxCookingMinutes() < 0)
            throw invalid();
        verifyViewer(filter.viewerUserId());
    }
    private static void validateIds(List<Long> ids, int maximum) {
        if (ids == null || ids.isEmpty() || ids.size() > maximum
                || ids.stream().anyMatch(id -> id == null || id <= 0) || new HashSet<>(ids).size() != ids.size()) throw invalid();
    }
    private static ApiException invalid() {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "内部事实请求参数不合法");
    }
}
