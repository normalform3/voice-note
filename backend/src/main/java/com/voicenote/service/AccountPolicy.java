package com.voicenote.service;

import com.voicenote.web.ApiException;
import org.springframework.http.HttpStatus;

public final class AccountPolicy {
    private AccountPolicy() { }
    public static void requireValidAccount(String account) {
        if (account == null || account.isEmpty() || account.codePoints().anyMatch(value -> Character.isWhitespace(value) || Character.isSpaceChar(value))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ACCOUNT", "Account cannot be empty or contain whitespace");
        }
    }
    public static String passwordDigest(String password) { return Hashing.sha256(password); }
}
