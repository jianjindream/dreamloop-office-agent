package com.jianjin.assistant.interfaces.http.controller;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class FrontendRouteControllerTest {

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new FrontendRouteController()).build();

    @Test
    void forwardsVueHistoryRoutesToFrontend() throws Exception {
        for (String route : new String[]{"/chat", "/knowledge", "/documents", "/tools", "/status", "/settings"}) {
            mvc.perform(get(route))
                    .andExpect(status().isOk())
                    .andExpect(forwardedUrl("/index.html"));
        }
    }
}
