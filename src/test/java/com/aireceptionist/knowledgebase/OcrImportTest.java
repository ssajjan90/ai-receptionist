package com.aireceptionist.knowledgebase;

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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class OcrImportTest extends AbstractIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    StringRedisTemplate redisTemplate;

    @Autowired
    CapturingOwnerNotificationPort ownerNotificationPort;

    @Test
    void confirmOcrImportUpsertsProductEntriesWithOcrSource() throws Exception {
        RegisteredTenant tenant = registerAndVerifyTenant();

        confirm(tenant, "20")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.entriesSaved").value(2));

        assertThat(countOcrProducts(tenant.tenantId())).isEqualTo(2);
        assertThat(readOcrProductPrice(tenant.tenantId(), "Tea")).isEqualTo("20");

        confirm(tenant, "25")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.entriesSaved").value(2));

        assertThat(countOcrProducts(tenant.tenantId())).isEqualTo(2);
        assertThat(readOcrProductPrice(tenant.tenantId(), "Tea")).isEqualTo("25");
    }

    private org.springframework.test.web.servlet.ResultActions confirm(RegisteredTenant tenant, String teaPrice) throws Exception {
        return mockMvc.perform(post("/v1/tenants/{tenantId}/knowledge-base/ocr-import/confirm", tenant.tenantId())
                .header("Authorization", "Bearer " + tenant.jwt())
                .contentType("application/json")
                .accept("application/vnd.aireceptionist.v1+json")
                .content("""
                        {
                          "entries": [
                            {"productName": "Tea", "price": "%s"},
                            {"productName": "Coffee", "price": "30"}
                          ]
                        }
                        """.formatted(teaPrice)));
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

    private int countOcrProducts(UUID tenantId) {
        return jdbcTemplate.execute((ConnectionCallback<Integer>) connection -> {
            connection.createStatement().execute("SELECT set_config('app.current_tenant', '" + tenantId + "', false)");
            var resultSet = connection.createStatement().executeQuery("""
                    SELECT COUNT(*) FROM knowledge_entries
                    WHERE tenant_id = '%s' AND entry_type = 'PRODUCT' AND source = 'OCR'
                    """.formatted(tenantId));
            resultSet.next();
            return resultSet.getInt(1);
        });
    }

    private String readOcrProductPrice(UUID tenantId, String productName) {
        return jdbcTemplate.execute((ConnectionCallback<String>) connection -> {
            connection.createStatement().execute("SELECT set_config('app.current_tenant', '" + tenantId + "', false)");
            try (var statement = connection.prepareStatement("""
                    SELECT price FROM knowledge_entries
                    WHERE tenant_id = ? AND entry_type = 'PRODUCT' AND source = 'OCR' AND product_name = ?
                    """)) {
                statement.setObject(1, tenantId);
                statement.setString(2, productName);
                var resultSet = statement.executeQuery();
                resultSet.next();
                return resultSet.getString(1);
            }
        });
    }

    private record RegisteredTenant(UUID tenantId, String jwt) {
    }
}
