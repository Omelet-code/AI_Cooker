package com.cooker.recipe.service.impl;

import com.cooker.recipe.dto.MealRole;
import com.cooker.recipe.dto.MealRecipeFacts.RoleEvidence;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

@Component
public class MealRoleRules {
    private final Properties categories = new Properties();
    private final Map<String, String[]> exceptions = new HashMap<>();
    private final String version;

    public MealRoleRules() {
        try (var reader = new InputStreamReader(new ClassPathResource("recipe/meal-role-v1.properties")
                .getInputStream(), StandardCharsets.UTF_8);
             var lines = new BufferedReader(new InputStreamReader(new ClassPathResource(
                     "recipe/meal-role-v1-exceptions.tsv").getInputStream(), StandardCharsets.UTF_8))) {
            categories.load(reader);
            version = categories.getProperty("version");
            for (String line : lines.lines().toList()) {
                if (line.isBlank()) continue;
                String[] fields = line.split("\t", 5);
                if (fields.length != 5) throw new IllegalStateException("角色例外资源格式错误");
                exceptions.put(fields[0] + "\t" + fields[1], fields);
            }
        } catch (IOException ex) { throw new IllegalStateException("角色规则资源读取失败", ex); }
    }

    public RoleEvidence classify(String sourceType, String sourceKey, String category, String hash) {
        // 系统例外绑定路径、分类和原文哈希，绝不按菜名套用到用户菜谱。
        String[] exception = "SYSTEM_HOWTOCOOK".equals(sourceType)
                ? exceptions.get(sourceKey + "\t" + category) : null;
        if (exception != null) {
            if (!exception[2].equals(hash)) return new RoleEvidence(MealRole.UNKNOWN,
                    "系统例外原文哈希变化，需要重新审计", version);
            return new RoleEvidence(MealRole.valueOf(exception[3]), "系统原文例外：" + exception[4], version);
        }
        String mapped = category == null ? null : categories.getProperty(category);
        if (mapped == null || "version".equals(category))
            return new RoleEvidence(MealRole.UNKNOWN, "缺少受支持的可信分类", version);
        return new RoleEvidence(MealRole.valueOf(mapped),
                ("USER_CUSTOM".equals(sourceType) ? "私有同模板类别依据，未逐菜人工审定：" : "已审计分类规则：") + category, version);
    }

    public List<String> categoriesFor(MealRole role) {
        return categories.stringPropertyNames().stream().filter(k -> role.name().equals(categories.getProperty(k)))
                .sorted().toList();
    }
}
