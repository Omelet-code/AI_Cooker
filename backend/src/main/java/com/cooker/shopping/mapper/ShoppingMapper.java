package com.cooker.shopping.mapper;

import com.cooker.shopping.entity.ShoppingList;
import com.cooker.shopping.vo.ShoppingView.Item;
import org.apache.ibatis.annotations.*;

/** 参数化SQL与唯一约束原子插入；不在已失败的PG事务里捕获唯一异常再查询。 */
@Mapper
public interface ShoppingMapper {
    int lockRequest(@Param("user")long user,@Param("request")String request);
    ShoppingList request(@Param("user")long user,@Param("request")String request);
    ShoppingList get(@Param("user")long user,@Param("id")long id);
    Long insert(@Param("row")ShoppingList row);
    int item(@Param("list")long list,@Param("user")long user,@Param("order")int order,@Param("item")Item item);
    int view(@Param("user")long user,@Param("id")long id,@Param("view")String view);
}
