package com.cooker.recipe.mapper;

import com.cooker.recipe.entity.RecipeRepairBatchEntity;
import org.apache.ibatis.annotations.*;
import java.math.BigDecimal;
import java.util.List;

/** 治理专属显式SQL；不跨模块访问Mapper，也不接收动态SQL片段。 */
@Mapper
public interface RecipeRepairMapper {
    @Select("SELECT current_database()") String database();
    // 只返回不可逆摘要，不读取凭证或把用户历史原文放入治理包/审计。
    // 全保护范围摘要变化会保守拒绝回滚，包括与本批无关的新记录。
    @Select("SELECT jsonb_build_object('recipe_source_document',(SELECT md5(coalesce(string_agg(md5(to_jsonb(t)::text),'' ORDER BY to_jsonb(t)::text),'')) FROM recipe_source_document t),'recipe_step',(SELECT md5(coalesce(string_agg(md5(to_jsonb(t)::text),'' ORDER BY to_jsonb(t)::text),'')) FROM recipe_step t),'recipe_tool',(SELECT md5(coalesce(string_agg(md5(to_jsonb(t)::text),'' ORDER BY to_jsonb(t)::text),'')) FROM recipe_tool t),'favorite_recipe',(SELECT md5(coalesce(string_agg(md5(to_jsonb(t)::text),'' ORDER BY to_jsonb(t)::text),'')) FROM favorite_recipe t),'recipe_cook_record',(SELECT md5(coalesce(string_agg(md5(to_jsonb(t)::text),'' ORDER BY to_jsonb(t)::text),'')) FROM recipe_cook_record t),'meal_session',(SELECT md5(coalesce(string_agg(md5(to_jsonb(t)::text),'' ORDER BY to_jsonb(t)::text),'')) FROM meal_session t),'meal_action',(SELECT md5(coalesce(string_agg(md5(to_jsonb(t)::text),'' ORDER BY to_jsonb(t)::text),'')) FROM meal_action t),'meal_option',(SELECT md5(coalesce(string_agg(md5(to_jsonb(t)::text),'' ORDER BY to_jsonb(t)::text),'')) FROM meal_option t),'meal_guide',(SELECT md5(coalesce(string_agg(md5(to_jsonb(t)::text),'' ORDER BY to_jsonb(t)::text),'')) FROM meal_guide t),'shopping_list',(SELECT md5(coalesce(string_agg(md5(to_jsonb(t)::text),'' ORDER BY to_jsonb(t)::text),'')) FROM shopping_list t),'shopping_list_item',(SELECT md5(coalesce(string_agg(md5(to_jsonb(t)::text),'' ORDER BY to_jsonb(t)::text),'')) FROM shopping_list_item t),'shopping_product_match',(SELECT md5(coalesce(string_agg(md5(to_jsonb(t)::text),'' ORDER BY to_jsonb(t)::text),'')) FROM shopping_product_match t),'shopping_product_match_item',(SELECT md5(coalesce(string_agg(md5(to_jsonb(t)::text),'' ORDER BY to_jsonb(t)::text),'')) FROM shopping_product_match_item t),'shopping_product_purchase_group',(SELECT md5(coalesce(string_agg(md5(to_jsonb(t)::text),'' ORDER BY to_jsonb(t)::text),'')) FROM shopping_product_purchase_group t),'recipe_search_document',(SELECT md5(coalesce(string_agg(md5(to_jsonb(t)::text),'' ORDER BY to_jsonb(t)::text),'')) FROM recipe_search_document t))::text") String protectionDigests();
    // 治理批次低频串行，再按recipe/ingredient主键顺序行锁，包含并发重放。
    @Select("SELECT pg_advisory_xact_lock(716341502)::text") String lockGovernance();
    // 仅显式写入事务加SHARE表锁，封闭摘要核对到提交之间的历史/别名并发写窗口。
    @Update("LOCK TABLE recipe_source_document,recipe_step,recipe_tool,favorite_recipe,recipe_cook_record,meal_session,meal_action,meal_option,meal_guide,shopping_list,shopping_list_item,shopping_product_match,shopping_product_match_item,shopping_product_purchase_group,recipe_search_document,ingredient_alias IN SHARE MODE")
    void lockProtection();
    @Select("SELECT id,request_key::text AS request_key,input_hash,manifest_hash,approval_hash,rule_version,target_database,target_identifier,reviewer,status,state_json::text AS state_json,result_json::text AS result_json FROM recipe_repair_batch WHERE request_key=#{key}::uuid FOR UPDATE")
    RecipeRepairBatchEntity batch(@Param("key") String key);
    @Insert("INSERT INTO recipe_repair_batch(request_key,input_hash,manifest_hash,approval_hash,rule_version,target_database,target_identifier,reviewer,status,state_json,result_json) VALUES(#{requestKey}::uuid,#{inputHash},#{manifestHash},#{approvalHash},#{ruleVersion},#{targetDatabase},#{targetIdentifier},#{reviewer},#{status},#{stateJson}::jsonb,#{resultJson}::jsonb)")
    @Options(useGeneratedKeys=true,keyProperty="id") int insertBatch(RecipeRepairBatchEntity row);
    @Update("UPDATE recipe_repair_batch SET status='ROLLED_BACK',rolled_back_at=clock_timestamp(),result_json=#{result}::jsonb WHERE id=#{id} AND status='APPLIED'")
    int rolledBack(@Param("id") long id, @Param("result") String result);
    @Insert("INSERT INTO recipe_repair_entry(batch_id,candidate_key,source_id,recipe_id,original_line_id,resulting_line_id,action,before_json,after_json,execution_json) VALUES(#{batch},#{key},#{source},#{recipe},#{oldLine},#{newLine},#{action},#{before}::jsonb,#{after}::jsonb,#{execution}::jsonb)")
    int audit(@Param("batch") long batch, @Param("key") String key, @Param("source") Long source,
            @Param("recipe") Long recipe, @Param("oldLine") Long oldLine, @Param("newLine") Long newLine,
            @Param("action") String action, @Param("before") String before, @Param("after") String after, @Param("execution") String execution);
    @Select("SELECT jsonb_build_object('recipe',to_jsonb(r),'source',to_jsonb(s),'document',to_jsonb(d))::text FROM recipe r JOIN recipe_source s ON s.id=r.source_id JOIN recipe_source_document d ON d.id=r.source_document_id WHERE r.id=#{id}")
    String header(@Param("id") long id);
    @Select("SELECT jsonb_build_object('recipe',to_jsonb(r),'source',to_jsonb(s),'document',to_jsonb(d))::text FROM recipe r JOIN recipe_source s ON s.id=r.source_id JOIN recipe_source_document d ON d.id=r.source_document_id WHERE r.id=#{id} FOR UPDATE OF r,s,d")
    String lockHeader(@Param("id") long id);
    @Select("SELECT to_jsonb(ri)::text FROM recipe_ingredient ri WHERE recipe_id=#{id} ORDER BY id") List<String> lines(@Param("id") long id);
    @Select("SELECT to_jsonb(ri)::text FROM recipe_ingredient ri WHERE recipe_id=#{id} ORDER BY id FOR UPDATE") List<String> lockLines(@Param("id") long id);
    @Select("SELECT to_jsonb(i)::text FROM ingredient i WHERE id=#{id}") String identity(@Param("id") long id);
    @Select("SELECT to_jsonb(i)::text FROM ingredient i WHERE id=#{id} FOR UPDATE") String lockIdentity(@Param("id") long id);
    @Select("SELECT id FROM ingredient WHERE canonical_name=#{name}") Long identityNamed(@Param("name") String name);
    @Select("SELECT jsonb_build_object('id',ri.id,'recipe_id',ri.recipe_id,'ingredient_id',ri.ingredient_id)::text FROM recipe_ingredient ri WHERE ingredient_id=#{id} ORDER BY recipe_id,id FOR UPDATE") List<String> identityReferences(@Param("id") long id);
    @Select("SELECT jsonb_build_object('id',ri.id,'recipe_id',ri.recipe_id,'ingredient_id',ri.ingredient_id)::text FROM recipe_ingredient ri WHERE ingredient_id=#{id} ORDER BY recipe_id,id") List<String> readIdentityReferences(@Param("id") long id);
    @Select("SELECT to_jsonb(a)::text FROM ingredient_alias a WHERE ingredient_id=#{id} ORDER BY id") List<String> aliases(@Param("id") long id);
    @Select(value="INSERT INTO ingredient(canonical_name,normalization_status) VALUES(#{name},'UNREVIEWED') RETURNING id",affectData=true)
    Long createIdentity(@Param("name") String name);
    @Update("UPDATE ingredient SET canonical_name=#{name} WHERE id=#{id}") int renameIdentity(@Param("id") long id,@Param("name") String name);
    @Delete("DELETE FROM ingredient WHERE id=#{id}") int deleteIdentity(@Param("id") long id);
    @Update("UPDATE recipe SET servings=#{servings} WHERE id=#{id}") int servings(@Param("id") long id,@Param("servings") Integer servings);
    @Update("UPDATE recipe_ingredient SET ingredient_id=#{ingredient},raw_name=#{name},amount_min=#{min},amount_max=#{max},unit_code=#{code},unit_label=#{label},optional=#{optional},sort_order=#{order} WHERE id=#{id} AND recipe_id=#{recipe}")
    int changeLine(@Param("id") long id,@Param("recipe") long recipe,@Param("ingredient") Long ingredient,@Param("name") String name,
            @Param("min") BigDecimal min,@Param("max") BigDecimal max,@Param("code") String code,@Param("label") String label,@Param("optional") boolean optional,@Param("order") int order);
    @Select(value="INSERT INTO recipe_ingredient(recipe_id,ingredient_id,raw_name,amount_min,amount_max,unit_code,unit_label,optional,raw_line,sort_order) VALUES(#{recipe},#{ingredient},#{name},#{min},#{max},#{code},#{label},#{optional},#{raw},#{order}) RETURNING id",affectData=true)
    Long addLine(@Param("recipe") long recipe,@Param("ingredient") Long ingredient,@Param("name") String name,
            @Param("min") BigDecimal min,@Param("max") BigDecimal max,@Param("code") String code,@Param("label") String label,@Param("optional") boolean optional,@Param("raw") String raw,@Param("order") int order);
    @Delete("DELETE FROM recipe_ingredient WHERE id=#{id} AND recipe_id=#{recipe}") int removeLine(@Param("id") long id,@Param("recipe") long recipe);
    @Insert("INSERT INTO recipe_ingredient(id,recipe_id,ingredient_id,raw_name,amount_min,amount_max,unit_code,unit_label,optional,raw_line,sort_order) VALUES(#{id},#{recipe},#{ingredient},#{name},#{min},#{max},#{code},#{label},#{optional},#{raw},#{order})")
    int restoreLine(@Param("id") long id,@Param("recipe") long recipe,@Param("ingredient") Long ingredient,@Param("name") String name,
            @Param("min") BigDecimal min,@Param("max") BigDecimal max,@Param("code") String code,@Param("label") String label,@Param("optional") boolean optional,@Param("raw") String raw,@Param("order") int order);
}
