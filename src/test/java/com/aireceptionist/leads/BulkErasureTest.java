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
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class BulkErasureTest extends AbstractIntegrationTest {

    @Autowired LeadService leadService;
    @Autowired LeadRepository leadRepository;
    @Autowired AuditLogRepository auditLogRepository;

    @Test
    void eraseAllNullsPiiForEveryLeadAndWritesSingleAuditLogEntry() {
        UUID tenantId = UUID.randomUUID();
        Instant before = Instant.now().minus(1, ChronoUnit.MINUTES);

        TenantContext.setCurrentTenant(tenantId.toString());
        try {
            for (int i = 0; i < 5; i++) {
                leadRepository.save(Lead.create(tenantId, "Customer " + i, "+91900000000" + i,
                        "Product " + i, LeadChannel.WHATSAPP, "WHATSAPP", Instant.now()));
            }

            leadService.eraseAllLeads(tenantId);

            List<Lead> all = leadRepository.findByTenantId(tenantId, Pageable.unpaged()).getContent();
            assertThat(all).hasSize(5);
            assertThat(all).allSatisfy(lead -> {
                assertThat(lead.getErased()).isTrue();
                assertThat(lead.getCustomerName()).isNull();
                assertThat(lead.getPhone()).isNull();
            });
        } finally {
            TenantContext.clear();
        }

        long auditCount = auditLogRepository.countByTenantIdAndEventTypeAndOccurredAtAfter(
                tenantId, AuditEventType.DATA_ERASED_BULK, before);
        assertThat(auditCount).isEqualTo(1);
    }
}
