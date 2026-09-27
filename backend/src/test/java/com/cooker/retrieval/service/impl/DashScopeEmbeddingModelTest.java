package com.cooker.retrieval.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.cooker.retrieval.config.RetrievalIndexProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** 用模拟 HTTP 响应验证百炼请求参数，不产生真实收费调用。 */
class DashScopeEmbeddingModelTest {
    @Test
    void 查询和文档分别使用对应的textType() {
        var props = new RetrievalIndexProperties();
        props.setApiKey("test-only");
        props.setEndpoint("https://example.invalid/embedding");
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var model = new DashScopeEmbeddingModel(builder.build(), new ObjectMapper(), props);
        String response = "{\"output\":{\"embeddings\":[{\"text_index\":0,\"embedding\":["
                + "0.1,".repeat(1023) + "0.1]}]}}";
        server.expect(once(), requestTo(props.getEndpoint()))
                .andExpect(content().string(containsString("\"text_type\":\"query\"")))
                .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(props.getEndpoint()))
                .andExpect(content().string(containsString("\"text_type\":\"document\"")))
                .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
        assertThat(model.embedQuery("清淡")).hasSize(1024);
        assertThat(model.embed("菜名：番茄炒蛋")).hasSize(1024);
        server.verify();
    }
}
