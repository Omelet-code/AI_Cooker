package com.cooker.t18;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** 只允许本机替身HTTP；在HTTP边界核对协议、先记账。不能配置为真实供应商客户端。 */
public final class GuardedLocalTransport {
    private GuardedLocalTransport() {}
    public static RestClient client(URI endpoint, SamplingLedger ledger, SamplingLedger.Ticket ticket) {
        if (!"http".equals(endpoint.getScheme()) || !"127.0.0.1".equals(endpoint.getHost()) || endpoint.getPort() <= 0
                || endpoint.getUserInfo() != null || endpoint.getQuery() != null) throw new IllegalArgumentException("仅允许本机替身地址");
        var http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(1)).build();
        var factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofMillis(400));
        return RestClient.builder().requestFactory(factory).requestInterceptor((request, body, execution) -> {
            if (!endpoint.equals(request.getURI()) || request.getMethod() != HttpMethod.POST) {
                ledger.stop(); throw new SamplingLedger.Stopped();
            }
            var payload = new ObjectMapper().readTree(body);
            boolean query = ticket.lane == SamplingLedger.Lane.QUERY;
            if (query ? !"query".equals(payload.path("parameters").path("text_type").asText())
                    || payload.path("input").path("texts").size() != 1
                    : !payload.path("messages").isArray() || payload.path("stream").asBoolean(false)) {
                ledger.stop(); throw new SamplingLedger.Stopped();
            }
            ledger.beforeHttp(ticket);
            try {
                var response = execution.execute(request, body);
                if (!response.getStatusCode().is2xxSuccessful()) {
                    ledger.finish(ticket, SamplingLedger.State.FAILED);
                    response.close();
                    throw new SamplingLedger.Stopped();
                }
                // 200也可能截断、非法JSON或向量错误；由调用者完成解码后才登记成功。
                return response;
            } catch (java.io.IOException e) {
                ledger.finish(ticket, SamplingLedger.State.UNKNOWN);
                throw e;
            }
        }).build();
    }
}
