package com.cooker.retrieval.service.impl;

import com.cooker.retrieval.config.RetrievalIndexProperties;
import com.cooker.retrieval.service.QueryEmbeddingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import java.time.Duration;

@Service
public class QueryEmbeddingServiceImpl implements QueryEmbeddingService {
    private final RetrievalIndexProperties properties;

    public QueryEmbeddingServiceImpl(RetrievalIndexProperties properties) { this.properties = properties; }

    @Override
    public float[] embed(String query) {
        if (!StringUtils.hasText(properties.getApiKey())) return null;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(3));
        requestFactory.setReadTimeout(Duration.ofSeconds(5));
        return new DashScopeEmbeddingModel(RestClient.builder().requestFactory(requestFactory).build(),
                new ObjectMapper(), properties).embedQuery(query);
    }
}
