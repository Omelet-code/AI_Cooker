package com.cooker.evaluation.transport;

import com.fasterxml.jackson.databind.*;
import java.io.*;
import java.net.URI;
import org.springframework.http.*;
import org.springframework.http.client.*;

/** HTTP执行边界只接收方法票据；完全读取有界响应，错误及读超时在方法退出前停止。 */
final class BudgetRequestFactory implements ClientHttpRequestFactory {
    private final ClientHttpRequestFactory delegate;
    private final URI endpoint;
    private final SyncModelTransport.Lane lane;
    private final EvaluationBatch batch;
    private final ThreadLocal<EvaluationBatch.Ticket> active;
    BudgetRequestFactory(ClientHttpRequestFactory delegate,URI endpoint,SyncModelTransport.Lane lane,EvaluationBatch batch,ThreadLocal<EvaluationBatch.Ticket> active){
        this.delegate=delegate;this.endpoint=endpoint;this.lane=lane;this.batch=batch;this.active=active;
    }
    @Override public ClientHttpRequest createRequest(URI uri,HttpMethod method){
        return new AbstractClientHttpRequest(){
            final ByteArrayOutputStream body=new ByteArrayOutputStream();
            public HttpMethod getMethod(){return method;}public URI getURI(){return uri;}
            @Override protected OutputStream getBodyInternal(HttpHeaders headers){return body;}
            @Override protected ClientHttpResponse executeInternal(HttpHeaders headers)throws IOException{
                synchronized(batch){
                    var ticket=active.get();
                    if(!endpoint.equals(uri)||method!=HttpMethod.POST||ticket==null||ticket.lane!=lane||body.size()>1_000_000){batch.stop();throw new EvaluationBatch.Stopped();}
                    JsonNode json=new ObjectMapper().readTree(body.toByteArray());
                    if(!valid(json)){batch.stop();throw new EvaluationBatch.Stopped();}
                    batch.beforeHttp(ticket);
                    try{
                        var request=delegate.createRequest(uri,method);request.getHeaders().putAll(headers);
                        request.getBody().write(body.toByteArray());
                        try(var response=request.execute()){
                            if(!response.getStatusCode().is2xxSuccessful()){
                                batch.failed(ticket,false);
                                // 停批不覆盖本次供应商分类，也不携带供应商错误正文。
                                throw new org.springframework.web.client.RestClientResponseException("模型供应商请求失败",
                                    response.getStatusCode().value(),"",new HttpHeaders(),new byte[0],null);
                            }
                            byte[] bytes=response.getBody().readNBytes(4_000_001);
                            if(bytes.length>4_000_000)throw new IOException("模型响应超过受控上限");
                            if(lane==SyncModelTransport.Lane.LLM){
                                JsonNode value;
                                try{value=new ObjectMapper().readTree(bytes);}catch(com.fasterxml.jackson.core.JsonProcessingException e){batch.failed(ticket,false);throw new IllegalArgumentException("模型响应无法安全处理");}
                                var choices=value==null?com.fasterxml.jackson.databind.node.MissingNode.getInstance():value.path("choices");
                                String content=choices.path(0).path("message").path("content").asText("");
                                boolean validContent;
                                try{validContent=new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(content)!=null;}
                                catch(com.fasterxml.jackson.core.JsonProcessingException e){validContent=false;}
                                if(!choices.isArray()||choices.size()!=1||!"stop".equalsIgnoreCase(choices.path(0).path("finish_reason").asText())
                                        ||content.isBlank()||!validContent){batch.failed(ticket,false);throw new IllegalArgumentException("模型响应无法安全处理");}
                            }
                            var responseHeaders=new HttpHeaders();responseHeaders.putAll(response.getHeaders());
                            var status=response.getStatusCode();String text=response.getStatusText();
                            return new ClientHttpResponse(){public HttpStatusCode getStatusCode(){return status;}public String getStatusText(){return text;}
                                public HttpHeaders getHeaders(){return responseHeaders;}public InputStream getBody(){return new ByteArrayInputStream(bytes);}public void close(){}};
                        }
                    }catch(IOException e){batch.failed(ticket,true);throw e;}
                }
            }
        };
    }
    private boolean valid(JsonNode json){
        if(json==null)return false;
        if(lane==SyncModelTransport.Lane.QUERY)return "text-embedding-v4".equals(json.path("model").asText())
            &&json.path("parameters").path("dimension").asInt()==1024
            &&"query".equals(json.path("parameters").path("text_type").asText())
            &&json.path("input").path("texts").isArray()&&json.path("input").path("texts").size()==1;
        return "deepseek-flash".equals(json.path("model").asText())&&json.path("messages").isArray()
            &&!json.path("stream").asBoolean(false)&&"disabled".equals(json.path("thinking").path("type").asText())
            &&"json_object".equals(json.path("response_format").path("type").asText())
            &&(json.path("max_tokens").asInt()==2048||json.path("max_tokens").asInt()==8192);
    }
}
