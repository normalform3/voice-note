package com.voicenote.config;

import com.voicenote.security.JwtService;
import com.voicenote.service.AnalysisService;
import com.voicenote.service.KnowledgeAgentService;
import com.voicenote.service.KnowledgeDocumentService;
import com.voicenote.service.PipelineProgressService;
import com.voicenote.web.ProgressEventsController;
import com.voicenote.web.ProgressSseHub;
import jakarta.servlet.DispatcherType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.web.FilterChainProxy;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

@WebMvcTest(controllers = ProgressEventsController.class)
@Import(SecurityConfiguration.class)
class SecurityConfigurationTest {
    @Autowired
    private FilterChainProxy springSecurityFilterChain;

    @MockBean
    private JwtService jwtService;

    @MockBean
    private ProgressSseHub hub;

    @MockBean
    private PipelineProgressService pipeline;

    @MockBean
    private KnowledgeDocumentService documents;

    @MockBean
    private AnalysisService analyses;

    @MockBean
    private KnowledgeAgentService knowledge;

    @Test
    void permitsInternalAsyncDispatchWithoutRequiringTheBearerHeaderAgain() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/progress-events");
        request.setDispatcherType(DispatcherType.ASYNC);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean downstreamInvoked = new AtomicBoolean();

        springSecurityFilterChain.doFilter(request, response,
                (ignoredRequest, ignoredResponse) -> downstreamInvoked.set(true));

        assertThat(downstreamInvoked).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
    }
}
