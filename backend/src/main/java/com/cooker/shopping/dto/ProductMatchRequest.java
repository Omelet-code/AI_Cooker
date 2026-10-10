package com.cooker.shopping.dto;

import com.cooker.shopping.service.ShoppingJson;
import java.util.UUID;

/** 商品请求只接收幂等键与城市，不信任客户端报价。 */
public record ProductMatchRequest(String requestId,String cityCode) {
    public static ProductMatchRequest parse(String raw){
        var node=ShoppingJson.parse(raw);ShoppingJson.keys(node,"requestId","cityCode");
        var key=node.path("requestId");var city=node.path("cityCode");
        if(!key.isTextual()||!key.asText().matches("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")||!city.isTextual()||!city.asText().matches("[0-9]{6}"))throw ShoppingJson.invalid();
        return new ProductMatchRequest(UUID.fromString(key.asText()).toString(),city.asText());
    }
}
