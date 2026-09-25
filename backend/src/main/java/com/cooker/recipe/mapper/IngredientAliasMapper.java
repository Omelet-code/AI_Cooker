package com.cooker.recipe.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cooker.recipe.entity.IngredientAliasEntity;
import com.cooker.recipe.entity.IngredientEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface IngredientAliasMapper extends BaseMapper<IngredientAliasEntity> {
    @Select("SELECT i.* FROM ingredient_alias a JOIN ingredient i ON i.id = a.ingredient_id WHERE a.alias_name = #{name}")
    IngredientEntity findIngredientByAlias(@Param("name") String name);
}
