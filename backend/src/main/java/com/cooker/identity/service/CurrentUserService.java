package com.cooker.identity.service;

import com.cooker.identity.vo.UserProfile;

public interface CurrentUserService {
    Long requireUserId();
    UserProfile currentProfile();
}
