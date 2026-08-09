package com.voicenote.domain;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class OrganizationInvocationStateTest {
    @Test
    void recordsADeterministicFallbackAfterADefinitiveModelFailure() {
        OrganizationInvocation invocation = new OrganizationInvocation("document", "STRUCTURE", "hash");

        invocation.start();
        invocation.fallback();

        assertThat(invocation.getStatus()).isEqualTo(InvocationStatus.FALLBACK);
    }

    @Test
    void doesNotReplaceASuccessfulModelResponseWithFallback() {
        OrganizationInvocation invocation = new OrganizationInvocation("document", "STRUCTURE", "hash");
        invocation.start();
        invocation.succeed("{}");

        invocation.fallback();

        assertThat(invocation.getStatus()).isEqualTo(InvocationStatus.SUCCEEDED);
    }
}
