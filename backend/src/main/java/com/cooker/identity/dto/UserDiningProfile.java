package com.cooker.identity.dto;

/** 跨模块只读值；没有设置与显式清空均表达为 null。 */
public record UserDiningProfile(Integer defaultDiningCount) {}
