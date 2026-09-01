package com.aireceptionist.leads;

import com.aireceptionist.AbstractIntegrationTest;
import com.aireceptionist.common.audit.AuditEventType;
import com.aireceptionist.common.audit.AuditLogRepository;
import com.aireceptionist.common.multitenancy.TenantContext;
import com.aireceptionist.leads.domain.Lead;
import com.aireceptionist.leads.domain.LeadChannel;
import com.aireceptionist.leads.repository.LeadRepository;
import com.aireceptionist.leads.service.LeadService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class LeadErasureTest extends AbstractIntegrationTest {

    @Autowired LeadService leadService;
    @Autowired LeadRepository leadRepository;
    @Autowired AuditLogRepository auditLogRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    @Test
    void erasingLeadNullsPiiRetainsRowWritesAuditLogAndExcludesFromExport() {
        UUID tenantId = UUID.randomUUID();
        Instant before = Instant.now().minus(1, ChronoUnit.MINUTES);
        UUID leadId;

        // leads.tenant_id has a FK to tenants(id) (V3), so a tenant row must exist first.
        jdbcTemplate.update(
                "INSERT INTO tenants (id, business_name, phone_number, tier, status) VALUES (?, ?, ?, 'PRO', 'ACTIVE')",
                tenantId, "Erasure Test Business", "+91" + System.nanoTime() % 10_000_000_000L);

        TenantContext.setCurrentTenant(tenantId.toString());
        try {
            leadId = leadRepository.save(Lead.create(tenantId, "Ravi Kumar", "+919876543210",
                    "Samsung Galaxy S24", LeadChannel.WHATSAPP, "WHATSAPP", Instant.now())).getId();

            leadService.eraseLead(tenantId, leadId);

            Lead reloaded = leadRepository.findById(leadId).orElseThrow();
            assertThat(reloaded.getCustomerName()).isNull();
            assertThat(reloaded.getPhone()).isNull();
            assertThat(reloaded.getProductIntent()).isEqualTo("Samsung Galaxy S24");
            assertThat(reloaded.getErased()).isTrue();

            List<Lead> exported = leadService.exportLeads(tenantId);
            assertThat(exported).extracting(Lead::getId).doesNotContain(leadId);
        } finally {
            TenantContext.clear();
        }

        long auditCount = auditLogRepository.countByTenantIdAndEventTypeAndOccurredAtAfter(
                tenantId, AuditEventType.DATA_ERASED, before);
        assertThat(auditCount).isEqualTo(1);
    }
}
