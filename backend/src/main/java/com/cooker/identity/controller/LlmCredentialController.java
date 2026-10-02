package com.cooker.identity.controller;

import com.cooker.common.ApiResponse;
import com.cooker.common.ApiException;
import com.cooker.identity.dto.SaveLlmCredentialRequest;
import com.cooker.identity.service.LlmCredentialService;
import com.cooker.identity.vo.LlmCredentialStatus;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class LlmCredentialController {
    private final LlmCredentialService credentials;
    public LlmCredentialController(LlmCredentialService credentials) { this.credentials = credentials; }

    @PutMapping("/users/me/llm-credential")
    public ApiResponse<LlmCredentialStatus> save(@RequestBody SaveLlmCredentialRequest request,
            HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        if (request == null || request.apiKey() == null || !request.apiKey().isString())
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "请求参数不合法");
        return ApiResponse.ok(credentials.saveCurrentUser(request.apiKey().stringValue()));
    }

    @DeleteMapping("/users/me/llm-credential")
    public ApiResponse<LlmCredentialStatus> delete(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return ApiResponse.ok(credentials.deleteCurrentUser());
    }

    @GetMapping("/users/me/llm-credential/status")
    public ApiResponse<LlmCredentialStatus> status(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return ApiResponse.ok(credentials.currentUserStatus());
    }
}
