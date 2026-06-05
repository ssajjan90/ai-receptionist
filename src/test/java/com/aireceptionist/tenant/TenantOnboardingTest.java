package com.aireceptionist.tenant;

import com.aireceptionist.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class TenantOnboardingTest extends AbstractIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    StringRedisTemplate redisTemplate;

    @Autowired
    CapturingOwnerNotificationPort ownerNotificationPort;

    @Test
    void authenticatedActiveTenantCompletesOnboardingAndUpsertsKnowledgeEntries() throws Exception {
        RegisteredTenant tenant = registerAndVerifyTenant();

        onboard(tenant, "20", "9am-9pm")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("ONBOARDING_COMPLETE"))
                .andExpect(jsonPath("$.data.kbEntriesCreated").value(3));

        assertThat(readTenantStatus(tenant.tenantId())).isEqualTo("ONBOARDING_COMPLETE");
        assertThat(countKnowledgeEntries(tenant.tenantId())).isEqualTo(3);
        assertThat(readProductPrice(tenant.tenantId(), "Tea")).isEqualTo("20");

        onboard(tenant, "25", "10am-8pm")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ONBOARDING_COMPLETE"))
                .andExpect(jsonPath("$.data.kbEntriesCreated").value(3));

        assertThat(countKnowledgeEntries(tenant.tenantId())).isEqualTo(3);
        assertThat(readProductPrice(tenant.tenantId(), "Tea")).isEqualTo("25");
        assertThat(readBusinessHours(tenant.tenantId())).isEqualTo("10am-8pm");
    }

    private org.springframework.test.web.servlet.ResultActions onboard(RegisteredTenant tenant,
                                                                       String teaPrice,
                                                                       String businessHours) throws Exception {
        return mockMvc.perform(put("/v1/tenants/{tenantId}/onboarding", tenant.tenantId())
                .header("Authorization", "Bearer " + tenant.jwt())
                .contentType("application/json")
                .accept("application/vnd.aireceptionist.v1+json")
                .content("""
                        {
                          "shopName": "Suresh Stores",
                          "location": "Bengaluru",
                          "businessHours": "%s",
                          "topProducts": [
                            {"productName": "Tea", "price": "%s"},
                            {"productName": "Coffee", "price": "30"}
                          ],
                          "commonFaqs": [
                            {"question": "Do you deliver?", "answer": "Yes"}
                          ]
                        }
                        """.formatted(businessHours, teaPrice)));
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

    private int countKnowledgeEntries(UUID tenantId) {
        return withTenant(tenantId, "SELECT COUNT(*) FROM knowledge_entries", Integer.class);
    }

    private String readProductPrice(UUID tenantId, String productName) {
        return jdbcTemplate.execute((ConnectionCallback<String>) connection -> {
            connection.createStatement().execute("SELECT set_config('app.current_tenant', '" + tenantId + "', false)");
            try (var statement = connection.prepareStatement("""
                    SELECT price FROM knowledge_entries
                    WHERE tenant_id = ? AND entry_type = 'PRODUCT' AND product_name = ?
                    """)) {
                statement.setObject(1, tenantId);
                statement.setString(2, productName);
                var resultSet = statement.executeQuery();
                resultSet.next();
                return resultSet.getString(1);
            }
        });
    }

    private String readTenantStatus(UUID tenantId) {
        return jdbcTemplate.queryForObject("SELECT status FROM tenants WHERE id = ?", String.class, tenantId);
    }

    private String readBusinessHours(UUID tenantId) {
        return jdbcTemplate.queryForObject("SELECT business_hours FROM tenants WHERE id = ?", String.class, tenantId);
    }

    private <T> T withTenant(UUID tenantId, String sql, Class<T> type) {
        return jdbcTemplate.execute((ConnectionCallback<T>) connection -> {
            connection.createStatement().execute("SELECT set_config('app.current_tenant', '" + tenantId + "', false)");
            var resultSet = connection.createStatement().executeQuery(sql);
            resultSet.next();
            return resultSet.getObject(1, type);
        });
    }

    private record RegisteredTenant(UUID tenantId, String jwt) {
    }
}
