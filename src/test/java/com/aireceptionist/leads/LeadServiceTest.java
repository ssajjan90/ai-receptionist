package com.aireceptionist.leads;

import com.aireceptionist.leads.domain.Lead;
import com.aireceptionist.leads.domain.LeadChannel;
import com.aireceptionist.leads.domain.LeadStatus;
import com.aireceptionist.leads.dto.CreateLeadCommand;
import com.aireceptionist.leads.dto.LeadResponse;
import com.aireceptionist.leads.event.LeadCapturedEvent;
import com.aireceptionist.leads.repository.LeadRepository;
import com.aireceptionist.leads.service.LeadService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LeadServiceTest {

    @Mock LeadRepository leadRepository;
    @Mock ApplicationEventPublisher eventPublisher;
    @InjectMocks LeadService leadService;

    @Test
    void savesLeadWithCorrectFields() {
        UUID tenantId = UUID.randomUUID();
        CreateLeadCommand command = new CreateLeadCommand(
                tenantId, "Ravi Kumar", "+919876543210",
                "Samsung Galaxy S24", LeadChannel.WHATSAPP, "WHATSAPP", Instant.now());
        when(leadRepository.save(any(Lead.class))).thenAnswer(inv -> inv.getArgument(0));

        LeadResponse response = leadService.createLead(command, "+910000000000");

        assertThat(response.tenantId()).isEqualTo(tenantId);
        assertThat(response.customerName()).isEqualTo("Ravi Kumar");
        assertThat(response.phone()).isEqualTo("+919876543210");
        assertThat(response.productIntent()).isEqualTo("Samsung Galaxy S24");
        assertThat(response.channel()).isEqualTo(LeadChannel.WHATSAPP);
        assertThat(response.status()).isEqualTo(LeadStatus.NEW);
        assertThat(response.erased()).isFalse();
        assertThat(response.consentTimestamp()).isNotNull();
        assertThat(response.id()).isNotNull();
    }

    @Test
    void publishesLeadCapturedEvent() {
        UUID tenantId = UUID.randomUUID();
        CreateLeadCommand command = new CreateLeadCommand(
                tenantId, "Ravi Kumar", "+919876543210",
                "phone", LeadChannel.WHATSAPP, "WHATSAPP", Instant.now());
        when(leadRepository.save(any(Lead.class))).thenAnswer(inv -> inv.getArgument(0));

        leadService.createLead(command, "+910000000000");

        ArgumentCaptor<LeadCapturedEvent> captor = ArgumentCaptor.forClass(LeadCapturedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());

        LeadCapturedEvent event = captor.getValue();
        assertThat(event.getTenantId()).isEqualTo(tenantId.toString());
        assertThat(event.getCustomerPhone()).isEqualTo("+919876543210");
        assertThat(event.getOwnerPhone()).isEqualTo("+910000000000");
        assertThat(event.getLeadId()).isNotNull();
    }

    @Test
    void rejectsNullTenantId() {
        CreateLeadCommand command = new CreateLeadCommand(
                null, "Test", "+91999", "phone", LeadChannel.WHATSAPP, "WHATSAPP", null);
        assertThatThrownBy(() -> leadService.createLead(command, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tenantId");
    }
}
