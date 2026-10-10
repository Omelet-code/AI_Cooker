package com.cooker.conversation.mapper;

import org.apache.ibatis.annotations.*;

/** 只操作本模块的指导快照；所有读取包含用户/会话/餐边界。 */
@Mapper
public interface MealGuideMapper {
    @Select("SELECT action_id FROM meal_guide WHERE meal_id=#{meal} AND version=#{version} AND option_id=#{option}")
    Long existing(@Param("meal") long meal,@Param("version") int version,@Param("option") long option);
    @Select("SELECT view_json::text FROM meal_guide WHERE user_id=#{user} AND conversation_id=#{conversation} AND meal_id=#{meal} AND id=#{guide}")
    String get(@Param("user") long user,@Param("conversation") long conversation,@Param("meal") long meal,@Param("guide") long guide);
    @Select(value="INSERT INTO meal_guide(user_id,conversation_id,meal_id,version,option_id,action_id,persona_version,prompt_version,view_json) VALUES(#{user},#{conversation},#{meal},#{version},#{option},#{action},'xiaochu-v1',(CAST(#{view} AS jsonb)->>'promptVersion'),CAST(#{view} AS jsonb)) RETURNING id",affectData=true)
    long insert(@Param("user") long user,@Param("conversation") long conversation,@Param("meal") long meal,@Param("version") int version,@Param("option") long option,@Param("action") long action,@Param("view") String view);
    @Update("UPDATE meal_guide SET view_json=CAST(#{view} AS jsonb) WHERE id=#{guide}")
    int save(@Param("guide") long guide,@Param("view") String view);
}
