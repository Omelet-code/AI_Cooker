package com.cooker.common;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.beans.TypeMismatchException;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiResponse<Void>> handle(ApiException e) {
        return ResponseEntity.status(e.status()).body(ApiResponse.error(e.code(), e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> validation() {
        return ResponseEntity.badRequest().body(ApiResponse.error("VALIDATION_ERROR", "请求参数不合法"));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, TypeMismatchException.class})
    public ResponseEntity<ApiResponse<Void>> invalidInput() {
        // JSON 语法、字段类型和必需请求体缺失都是客户端输入错误；不回显原文或解析异常。
        return ResponseEntity.badRequest().body(ApiResponse.error("VALIDATION_ERROR", "请求参数不合法"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> unexpected(Exception e) {
        // 不回显 SQL、令牌或异常细节；服务端监控可另行记录脱敏事件。
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error("INTERNAL_ERROR", "服务暂不可用"));
    }
}
