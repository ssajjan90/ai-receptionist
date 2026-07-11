package com.aireceptionist.leads;

import com.aireceptionist.AbstractIntegrationTest;
import com.aireceptionist.common.multitenancy.TenantContext;
import com.aireceptionist.leads.domain.Lead;
import com.aireceptionist.leads.domain.LeadChannel;
import com.aireceptionist.leads.repository.LeadRepository;
import com.aireceptionist.leads.service.LeadService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class LeadExportTest extends AbstractIntegrationTest {

    @Autowired LeadService leadService;
    @Autowired LeadRepository leadRepository;

    @Test
    void exportReturnsOnlyNonErasedLeadsWithAllFields() {
        UUID tenantId = UUID.randomUUID();
        UUID active1Id;
        UUID active2Id;

        TenantContext.setCurrentTenant(tenantId.toString());
        try {
            active1Id = leadRepository.save(Lead.create(tenantId, "Ravi Kumar", "+919876543210",
                    "Samsung Galaxy S24", LeadChannel.WHATSAPP, "WHATSAPP", Instant.now())).getId();
            active2Id = leadRepository.save(Lead.create(tenantId, "Priya Sharma", "+919876500000",
                    "iPhone 15", LeadChannel.WHATSAPP, "WHATSAPP", Instant.now())).getId();

            Lead erased = Lead.create(tenantId, "Erased Customer", "+919000000000",
                    "Old Product", LeadChannel.WHATSAPP, "WHATSAPP", Instant.now());
            erased.erase();
            leadRepository.save(erased);

            List<Lead> exported = leadService.exportLeads(tenantId);

            assertThat(exported).hasSize(2);
            assertThat(exported).extracting(Lead::getId).containsExactlyInAnyOrder(active1Id, active2Id);
            assertThat(exported).allSatisfy(lead -> {
                assertThat(lead.getCustomerName()).isNotNull();
                assertThat(lead.getPhone()).isNotNull();
                assertThat(lead.getProductIntent()).isNotNull();
                assertThat(lead.getConsentTimestamp()).isNotNull();
            });
        } finally {
            TenantContext.clear();
        }
    }
}
