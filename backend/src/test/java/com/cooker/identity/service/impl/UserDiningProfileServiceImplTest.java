package com.cooker.identity.service.impl;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.cooker.common.ApiException;
import com.cooker.identity.dto.SaveDiningProfileRequest;
import com.cooker.identity.entity.UserDiningProfileEntity;
import com.cooker.identity.mapper.UserDiningProfileMapper;
import com.cooker.identity.service.CurrentUserService;
import org.junit.jupiter.api.Test;

class UserDiningProfileServiceImplTest {
    private final CurrentUserService current = mock(CurrentUserService.class);
    private final UserDiningProfileMapper mapper = mock(UserDiningProfileMapper.class);
    private final UserDiningProfileServiceImpl service = new UserDiningProfileServiceImpl(current, mapper);

    @Test void 缺档案返回未知且不写入() {
        when(current.requireUserId()).thenReturn(42L);
        when(mapper.lockExistingUser(42L)).thenReturn(42L);
        assertThat(service.currentUserProfile().defaultDiningCount()).isNull();
        verify(mapper).findByUserId(42L);
        verify(mapper, never()).upsert(anyLong(), any());
    }

    @Test void 失效账号不能查询或写档案() {
        when(current.requireUserId()).thenReturn(42L);
        when(mapper.lockExistingUser(42L)).thenReturn(null);
        assertAuth(() -> service.currentUserProfile());
        assertAuth(() -> service.saveCurrentUser(new SaveDiningProfileRequest(2)));
        assertAuth(() -> service.readForUser(42L));
        assertAuth(() -> service.readForUser(0));
        verify(mapper, never()).findByUserId(anyLong());
        verify(mapper, never()).upsert(anyLong(), any());
    }

    @Test void 可信显式用户读取不依赖线程身份() {
        when(mapper.lockExistingUser(7L)).thenReturn(7L);
        var entity = new UserDiningProfileEntity();
        entity.defaultDiningCount = 3;
        when(mapper.findByUserId(7L)).thenReturn(entity);
        assertThat(service.readForUser(7L).defaultDiningCount()).isEqualTo(3);
        verifyNoInteractions(current);
    }

    @Test void 非法内部调用不写入并支持显式清空() {
        when(current.requireUserId()).thenReturn(42L);
        when(mapper.lockExistingUser(42L)).thenReturn(42L);
        for (var request : new SaveDiningProfileRequest[] {null, new SaveDiningProfileRequest(0), new SaveDiningProfileRequest(-1)})
            assertThatThrownBy(() -> service.saveCurrentUser(request)).isInstanceOf(ApiException.class)
                    .satisfies(e -> assertThat(((ApiException)e).code()).isEqualTo("VALIDATION_ERROR"));
        verify(mapper, never()).upsert(anyLong(), any());
        var saved = new UserDiningProfileEntity();
        when(mapper.upsert(42L, null)).thenReturn(saved);
        assertThat(service.saveCurrentUser(new SaveDiningProfileRequest(null)).defaultDiningCount()).isNull();
        verify(mapper).upsert(42L, null);
    }

    private void assertAuth(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException)e).code()).isEqualTo("AUTH_REQUIRED"));
    }
}
