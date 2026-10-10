package com.cooker.identity.service;

/** 身份模块提供的访问令牌能力；调用方不依赖签名器的具体实现。 */
public interface JwtService {
    String issue(Long userId);
    Long verify(String raw);
}
