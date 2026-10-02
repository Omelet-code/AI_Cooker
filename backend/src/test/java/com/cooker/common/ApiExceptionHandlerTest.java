package com.cooker.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ApiExceptionHandlerTest {
    @Test void 内部异常仍返回脱敏500() {
        var response = new ApiExceptionHandler().unexpected(new IllegalStateException("private-diagnostic-marker"));
        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(response.getBody()).isEqualTo(ApiResponse.error("INTERNAL_ERROR", "服务暂不可用"));
        assertThat(response.getBody().toString()).doesNotContain("private-diagnostic-marker");
    }
}
