package com.aireceptionist.tenant;

import com.aireceptionist.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.sql.PreparedStatement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class TenantRegistrationTest extends AbstractIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    StringRedisTemplate redisTemplate;

    @Autowired
    CapturingOwnerNotificationPort ownerNotificationPort;

    @Test
    void registerAndVerifyOtpCreatesActiveTenantJwtAndSubscription() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String ownerPhone = "+9191" + suffix.substring(0, 8);
        String businessPhone = "+9192" + suffix.substring(0, 8);
        String email = "owner-" + suffix + "@example.com";

        mockMvc.perform(post("/v1/tenants/register")
                        .contentType("application/json")
                        .accept("application/vnd.aireceptionist.v1+json")
                        .content("""
                                {
                                  "businessName": "Suresh Stores",
                                  "ownerName": "Suresh",
                                  "ownerPhone": "%s",
                                  "businessPhone": "%s",
                                  "email": "%s",
                                  "password": "password123"
                                }
                                """.formatted(ownerPhone, businessPhone, email)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("PENDING_VERIFICATION"));

        Integer pendingCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tenants WHERE email = ? AND status = 'PENDING_VERIFICATION'",
                Integer.class,
                email
        );
        assertThat(pendingCount).isEqualTo(1);

        String otp = ownerNotificationPort.otpFor(ownerPhone);
        assertThat(otp).matches("\\d{6}");
        assertThat(redisTemplate.opsForValue().get("otp:" + ownerPhone)).isNotEqualTo(otp);

        mockMvc.perform(post("/v1/tenants/verify-otp")
                        .contentType("application/json")
                        .accept("application/vnd.aireceptionist.v1+json")
                        .content("""
                                {
                                  "ownerPhone": "%s",
                                  "otp": "%s"
                                }
                                """.formatted(ownerPhone, otp)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.jwt").isNotEmpty())
                .andExpect(jsonPath("$.data.role").value("OWNER"))
                .andExpect(jsonPath("$.data.tier").value("BASIC"));

        UUID tenantId = jdbcTemplate.queryForObject(
                "SELECT id FROM tenants WHERE email = ? AND status = 'ACTIVE'",
                UUID.class,
                email
        );
        assertThat(tenantId).isNotNull();

        // subscriptions carries RLS (V8/W99): an unscoped query sees zero rows regardless of what
        // was provisioned, so app.current_tenant must be set on this connection first.
        UUID scopedTenantId = tenantId;
        Long subscriptionCount = jdbcTemplate.execute((ConnectionCallback<Long>) connection -> {
            try {
                connection.createStatement().execute(
                        "SELECT set_config('app.current_tenant', '" + scopedTenantId + "', false)");
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT COUNT(*) FROM subscriptions WHERE tenant_id = ? AND tier = 'BASIC' AND status = 'ACTIVE'")) {
                    statement.setObject(1, scopedTenantId);
                    try (var resultSet = statement.executeQuery()) {
                        resultSet.next();
                        return resultSet.getLong(1);
                    }
                }
            } finally {
                connection.createStatement().execute("RESET app.current_tenant");
            }
        });
        assertThat(subscriptionCount).isEqualTo(1L);
        assertThat(redisTemplate.opsForValue().get("otp:" + ownerPhone)).isNull();
    }
}
