package com.cooker.conversation;

import com.cooker.common.ApiException;
import org.springframework.http.HttpStatus;

public final class ConversationIdParser {
    private ConversationIdParser() { }
    public static long parse(String value) {
        try {
            if (value == null || !value.matches("[1-9][0-9]*")) throw new NumberFormatException();
            return Long.parseLong(value);
        } catch (NumberFormatException ex) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "标识必须是正整数十进制字符串");
        }
    }
}
