package com.cooker.recipe.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cooker.recipe.entity.IngredientEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface IngredientMapper extends BaseMapper<IngredientEntity> {
    @Select("SELECT * FROM ingredient WHERE canonical_name = #{name}")
    IngredientEntity findByCanonicalName(@Param("name") String name);
}
