package com.aireceptionist.admin;

import com.aireceptionist.AbstractIntegrationTest;
import com.aireceptionist.common.security.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class AdminDashboardTest extends AbstractIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired JwtTokenProvider tokenProvider;
    @Autowired JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clearTenants() {
        // GET /v1/admin/tenants is platform-wide (no tenant scoping), so tenants seeded by one
        // test method are visible to every other test method sharing this class's container.
        // Cascades to leads/knowledge_entries/etc. via their ON DELETE CASCADE FKs to tenants(id).
        jdbcTemplate.update("DELETE FROM tenants");
    }

    private void seedTenants(int count) {
        for (int i = 0; i < count; i++) {
            jdbcTemplate.update(
                    "INSERT INTO tenants (id, business_name, phone_number, tier, status) VALUES (?, ?, ?, 'PRO', 'ACTIVE')",
                    UUID.randomUUID(), "Dashboard Tenant " + i, "+91" + (System.nanoTime() + i) % 10_000_000_000L);
        }
    }

    private String adminToken() throws Exception {
        return tokenProvider.generateToken(UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                "PLATFORM_ADMIN", "PRO");
    }

    @Test
    void listReturnsAllSeededTenants() throws Exception {
        seedTenants(5);

        mockMvc.perform(get("/v1/admin/tenants")
                        .param("size", "20")
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(5))
                .andExpect(jsonPath("$.data.content.length()").value(5));
    }

    @Test
    void listRespondsWithinTwoSecondsFor50Tenants() throws Exception {
        seedTenants(50);
        String token = adminToken();

        long start = System.nanoTime();
        mockMvc.perform(get("/v1/admin/tenants")
                        .param("size", "50")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(50));
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        assertThat(elapsedMillis).isLessThanOrEqualTo(2000);
    }
}
