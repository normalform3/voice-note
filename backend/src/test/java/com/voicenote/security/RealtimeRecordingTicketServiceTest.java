package com.voicenote.security;

import com.voicenote.config.AppProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RealtimeRecordingTicketServiceTest {
    @Test
    void bindsShortLivedTicketToItsOwnerAndRecordingSession() {
        AppProperties properties = properties();
        RealtimeRecordingTicketService service = new RealtimeRecordingTicketService(properties);
        RealtimeRecordingTicketService.Ticket ticket = service.issue("owner-1", "session-1");

        assertThat(service.parse(ticket.value())).isEqualTo(new RealtimeRecordingTicketService.TicketClaims("owner-1", "session-1"));
        assertThat(ticket.expiresAt()).isAfter(java.time.Instant.now()).isBefore(java.time.Instant.now().plusSeconds(31));
    }

    @Test
    void cannotUseAnOrdinaryLoginJwtAsARealtimeTicket() {
        AppProperties properties = properties();
        String loginToken = new JwtService(properties).issue("owner-1", "account");

        assertThatThrownBy(() -> new RealtimeRecordingTicketService(properties).parse(loginToken))
                .isInstanceOf(RuntimeException.class);
    }

    private static AppProperties properties() {
        AppProperties properties = new AppProperties();
        properties.getSecurity().setJwtSecret("test-secret-with-at-least-thirty-two-bytes-long");
        properties.getRealtimeAsr().setTicketTtlSeconds(30);
        return properties;
    }
}
