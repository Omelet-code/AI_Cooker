package com.cooker.conversation.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 采购专用无锁读取；创建时由公共Service持父行与餐单锁，不恢复动作。 */
@Mapper
public interface ShoppingSourceMapper {
    @Select("SELECT EXISTS(SELECT 1 FROM meal_action WHERE user_id=#{user} AND conversation_id=#{conversation} AND meal_id=#{meal} AND status IN ('PENDING','RUNNING'))")
    boolean busy(@Param("user") long user,@Param("conversation") long conversation,@Param("meal") long meal);
}
