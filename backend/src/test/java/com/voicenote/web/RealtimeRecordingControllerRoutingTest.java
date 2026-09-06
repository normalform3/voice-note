package com.voicenote.web;

import com.voicenote.config.AppProperties;
import com.voicenote.security.RealtimeRecordingTicketService;
import com.voicenote.service.RealtimeRecordingService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RealtimeRecordingControllerRoutingTest {
    @Test
    void socketPathIsNotClaimedAsARecordingSessionId() throws Exception {
        RealtimeRecordingService recordings = mock(RealtimeRecordingService.class);
        RealtimeRecordingController controller = new RealtimeRecordingController(
                recordings, mock(RealtimeRecordingTicketService.class), new AppProperties());
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        mvc.perform(get("/api/realtime-recordings/socket"))
                .andExpect(status().isNotFound());

        verifyNoInteractions(recordings);
    }
}
