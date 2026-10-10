package com.cooker.shopping.service;

import com.cooker.shopping.dto.ShoppingRequest;
import com.cooker.shopping.vo.ShoppingView;

public interface ShoppingService {
    ShoppingView.Preview preview(String conversation,String meal,int version,String option);
    record Creation(boolean created,ShoppingView view) {}
    Creation create(ShoppingRequest request);
    ShoppingView get(String id);
}
