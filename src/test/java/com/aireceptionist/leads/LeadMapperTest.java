package com.aireceptionist.leads;

import com.aireceptionist.leads.domain.Lead;
import com.aireceptionist.leads.domain.LeadChannel;
import com.aireceptionist.leads.dto.LeadMapper;
import com.aireceptionist.leads.dto.LeadResponse;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class LeadMapperTest {

    private final LeadMapper leadMapper = new LeadMapper();

    @Test
    void nullsOutPiiFieldsForErasedLead() {
        UUID tenantId = UUID.randomUUID();
        Lead lead = Lead.create(tenantId, "Ravi Kumar", "+919876543210",
                "Samsung Galaxy S24", LeadChannel.WHATSAPP, "WHATSAPP", Instant.now());
        ReflectionTestUtils.setField(lead, "erased", true);

        LeadResponse response = leadMapper.toResponse(lead);

        assertThat(response.erased()).isTrue();
        assertThat(response.customerName()).isNull();
        assertThat(response.phone()).isNull();
        assertThat(response.consentTimestamp()).isNull();
        assertThat(response.productIntent()).isEqualTo("Samsung Galaxy S24");
        assertThat(response.id()).isEqualTo(lead.getId());
    }

    @Test
    void mapsFullDataForNonErasedLead() {
        UUID tenantId = UUID.randomUUID();
        Lead lead = Lead.create(tenantId, "Ravi Kumar", "+919876543210",
                "Samsung Galaxy S24", LeadChannel.WHATSAPP, "WHATSAPP", Instant.now());

        LeadResponse response = leadMapper.toResponse(lead);

        assertThat(response.erased()).isFalse();
        assertThat(response.customerName()).isEqualTo("Ravi Kumar");
        assertThat(response.phone()).isEqualTo("+919876543210");
    }
}
