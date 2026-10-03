package com.cooker.identity.controller;

import com.cooker.common.ApiException;
import com.cooker.common.ApiResponse;
import com.cooker.identity.dto.SaveDiningProfileRequest;
import com.cooker.identity.service.UserDiningProfileService;
import com.cooker.identity.vo.DiningProfileView;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Validator;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
public class DiningProfileController {
    private final UserDiningProfileService profiles;
    private final Validator validator;
    public DiningProfileController(UserDiningProfileService profiles, Validator validator) {
        this.profiles = profiles;
        this.validator = validator;
    }

    @GetMapping("/users/me/profile")
    public ApiResponse<DiningProfileView> current(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return ApiResponse.ok(profiles.currentUserProfile());
    }

    @PutMapping("/users/me/profile")
    public ApiResponse<DiningProfileView> save(@RequestBody JsonNode body, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        // 先检查原始JSON类型，再验证DTO；不允许解析器将2.0或"2"强制转成2。
        var request = SaveDiningProfileRequest.fromJson(body);
        if (!validator.validate(request).isEmpty())
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "请求参数不合法");
        return ApiResponse.ok(profiles.saveCurrentUser(request));
    }
}
