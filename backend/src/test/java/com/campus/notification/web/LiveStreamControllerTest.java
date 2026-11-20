package com.campus.notification.web;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.campus.common.error.ConflictException;
import com.campus.common.security.CurrentUser;
import com.campus.config.SecurityConfig;
import com.campus.notification.service.LiveStreams;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@WebMvcTest(LiveStreamController.class)
@Import(SecurityConfig.class)
class LiveStreamControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private LiveStreams liveStreams;

    @MockitoBean
    private CurrentUser currentUser;

    @Test
    void requiresLogin() throws Exception {
        mockMvc.perform(get("/api/v1/live/stream"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        verifyNoInteractions(liveStreams);
    }

    @Test
    @WithMockUser(roles = "STUDENT")
    void opensAStreamForTheLoggedInUser() throws Exception {
        when(currentUser.id()).thenReturn(12L);
        when(liveStreams.open(12L)).thenReturn(new SseEmitter(60_000L));

        mockMvc.perform(get("/api/v1/live/stream"))
                .andExpect(request().asyncStarted())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));

        verify(liveStreams).open(12L);
    }

    @Test
    @WithMockUser(roles = "STUDENT")
    void limitIs409() throws Exception {
        when(currentUser.id()).thenReturn(12L);
        when(liveStreams.open(12L))
                .thenThrow(new ConflictException("LIVE_STREAM_LIMIT", "Too many open tabs for this account"));

        mockMvc.perform(get("/api/v1/live/stream"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LIVE_STREAM_LIMIT"));
    }
}
