package com.aireceptionist.leads;

import com.aireceptionist.AbstractIntegrationTest;
import com.aireceptionist.common.security.JwtTokenProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class LeadListPerformanceTest extends AbstractIntegrationTest {

    private static final int ITERATIONS = 5;

    @Autowired MockMvc mockMvc;
    @Autowired JwtTokenProvider tokenProvider;
    @Autowired JdbcTemplate jdbcTemplate;

    private UUID seedTenantWith1000Leads() {
        UUID tenantId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO tenants (id, business_name, phone_number, tier, status) VALUES (?, ?, ?, 'PRO', 'ACTIVE')",
                tenantId, "Perf Test Business", "+91" + System.nanoTime() % 10_000_000_000L);

        List<Object[]> rows = new ArrayList<>(1000);
        Instant now = Instant.now();
        for (int i = 0; i < 1000; i++) {
            rows.add(new Object[]{
                    UUID.randomUUID(), tenantId, "Customer " + i, "+9199" + String.format("%08d", i),
                    "Product " + i, "WHATSAPP", "NEW", Timestamp.from(now), "WHATSAPP",
                    Timestamp.from(now.minusSeconds(i)), Timestamp.from(now.minusSeconds(i))
            });
        }
        jdbcTemplate.batchUpdate(
                "INSERT INTO leads (id, tenant_id, name, phone, intent, channel, status, consent_timestamp, " +
                        "consent_channel, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                rows);
        return tenantId;
    }

    private long averageResponseTimeMs(String url, String token) throws Exception {
        // one untimed warm-up call to exclude JIT/connection-pool/first-query overhead,
        // then average over several timed calls to reduce wall-clock flakiness
        mockMvc.perform(get(url).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        long totalMs = 0;
        for (int i = 0; i < ITERATIONS; i++) {
            long start = System.currentTimeMillis();
            mockMvc.perform(get(url).header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk());
            totalMs += System.currentTimeMillis() - start;
        }
        return totalMs / ITERATIONS;
    }

    @Test
    void listLeadsRespondsWithin500msFor1000Leads() throws Exception {
        UUID tenantId = seedTenantWith1000Leads();
        String token = tokenProvider.generateToken(tenantId.toString(), tenantId.toString(), "OWNER", "PRO");

        long averageMs = averageResponseTimeMs("/v1/tenants/" + tenantId + "/leads?size=20", token);

        assertThat(averageMs).isLessThanOrEqualTo(500);
    }

    @Test
    void listLeadsFilteredByStatusRespondsWithin500msFor1000Leads() throws Exception {
        UUID tenantId = seedTenantWith1000Leads();
        String token = tokenProvider.generateToken(tenantId.toString(), tenantId.toString(), "OWNER", "PRO");

        long averageMs = averageResponseTimeMs("/v1/tenants/" + tenantId + "/leads?status=NEW&size=20", token);

        assertThat(averageMs).isLessThanOrEqualTo(500);
    }
}
