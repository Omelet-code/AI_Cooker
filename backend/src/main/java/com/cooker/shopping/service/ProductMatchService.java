package com.cooker.shopping.service;

import com.cooker.shopping.dto.ProductMatchRequest;
import com.cooker.shopping.vo.ProductMatchView;

public interface ProductMatchService {
    record Creation(boolean created,ProductMatchView view) {}
    Creation create(String list,ProductMatchRequest request);
    ProductMatchView get(String list,String id);
    ProductMatchView latest(String list);
}
