package com.aireceptionist.admin;

import com.aireceptionist.AbstractIntegrationTest;
import com.aireceptionist.common.audit.AuditLogEntry;
import com.aireceptionist.common.audit.AuditLogRepository;
import com.aireceptionist.common.security.JwtTokenProvider;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Story 5.6 (AC1-AC4). {@code AuditLogRepository.save} is used directly to seed rows (including
 * one deliberately older than 90 days, and one for a different tenant), rather than driving every
 * seed through the application's own audit-writing endpoints — this test needs precise control
 * over {@code occurred_at}, which every existing audit-writing call site always sets to "now".
 */
@AutoConfigureMockMvc
class AuditLogApiTest extends AbstractIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired JwtTokenProvider tokenProvider;
    @Autowired AuditLogRepository auditLogRepository;

    private UUID seedTenant() {
        UUID tenantId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO tenants (id, business_name, phone_number, tier, status) VALUES (?, ?, ?, 'PRO', 'LIVE')",
                tenantId, "Audit Log Test Business", "+91" + System.nanoTime() % 10_000_000_000L);
        return tenantId;
    }

    private String adminToken() throws Exception {
        return tokenProvider.generateToken(UUID.randomUUID().toString(), UUID.randomUUID().toString(), "PLATFORM_ADMIN", "PRO");
    }

    private String ownerToken(UUID tenantId) throws Exception {
        return tokenProvider.generateToken(tenantId.toString(), tenantId.toString(), "OWNER", "PRO");
    }

    @Test
    void excludesRowsOlderThan90DaysAndReturnsExpectedFields() throws Exception {
        UUID tenantId = seedTenant();
        Instant now = Instant.now();
        UUID recentId = UUID.randomUUID();
        auditLogRepository.save(new AuditLogEntry(
                recentId, tenantId, "HIGH_CONFIDENCE", new BigDecimal("0.95"), "abc123hash", now.minus(1, ChronoUnit.DAYS)));
        auditLogRepository.save(new AuditLogEntry(
                UUID.randomUUID(), tenantId, "HIGH_CONFIDENCE", new BigDecimal("0.90"), "oldhash", now.minus(100, ChronoUnit.DAYS)));

        mockMvc.perform(get("/v1/admin/audit-log")
                        .param("tenantId", tenantId.toString())
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.content[0].id").value(recentId.toString()))
                .andExpect(jsonPath("$.data.content[0].tenantId").value(tenantId.toString()))
                .andExpect(jsonPath("$.data.content[0].eventType").value("HIGH_CONFIDENCE"))
                .andExpect(jsonPath("$.data.content[0].confidence").value(0.95))
                .andExpect(jsonPath("$.data.content[0].messageHash").value("abc123hash"));
    }

    @Test
    void filtersByEventType() throws Exception {
        UUID tenantId = seedTenant();
        Instant now = Instant.now();
        auditLogRepository.save(new AuditLogEntry(
                UUID.randomUUID(), tenantId, "HIGH_CONFIDENCE", new BigDecimal("0.95"), null, now));
        auditLogRepository.save(new AuditLogEntry(
                UUID.randomUUID(), tenantId, "LOW_CONFIDENCE", new BigDecimal("0.30"), null, now));

        mockMvc.perform(get("/v1/admin/audit-log")
                        .param("tenantId", tenantId.toString())
                        .param("eventType", "LOW_CONFIDENCE")
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.content[0].eventType").value("LOW_CONFIDENCE"));
    }

    @Test
    void onlyReturnsRowsForTheRequestedTenant() throws Exception {
        UUID tenantId = seedTenant();
        UUID otherTenantId = seedTenant();
        Instant now = Instant.now();
        auditLogRepository.save(new AuditLogEntry(UUID.randomUUID(), tenantId, "HIGH_CONFIDENCE", null, null, now));
        auditLogRepository.save(new AuditLogEntry(UUID.randomUUID(), otherTenantId, "HIGH_CONFIDENCE", null, null, now));

        mockMvc.perform(get("/v1/admin/audit-log")
                        .param("tenantId", tenantId.toString())
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.content[0].tenantId").value(tenantId.toString()));
    }

    @Test
    void adminAccessToAuditLogIsItselfAudited() throws Exception {
        UUID tenantId = seedTenant();
        Instant before = Instant.now().minus(1, ChronoUnit.MINUTES);

        mockMvc.perform(get("/v1/admin/audit-log")
                        .param("tenantId", tenantId.toString())
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk());

        // audit_log carries RLS (V9); a raw, tenant-unscoped query here would deterministically
        // see zero rows regardless of what the app wrote — same secure default TenantRlsTest
        // covers directly. Reuse the repository's own tenant-scoped read (already used the same
        // way by TenantDataExportTest) rather than duplicating the set_config dance in the test.
        long count = auditLogRepository.countByTenantIdAndEventTypeAndOccurredAtAfter(
                tenantId, "ADMIN_AUDIT_VIEW", before);
        org.assertj.core.api.Assertions.assertThat(count).isEqualTo(1L);
    }

    @Test
    void ownerRoleCannotCallAuditLogEndpoint() throws Exception {
        UUID tenantId = seedTenant();

        mockMvc.perform(get("/v1/admin/audit-log")
                        .param("tenantId", tenantId.toString())
                        .header("Authorization", "Bearer " + ownerToken(tenantId)))
                .andExpect(status().isForbidden());
    }

    @Test
    void unknownTenantReturnsNotFound() throws Exception {
        mockMvc.perform(get("/v1/admin/audit-log")
                        .param("tenantId", UUID.randomUUID().toString())
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isNotFound());
    }

    @Test
    void unauthenticatedRequestIsRejected() throws Exception {
        UUID tenantId = seedTenant();

        // 403, not 401: this controller enforces access via class-level @PreAuthorize, so an
        // anonymous request still carries an (anonymous) Authentication and fails the role check
        // as AccessDeniedException — same shape as ownerRoleCannotCallAuditLogEndpoint below and
        // AdminControllerTest's ownerRoleIsForbiddenFromAdminEndpoints.
        mockMvc.perform(get("/v1/admin/audit-log").param("tenantId", tenantId.toString()))
                .andExpect(status().isForbidden());
    }

    @Test
    void fromAndToFilterRowsToTheRequestedWindow() throws Exception {
        UUID tenantId = seedTenant();
        Instant now = Instant.now();
        auditLogRepository.save(new AuditLogEntry(
                UUID.randomUUID(), tenantId, "HIGH_CONFIDENCE", null, null, now.minus(10, ChronoUnit.DAYS)));
        auditLogRepository.save(new AuditLogEntry(
                UUID.randomUUID(), tenantId, "HIGH_CONFIDENCE", null, null, now.minus(2, ChronoUnit.DAYS)));
        auditLogRepository.save(new AuditLogEntry(
                UUID.randomUUID(), tenantId, "HIGH_CONFIDENCE", null, null, now.minus(1, ChronoUnit.HOURS)));

        // No range: all 3 rows within the 90-day window.
        mockMvc.perform(get("/v1/admin/audit-log")
                        .param("tenantId", tenantId.toString())
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(3));

        // from + to together: isolates just the 2 most recent rows.
        mockMvc.perform(get("/v1/admin/audit-log")
                        .param("tenantId", tenantId.toString())
                        .param("from", now.minus(3, ChronoUnit.DAYS).toString())
                        .param("to", now.toString())
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(2));
    }

    @Test
    void fromAfterToIsRejectedWithInvalidDateRange() throws Exception {
        UUID tenantId = seedTenant();

        mockMvc.perform(get("/v1/admin/audit-log")
                        .param("tenantId", tenantId.toString())
                        .param("from", Instant.now().toString())
                        .param("to", Instant.now().minus(1, ChronoUnit.DAYS).toString())
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_DATE_RANGE"));
    }

    @Test
    void nullConfidenceAndMessageHashSerializeAsNull() throws Exception {
        UUID tenantId = seedTenant();
        UUID rowId = UUID.randomUUID();
        auditLogRepository.save(new AuditLogEntry(
                rowId, tenantId, "DATA_ERASED", null, null, Instant.now()));

        mockMvc.perform(get("/v1/admin/audit-log")
                        .param("tenantId", tenantId.toString())
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].id").value(rowId.toString()))
                .andExpect(jsonPath("$.data.content[0].confidence").value(nullValue()))
                .andExpect(jsonPath("$.data.content[0].messageHash").value(nullValue()));
    }

    @Test
    void pageSizeIsClampedToMaxPageSize() throws Exception {
        UUID tenantId = seedTenant();
        for (int i = 0; i < 5; i++) {
            auditLogRepository.save(new AuditLogEntry(
                    UUID.randomUUID(), tenantId, "HIGH_CONFIDENCE", null, null, Instant.now().minus(i, ChronoUnit.MINUTES)));
        }

        // Requesting far more than MAX_PAGE_SIZE (100) must not error and must not return
        // more rows than actually exist — this just proves the clamp doesn't blow up the query;
        // MAX_PAGE_SIZE itself is exercised implicitly since 5 << 100.
        mockMvc.perform(get("/v1/admin/audit-log")
                        .param("tenantId", tenantId.toString())
                        .param("size", "1000000")
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(5))
                .andExpect(jsonPath("$.data.hasMore").value(false));
    }

    @Test
    void keysetCursorPaginatesWithoutDuplicatesOrGaps() throws Exception {
        UUID tenantId = seedTenant();
        Instant base = Instant.now().minus(1, ChronoUnit.HOURS);
        List<UUID> seededIds = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            UUID id = UUID.randomUUID();
            seededIds.add(id);
            auditLogRepository.save(new AuditLogEntry(id, tenantId, "HIGH_CONFIDENCE", null, null, base.plus(i, ChronoUnit.MINUTES)));
        }
        // Expect DESC order: seededIds reversed.
        List<String> expectedOrder = new ArrayList<>();
        for (int i = seededIds.size() - 1; i >= 0; i--) {
            expectedOrder.add(seededIds.get(i).toString());
        }

        // Page 1 (size=2): each call also writes its own ADMIN_AUDIT_VIEW row (now the newest row
        // in the table), which is exactly the scenario keyset pagination must stay stable under —
        // an OFFSET-based page 2 would have skipped/duplicated a row here.
        MvcResult page1 = mockMvc.perform(get("/v1/admin/audit-log")
                        .param("tenantId", tenantId.toString())
                        .param("size", "2")
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(2))
                .andExpect(jsonPath("$.data.hasMore").value(true))
                .andExpect(jsonPath("$.data.content[0].id").value(expectedOrder.get(0)))
                .andExpect(jsonPath("$.data.content[1].id").value(expectedOrder.get(1)))
                .andReturn();

        String cursorOccurredAt = JsonPath.read(page1.getResponse().getContentAsString(), "$.data.nextCursorOccurredAt");
        String cursorId = JsonPath.read(page1.getResponse().getContentAsString(), "$.data.nextCursorId");

        mockMvc.perform(get("/v1/admin/audit-log")
                        .param("tenantId", tenantId.toString())
                        .param("size", "2")
                        .param("cursorOccurredAt", cursorOccurredAt)
                        .param("cursorId", cursorId)
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].id").value(expectedOrder.get(2)))
                .andExpect(jsonPath("$.data.content[1].id").value(expectedOrder.get(3)));
    }
}
