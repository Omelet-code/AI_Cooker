package com.cooker.agent;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.cooker.agent.service.ModelGateway;
import com.cooker.agent.service.RecommendationClock;
import com.cooker.agent.service.impl.RecommendationServiceImpl;
import com.cooker.common.ApiException;
import com.cooker.conversation.service.ConversationRecommendationService;
import com.cooker.conversation.service.ConversationTurnService;
import com.cooker.identity.service.CurrentUserService;
import com.cooker.recipe.service.RecipeReadService;
import com.cooker.retrieval.service.RecipeSearchService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validator;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RecommendationServiceDisabledTest {
    @Test
    void 功能关闭时在登记轮次前拒绝且不调用外部或业务依赖() {
        ModelGateway gateway = mock(ModelGateway.class);
        RecipeSearchService search = mock(RecipeSearchService.class);
        RecipeReadService recipes = mock(RecipeReadService.class);
        ConversationTurnService turns = mock(ConversationTurnService.class);
        ConversationRecommendationService conversation = mock(ConversationRecommendationService.class);
        CurrentUserService currentUser = mock(CurrentUserService.class);
        when(currentUser.requireUserId()).thenReturn(7L);
        var service = new RecommendationServiceImpl(gateway, search, recipes, turns, conversation,
                currentUser, new ObjectMapper(), mock(Validator.class), new RecommendationClock(), false);

        assertThatThrownBy(() -> service.send("1", new com.cooker.agent.dto.SendRecommendationRequest(
                UUID.randomUUID().toString(), "推荐一道菜")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("推荐执行功能当前未启用");

        verify(currentUser).requireUserId();
        verifyNoInteractions(gateway, search, recipes, turns, conversation);
    }
}
