package com.cooker.shopping.mapper;

import com.cooker.shopping.service.ShoppingJson;
import com.cooker.shopping.vo.ProductMatchView.Product;
import java.sql.*;
import org.apache.ibatis.type.*;

/** 商品证据仅序列化服务器报价，不依赖跨模块JSON处理器。 */
public class ProductQuoteJsonTypeHandler extends BaseTypeHandler<Product> {
    @Override public void setNonNullParameter(PreparedStatement ps,int index,Product value,JdbcType type)throws SQLException{ps.setString(index,ShoppingJson.encode(value));}
    @Override public Product getNullableResult(ResultSet rs,String column)throws SQLException{return read(rs.getString(column));}
    @Override public Product getNullableResult(ResultSet rs,int column)throws SQLException{return read(rs.getString(column));}
    @Override public Product getNullableResult(CallableStatement cs,int column)throws SQLException{return read(cs.getString(column));}
    private Product read(String raw)throws SQLException{if(raw==null)return null;try{return ShoppingJson.JSON.readValue(raw,Product.class);}catch(Exception e){throw new SQLException("商品快照格式不合法");}}
}
