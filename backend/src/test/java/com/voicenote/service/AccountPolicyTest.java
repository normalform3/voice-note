package com.voicenote.service;

import com.voicenote.web.ApiException;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccountPolicyTest {
    @Test
    void keepsCaseSignificantAccountValuesValid() {
        AccountPolicy.requireValidAccount("Alice");
        AccountPolicy.requireValidAccount("alice");
    }

    @Test
    void rejectsAnyWhitespaceButAllowsPasswordCharacters() {
        assertThatThrownBy(() -> AccountPolicy.requireValidAccount("alice smith")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> AccountPolicy.requireValidAccount("alice\t42")).isInstanceOf(ApiException.class);
        assertThat(AccountPolicy.passwordDigest(" 密码 with spaces and symbols !@# ")).hasSize(64);
    }
}
