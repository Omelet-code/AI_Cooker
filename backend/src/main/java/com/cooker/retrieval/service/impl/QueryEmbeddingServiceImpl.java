package com.cooker.retrieval.service.impl;

import com.cooker.retrieval.config.RetrievalIndexProperties;
import com.cooker.retrieval.service.QueryEmbeddingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import java.time.Duration;
import java.net.URI;
import com.cooker.evaluation.transport.SyncModelTransport;
import com.cooker.evaluation.transport.DefaultSyncModelTransport;
import org.springframework.beans.factory.annotation.Autowired;

@Service
public class QueryEmbeddingServiceImpl implements QueryEmbeddingService {
    private final RetrievalIndexProperties properties;
    private final SyncModelTransport transport;

    public QueryEmbeddingServiceImpl(RetrievalIndexProperties properties) { this(properties, DefaultSyncModelTransport.ordinary()); }
    @Autowired
    public QueryEmbeddingServiceImpl(RetrievalIndexProperties properties, SyncModelTransport transport) { this.properties = properties; this.transport=transport; }

    @Override
    public float[] embed(String query) {
        return transport.call(SyncModelTransport.Lane.QUERY, () -> {
            if (!StringUtils.hasText(properties.getApiKey())) return null;
            return new DashScopeEmbeddingModel(transport.builder(SyncModelTransport.Lane.QUERY, URI.create(properties.getEndpoint()),
                    Duration.ofSeconds(3), Duration.ofSeconds(5)).build(), new ObjectMapper(), properties).embedQuery(query);
        });
    }
}
