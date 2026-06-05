package com.aireceptionist.security;

import com.aireceptionist.AbstractIntegrationTest;
import com.aireceptionist.common.security.JwtTokenProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class SecurityIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Test
    void actuatorHealthDoesNotRequireJwt() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }

    @Test
    void protectedEndpointReturnsUnauthorizedWithoutJwt() throws Exception {
        mockMvc.perform(get("/v1/protected"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void protectedEndpointWithValidJwtPassesAuthentication() throws Exception {
        String token = tokenProvider.generateToken("tenant-1", "user-1", "OWNER", "PRO");

        int status = mockMvc.perform(get("/v1/protected").header("Authorization", "Bearer " + token))
                .andReturn()
                .getResponse()
                .getStatus();

        assertThat(status).isNotEqualTo(401);
    }
}
