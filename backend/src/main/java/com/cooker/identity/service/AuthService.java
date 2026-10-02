package com.cooker.identity.service;

import com.cooker.identity.vo.UserProfile;

public interface AuthService {
    UserProfile register(String username, String password);
    Session login(String username, String password, boolean rememberMe);
    Session refresh(String refreshToken);
    void logout(String refreshToken);

    record Session(UserProfile profile, String accessToken, String refreshToken, boolean rememberMe, long refreshMaxAgeSeconds) { }
}
