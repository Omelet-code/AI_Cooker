package com.cooker.shopping.mapper;

import com.cooker.shopping.entity.ProductMatchRow;
import com.cooker.shopping.vo.ProductMatchView;
import org.apache.ibatis.annotations.*;

/** 仅shopping自己的表，所有历史查询都绑定用户与清单。 */
@Mapper
public interface ProductMatchMapper {
    Long lockList(@Param("user")long user,@Param("list")long list);
    ProductMatchRow request(@Param("user")long user,@Param("request")String request);
    ProductMatchRow get(@Param("user")long user,@Param("list")long list,@Param("id")long id);
    ProductMatchRow latest(@Param("user")long user,@Param("list")long list);
    Long insert(@Param("row")ProductMatchRow row,@Param("costs")ProductMatchView.Costs costs);
    int item(@Param("id")long id,@Param("user")long user,@Param("list")long list,@Param("order")int order,@Param("line")ProductMatchView.Line line);
    int group(@Param("id")long id,@Param("user")long user,@Param("list")long list,@Param("group")ProductMatchView.PurchaseGroup group);
    int view(@Param("id")long id,@Param("user")long user,@Param("view")String view);
}
