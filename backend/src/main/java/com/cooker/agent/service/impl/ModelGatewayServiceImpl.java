package com.cooker.agent.service.impl;

import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.EmptyUsage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.http.converter.HttpMessageNotReadableException;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.cooker.agent.config.DeepSeekChatClientFactory;
import com.cooker.agent.dto.ModelChatRequest;
import com.cooker.agent.dto.ModelChatResponse;
import com.cooker.agent.dto.ModelMessage;
import com.cooker.agent.dto.ModelOutputMode;
import com.cooker.agent.dto.ModelUsage;
import com.cooker.agent.service.ModelGateway;
import com.cooker.agent.service.ModelGatewayException;
import com.cooker.common.ApiException;
import com.cooker.identity.service.CurrentUserService;
import com.cooker.identity.service.LlmCredentialService;

/** 将身份凭证、有限 DTO 与一次 DeepSeek 调用隔离在 Agent Service 边界内。 */
@Service
public class ModelGatewayServiceImpl implements ModelGateway {
    private static final int MAX_MESSAGES = 16;
    private static final int MAX_CONTENT_CODE_POINTS = 20_000;

    private final CurrentUserService currentUser;
    private final LlmCredentialService credentialService;
    private final DeepSeekChatClientFactory clientFactory;
    private final ObjectMapper json = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    public ModelGatewayServiceImpl(CurrentUserService currentUser, LlmCredentialService credentialService,
            DeepSeekChatClientFactory clientFactory) {
        this.currentUser = currentUser;
        this.credentialService = credentialService;
        this.clientFactory = clientFactory;
    }

    @Override
    public ModelChatResponse complete(ModelChatRequest request) {
        validate(request);
        requireAuthenticatedUser();
        String apiKey = decryptCurrentUserCredential();

        ChatModel client;
        try {
            client = clientFactory.create(apiKey, request.outputMode());
        } catch (ModelGatewayException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new ModelGatewayException(ModelGatewayException.Reason.CREDENTIAL_SERVICE_UNAVAILABLE,
                    "模型凭证暂不可用");
        }
        try {
            var response = client.call(new Prompt(toSpringMessages(request.messages())));
            if (response == null || response.getResults() == null || response.getResults().size() != 1) {
                throw invalidResponse();
            }

            var generation = response.getResults().getFirst();
            String finishReason = generation.getMetadata() == null
                    ? null : generation.getMetadata().getFinishReason();
            // 截断表示模型输出不完整，两种格式都必须失败，不能把半截文本当作成功结果。
            if ("LENGTH".equalsIgnoreCase(finishReason)) {
                throw invalidResponse();
            }
            String content = generation.getOutput() == null ? null : generation.getOutput().getText();
            if (content == null || content.isBlank()) {
                throw invalidResponse();
            }
            if (request.outputMode() == ModelOutputMode.JSON) {
                if (!isJson(content)) {
                    throw invalidResponse();
                }
            }

            return new ModelChatResponse(content, finishReason,
                    response.getMetadata() == null ? null : mapUsage(response.getMetadata().getUsage()));
        } catch (ModelGatewayException e) {
            throw e;
        } catch (RuntimeException e) {
            throw mapProviderFailure(e);
        }
    }

    private void validate(ModelChatRequest request) {
        if (request == null || request.outputMode() == null || request.messages() == null
                || request.messages().isEmpty() || request.messages().size() > MAX_MESSAGES) {
            throw invalidInput();
        }
        int totalCodePoints = 0;
        for (ModelMessage message : request.messages()) {
            if (message == null || message.role() == null || message.content() == null
                    || message.content().isBlank()) {
                throw invalidInput();
            }
            totalCodePoints += message.content().codePointCount(0, message.content().length());
            if (totalCodePoints > MAX_CONTENT_CODE_POINTS) {
                throw invalidInput();
            }
        }
        if (request.outputMode() == ModelOutputMode.JSON && !containsJsonOutputInstruction(request.messages())) {
            throw invalidInput();
        }
    }

    private boolean containsJsonOutputInstruction(List<ModelMessage> messages) {
        boolean mentionsJson = false;
        boolean requestsOutput = false;
        for (ModelMessage message : messages) {
            String content = message.content().toLowerCase(java.util.Locale.ROOT);
            mentionsJson |= content.contains("json");
            requestsOutput |= content.contains("输出") || content.contains("output") || content.contains("return");
        }
        return mentionsJson && requestsOutput;
    }

    private void requireAuthenticatedUser() {
        try {
            currentUser.requireUserId();
        } catch (ApiException e) {
            throw new ModelGatewayException(ModelGatewayException.Reason.AUTHENTICATION_REQUIRED,
                    "请先登录后再调用模型");
        }
    }

    private String decryptCurrentUserCredential() {
        try {
            return credentialService.decryptCurrentUserCredential();
        } catch (ApiException e) {
            if ("LLM_CREDENTIAL_NOT_CONFIGURED".equals(e.code())) {
                throw new ModelGatewayException(ModelGatewayException.Reason.CREDENTIAL_NOT_CONFIGURED,
                        "尚未配置模型凭证");
            }
            throw new ModelGatewayException(ModelGatewayException.Reason.CREDENTIAL_SERVICE_UNAVAILABLE,
                    "模型凭证暂不可用");
        } catch (RuntimeException e) {
            throw new ModelGatewayException(ModelGatewayException.Reason.CREDENTIAL_SERVICE_UNAVAILABLE,
                    "模型凭证暂不可用");
        }
    }

    private List<Message> toSpringMessages(List<ModelMessage> messages) {
        List<Message> converted = new ArrayList<>(messages.size());
        for (ModelMessage message : messages) {
            converted.add(switch (message.role()) {
                case SYSTEM -> new SystemMessage(message.content());
                case USER -> new UserMessage(message.content());
                case ASSISTANT -> new AssistantMessage(message.content());
            });
        }
        return converted;
    }

    private boolean isJson(String content) {
        try {
            return json.readTree(content) != null;
        } catch (JsonProcessingException e) {
            return false;
        }
    }

    private ModelUsage mapUsage(Usage usage) {
        if (usage == null || usage instanceof EmptyUsage) {
            return null;
        }
        return new ModelUsage(usage.getPromptTokens(), usage.getCompletionTokens(), usage.getTotalTokens());
    }

    private ModelGatewayException mapProviderFailure(RuntimeException failure) {
        if (failure instanceof RestClientResponseException responseException) {
            HttpStatusCode status = responseException.getStatusCode();
            if (status.value() == 401 || status.value() == 403) {
                return new ModelGatewayException(ModelGatewayException.Reason.MODEL_AUTHENTICATION_FAILED,
                        "模型供应商拒绝了凭证");
            }
            if (status.value() == 429) {
                return new ModelGatewayException(ModelGatewayException.Reason.RATE_LIMITED,
                        "模型供应商请求频率受限");
            }
            return new ModelGatewayException(ModelGatewayException.Reason.PROVIDER_UNAVAILABLE,
                    "模型供应商暂不可用");
        }
        if (containsTimeout(failure)) {
            return new ModelGatewayException(ModelGatewayException.Reason.TIMEOUT, "模型请求超时");
        }
        if (failure instanceof HttpMessageNotReadableException
                || failure.getClass().getName().contains("HttpMessageConversion")) {
            return invalidResponse();
        }
        if (failure instanceof ResourceAccessException) {
            return new ModelGatewayException(ModelGatewayException.Reason.PROVIDER_UNAVAILABLE,
                    "模型供应商暂不可用");
        }
        return invalidResponse();
    }

    private boolean containsTimeout(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof SocketTimeoutException || current instanceof HttpTimeoutException
                    || current instanceof TimeoutException) {
                return true;
            }
        }
        return false;
    }

    private ModelGatewayException invalidInput() {
        return new ModelGatewayException(ModelGatewayException.Reason.INVALID_INPUT, "模型请求参数不合法");
    }

    private ModelGatewayException invalidResponse() {
        return new ModelGatewayException(ModelGatewayException.Reason.INVALID_RESPONSE, "模型响应无法安全处理");
    }
}
