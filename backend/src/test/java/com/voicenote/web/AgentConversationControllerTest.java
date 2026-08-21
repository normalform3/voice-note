package com.voicenote.web;

import com.voicenote.domain.AgentConversationStatus;
import com.voicenote.domain.AgentScopeType;
import com.voicenote.domain.ConversationSummaryStatus;
import com.voicenote.security.UserPrincipal;
import com.voicenote.service.AgentConversationService;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.Authentication;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentConversationControllerTest {
    @Test
    void exposesAStableConversationPageShape() {
        AgentConversationService service = mock(AgentConversationService.class);
        AgentConversationService.ConversationView conversation = new AgentConversationService.ConversationView(
                "conversation-id", "面试复盘", AgentConversationStatus.ACTIVE, AgentScopeType.ALL_DOCUMENTS,
                "Asia/Shanghai", "auto", "pending", true, ConversationSummaryStatus.IDLE, null,
                Instant.parse("2026-08-21T01:00:00Z"), Instant.parse("2026-08-21T01:00:00Z"));
        when(service.list("user-id", 1, 10)).thenReturn(new PageImpl<>(List.of(conversation), PageRequest.of(1, 10), 21));
        Authentication authentication = mock(Authentication.class);
        when(authentication.getPrincipal()).thenReturn(new UserPrincipal("user-id", "user@example.com"));

        AgentConversationController.ConversationPage page =
                new AgentConversationController(service).list(1, 10, authentication);

        assertThat(page.content()).containsExactly(conversation);
        assertThat(page.totalElements()).isEqualTo(21);
        assertThat(page.totalPages()).isEqualTo(3);
        assertThat(page.number()).isEqualTo(1);
        assertThat(page.size()).isEqualTo(10);
    }
}
