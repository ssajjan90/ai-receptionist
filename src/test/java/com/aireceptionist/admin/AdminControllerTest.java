package com.aireceptionist.admin;

import com.aireceptionist.AbstractIntegrationTest;
import com.aireceptionist.common.security.JwtTokenProvider;
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
class AdminControllerTest extends AbstractIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired JwtTokenProvider tokenProvider;
    @Autowired JdbcTemplate jdbcTemplate;

    private UUID seedTenant() {
        UUID tenantId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO tenants (id, business_name, phone_number, tier, status) VALUES (?, ?, ?, 'PRO', 'ACTIVE')",
                tenantId, "Admin Test Business", "+91" + System.nanoTime() % 10_000_000_000L);
        return tenantId;
    }

    @Test
    void ownerRoleIsForbiddenFromAdminEndpoints() throws Exception {
        UUID tenantId = seedTenant();
        String ownerToken = tokenProvider.generateToken(tenantId.toString(), tenantId.toString(), "OWNER", "PRO");

        // Asserts the response body too, not just the status: role enforcement lives in
        // @PreAuthorize (not a SecurityConfig requestMatcher), specifically so this 403 flows
        // through GlobalExceptionHandler and carries the app's standard ApiResponse envelope
        // rather than Spring Security's raw default body (see code review of story 5-1, 2026-09-01).
        mockMvc.perform(get("/v1/admin/tenants").header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));
    }

    @Test
    void nonAdminAccessIsNotWrittenToAdminAccessLog() throws Exception {
        UUID tenantId = seedTenant();
        String ownerToken = tokenProvider.generateToken(tenantId.toString(), tenantId.toString(), "OWNER", "PRO");

        mockMvc.perform(get("/v1/admin/tenants").header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isForbidden());

        // Scoped to this owner's own id (never used as admin_user_id by any other test, unlike
        // the shared "/v1/admin/tenants" action string every successful admin test also writes)
        // so this doesn't false-fail from cross-test pollution in a class with no log cleanup.
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM admin_access_log WHERE admin_user_id = ?", Long.class, tenantId);
        assertThat(count).isEqualTo(0L);
    }

    @Test
    void adminAccessToNonExistentTenantIsStillAudited() throws Exception {
        UUID adminUserId = UUID.randomUUID();
        String adminToken = tokenProvider.generateToken(UUID.randomUUID().toString(), adminUserId.toString(),
                "PLATFORM_ADMIN", "PRO");
        UUID missingTenantId = UUID.randomUUID();

        mockMvc.perform(get("/v1/admin/tenants/{tenantId}", missingTenantId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNotFound());

        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM admin_access_log WHERE admin_user_id = ? AND target_tenant_id = ? AND event_type = 'ADMIN_ACCESS'",
                Long.class, adminUserId, missingTenantId);
        assertThat(count).isEqualTo(1L);
    }

    @Test
    void platformAdminCanListTenants() throws Exception {
        seedTenant();
        UUID adminUserId = UUID.randomUUID();
        String adminToken = tokenProvider.generateToken(UUID.randomUUID().toString(), adminUserId.toString(),
                "PLATFORM_ADMIN", "PRO");

        mockMvc.perform(get("/v1/admin/tenants").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
    }

    @Test
    void adminAccessIsWrittenToAdminAccessLog() throws Exception {
        seedTenant();
        UUID adminUserId = UUID.randomUUID();
        String adminToken = tokenProvider.generateToken(UUID.randomUUID().toString(), adminUserId.toString(),
                "PLATFORM_ADMIN", "PRO");

        mockMvc.perform(get("/v1/admin/tenants").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());

        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM admin_access_log WHERE admin_user_id = ? AND event_type = 'ADMIN_ACCESS'",
                Long.class, adminUserId);
        assertThat(count).isEqualTo(1L);
    }
}
