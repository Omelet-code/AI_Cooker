package com.cooker.identity.service.impl;

import com.cooker.common.ApiException;
import com.cooker.identity.entity.AppUserEntity;
import com.cooker.identity.mapper.AppUserMapper;
import com.cooker.identity.service.CurrentUserService;
import com.cooker.identity.vo.UserProfile;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

@Service
public class CurrentUserServiceImpl implements CurrentUserService {
    private final AppUserMapper users;
    public CurrentUserServiceImpl(AppUserMapper users) { this.users = users; }
    @Override public Long requireUserId() {
        Object principal = SecurityContextHolder.getContext().getAuthentication() == null ? null
                : SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        if (!(principal instanceof Long id)) throw new ApiException(HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED", "请先登录");
        return id;
    }
    @Override public UserProfile currentProfile() {
        AppUserEntity user = users.selectById(requireUserId());
        if (user == null) throw new ApiException(HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED", "请先登录");
        return new UserProfile(user.id.toString(), user.username);
    }
}
