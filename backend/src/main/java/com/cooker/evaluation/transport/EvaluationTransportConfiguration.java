package com.cooker.evaluation.transport;

import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import java.util.*;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;

@Configuration(proxyBeanMethods=false)
@EnableConfigurationProperties(EvaluationHttpProperties.class)
public class EvaluationTransportConfiguration {
    @Bean(destroyMethod="close")
    public DefaultSyncModelTransport syncModelTransport(EvaluationHttpProperties properties)throws Exception {
        if(!properties.isEnabled())return DefaultSyncModelTransport.ordinary();
        DefaultSyncModelTransport.verifyRuntime();
        // 缺上下文可启动为关闭状态，但每次query/LLM调用在发送前拒绝；不创建空额度账本。
        if(properties.getBatchFile()==null||properties.getBatchFile().isBlank())return DefaultSyncModelTransport.controlled(null);
        Path source=Path.of(properties.getBatchFile());
        if(!source.isAbsolute()||Files.size(source)>65536)throw new IllegalArgumentException("批准文件须为有界绝对路径");
        var mapper=new ObjectMapper().enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        var json=mapper.readTree(Files.readString(source));
        var fields=new HashSet<String>();json.fieldNames().forEachRemaining(fields::add);
        if(!fields.equals(Set.of("schemaVersion","batchId","authorizationId","ledgerPath","queryMethods","queryHttp","llmMethods","llmHttp"))
                ||json.path("schemaVersion").asInt(-1)!=1)throw new IllegalArgumentException("批准文件字段或版本无效");
        for(String key:List.of("batchId","authorizationId","ledgerPath"))if(!json.path(key).isTextual())throw new IllegalArgumentException("批准标识和路径须为字符串");
        for(String key:List.of("queryMethods","queryHttp","llmMethods","llmHttp"))if(!json.path(key).isIntegralNumber()||!json.path(key).canConvertToInt())throw new IllegalArgumentException("额度须为整数");
        var batch=new EvaluationBatch(json.path("batchId").asText(),json.path("authorizationId").asText(),Path.of(json.path("ledgerPath").asText()),
            json.path("queryMethods").asInt(),json.path("queryHttp").asInt(),json.path("llmMethods").asInt(),json.path("llmHttp").asInt());
        return DefaultSyncModelTransport.controlled(batch);
    }
}
