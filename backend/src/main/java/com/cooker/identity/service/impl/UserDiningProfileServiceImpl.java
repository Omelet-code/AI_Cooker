package com.cooker.identity.service.impl;

import com.cooker.common.ApiException;
import com.cooker.identity.dto.SaveDiningProfileRequest;
import com.cooker.identity.dto.UserDiningProfile;
import com.cooker.identity.mapper.UserDiningProfileMapper;
import com.cooker.identity.service.CurrentUserService;
import com.cooker.identity.service.UserDiningProfileService;
import com.cooker.identity.vo.DiningProfileView;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserDiningProfileServiceImpl implements UserDiningProfileService {
    private final CurrentUserService currentUser;
    private final UserDiningProfileMapper profiles;

    public UserDiningProfileServiceImpl(CurrentUserService currentUser, UserDiningProfileMapper profiles) {
        this.currentUser = currentUser;
        this.profiles = profiles;
    }

    @Override @Transactional
    public DiningProfileView currentUserProfile() {
        return new DiningProfileView(readVerifiedUser(currentUser.requireUserId()).defaultDiningCount());
    }

    @Override @Transactional
    public DiningProfileView saveCurrentUser(SaveDiningProfileRequest request) {
        long userId = currentUser.requireUserId();
        requireExistingUser(userId);
        // HTTP与内部调用都守住业务边界，null是显式清空而不是缺省值。
        if (request == null || (request.defaultDiningCount() != null && request.defaultDiningCount() <= 0))
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "请求参数不合法");
        var saved = profiles.upsert(userId, request.defaultDiningCount());
        // RETURNING返回本次写入值，不额外查询可能已经被下一并发写覆盖的值。
        return new DiningProfileView(saved.defaultDiningCount);
    }

    @Override @Transactional
    public UserDiningProfile readForUser(long userId) {
        // 显式用户接口不读取线程SecurityContext，支持未来已验证归属的后台任务。
        return readVerifiedUser(userId);
    }

    private UserDiningProfile readVerifiedUser(long userId) {
        requireExistingUser(userId);
        var entity = profiles.findByUserId(userId);
        return new UserDiningProfile(entity == null ? null : entity.defaultDiningCount);
    }

    private void requireExistingUser(long userId) {
        if (userId <= 0 || profiles.lockExistingUser(userId) == null)
            throw new ApiException(HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED", "请先登录");
    }
}
