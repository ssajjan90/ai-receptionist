package com.aireceptionist.whatsapp;

import com.aireceptionist.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class WhatsAppConnectionTest extends AbstractIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    StringRedisTemplate redisTemplate;

    @Autowired
    CapturingOwnerNotificationPort ownerNotificationPort;

    @Test
    void onboardedTenantConnectsWhatsAppAndDuplicatePhoneNumberIsRejected() throws Exception {
        RegisteredTenant firstTenant = registerVerifyAndOnboardTenant();
        RegisteredTenant secondTenant = registerVerifyAndOnboardTenant();

        connect(firstTenant, "WABA_1", "PHONE_1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("LIVE"))
                .andExpect(jsonPath("$.data.phoneNumberId").value("PHONE_1"));

        assertThat(readTenantStatus(firstTenant.tenantId())).isEqualTo("LIVE");
        assertThat(readPhoneNumberId(firstTenant.tenantId())).isEqualTo("PHONE_1");
        assertThat(readWhatsappConnectedAt(firstTenant.tenantId())).isNotNull();

        connect(secondTenant, "WABA_2", "PHONE_1")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("PHONE_ALREADY_REGISTERED"));
    }

    private RegisteredTenant registerVerifyAndOnboardTenant() throws Exception {
        RegisteredTenant tenant = registerAndVerifyTenant();
        mockMvc.perform(put("/v1/tenants/{tenantId}/onboarding", tenant.tenantId())
                        .header("Authorization", "Bearer " + tenant.jwt())
                        .contentType("application/json")
                        .accept("application/vnd.aireceptionist.v1+json")
                        .content("""
                                {
                                  "shopName": "Suresh Stores",
                                  "location": "Bengaluru",
                                  "businessHours": "9am-9pm",
                                  "topProducts": [
                                    {"productName": "Tea", "price": "20"}
                                  ],
                                  "commonFaqs": []
                                }
                                """))
                .andExpect(status().isOk());
        return tenant;
    }

    private org.springframework.test.web.servlet.ResultActions connect(RegisteredTenant tenant,
                                                                       String wabaId,
                                                                       String phoneNumberId) throws Exception {
        return mockMvc.perform(post("/v1/tenants/{tenantId}/whatsapp/connect", tenant.tenantId())
                .header("Authorization", "Bearer " + tenant.jwt())
                .contentType("application/json")
                .accept("application/vnd.aireceptionist.v1+json")
                .content("""
                        {
                          "wabaId": "%s",
                          "phoneNumberId": "%s",
                          "displayPhoneNumber": "+91 98765 43210"
                        }
                        """.formatted(wabaId, phoneNumberId)));
    }

    private RegisteredTenant registerAndVerifyTenant() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String ownerPhone = "+9191" + suffix;
        String businessPhone = "+9192" + suffix;
        String email = "owner-" + suffix + "@example.com";

        mockMvc.perform(post("/v1/tenants/register")
                        .contentType("application/json")
                        .accept("application/vnd.aireceptionist.v1+json")
                        .content("""
                                {
                                  "businessName": "Starter Shop",
                                  "ownerName": "Suresh",
                                  "ownerPhone": "%s",
                                  "businessPhone": "%s",
                                  "email": "%s",
                                  "password": "password123"
                                }
                                """.formatted(ownerPhone, businessPhone, email)))
                .andExpect(status().isCreated());

        String otp = ownerNotificationPort.otpFor(ownerPhone);
        assertThat(redisTemplate.opsForValue().get("otp:" + ownerPhone)).isNotEqualTo(otp);

        String jwt = mockMvc.perform(post("/v1/tenants/verify-otp")
                        .contentType("application/json")
                        .accept("application/vnd.aireceptionist.v1+json")
                        .content("""
                                {
                                  "ownerPhone": "%s",
                                  "otp": "%s"
                                }
                                """.formatted(ownerPhone, otp)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString()
                .replaceAll(".*\"jwt\":\"([^\"]+)\".*", "$1");

        UUID tenantId = jdbcTemplate.queryForObject(
                "SELECT id FROM tenants WHERE email = ?",
                UUID.class,
                email
        );
        return new RegisteredTenant(tenantId, jwt);
    }

    private String readTenantStatus(UUID tenantId) {
        return jdbcTemplate.queryForObject("SELECT status FROM tenants WHERE id = ?", String.class, tenantId);
    }

    private String readPhoneNumberId(UUID tenantId) {
        return jdbcTemplate.queryForObject("SELECT phone_number_id FROM tenants WHERE id = ?", String.class, tenantId);
    }

    private java.time.Instant readWhatsappConnectedAt(UUID tenantId) {
        return jdbcTemplate.queryForObject("SELECT whatsapp_connected_at FROM tenants WHERE id = ?", java.time.Instant.class, tenantId);
    }

    private record RegisteredTenant(UUID tenantId, String jwt) {
    }
}
