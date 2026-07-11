package com.aireceptionist.leads;

import com.aireceptionist.common.ai.TenantNamePort;
import com.aireceptionist.common.ai.TenantOwnerPhonePort;
import com.aireceptionist.common.audit.AuditLogRepository;
import com.aireceptionist.leads.domain.Lead;
import com.aireceptionist.leads.domain.LeadChannel;
import com.aireceptionist.leads.repository.LeadRepository;
import com.aireceptionist.leads.service.DailySummaryJob;
import com.aireceptionist.tenant.port.in.GetLiveTenantsUseCase;
import com.aireceptionist.whatsapp.domain.SenderType;
import com.aireceptionist.whatsapp.repository.WhatsAppMessageRepository;
import com.aireceptionist.whatsapp.service.WhatsAppNotificationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DailySummaryJobTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-07-09T08:00:00Z"), ZoneOffset.UTC);
    private static final String DASHBOARD_URL = "https://app.callsahayak.com/dashboard";

    @Mock GetLiveTenantsUseCase liveTenantsUseCase;
    @Mock TenantNamePort tenantNamePort;
    @Mock TenantOwnerPhonePort tenantOwnerPhonePort;
    @Mock WhatsAppMessageRepository whatsAppMessageRepository;
    @Mock LeadRepository leadRepository;
    @Mock AuditLogRepository auditLogRepository;
    @Mock WhatsAppNotificationService notificationService;

    private DailySummaryJob newJob(boolean enabled) {
        return new DailySummaryJob(liveTenantsUseCase, tenantNamePort, tenantOwnerPhonePort,
                whatsAppMessageRepository, leadRepository, auditLogRepository, notificationService,
                FIXED_CLOCK, enabled, DASHBOARD_URL);
    }

    @Test
    void featureFlagDisabledSkipsEntirely() {
        DailySummaryJob job = newJob(false);

        job.sendDailySummaries();

        verifyNoInteractions(liveTenantsUseCase, tenantNamePort, tenantOwnerPhonePort,
                whatsAppMessageRepository, leadRepository, auditLogRepository, notificationService);
    }

    @Test
    void zeroMessagesSendsNoNotification() {
        UUID tenantId = UUID.randomUUID();
        when(liveTenantsUseCase.getLiveTenantIds()).thenReturn(List.of(tenantId));
        when(whatsAppMessageRepository.countByTenantIdAndSenderTypeAndReceivedAtAfter(
                eq(tenantId), eq(SenderType.CUSTOMER), any())).thenReturn(0L);

        DailySummaryJob job = newJob(true);
        job.sendDailySummaries();

        verify(notificationService, never()).sendMessage(any(), any(), any());
        verify(leadRepository, never()).countByTenantIdAndCreatedAtAfter(any(), any());
    }

    @Test
    void sendsSummaryWithMessageAndLeadCountsForActiveTenant() {
        UUID tenantId = UUID.randomUUID();
        Lead lead1 = Lead.create(tenantId, "Ravi Kumar", "+919876543210", "Samsung Galaxy S24",
                LeadChannel.WHATSAPP, "WHATSAPP", Instant.now(FIXED_CLOCK));
        Lead lead2 = Lead.create(tenantId, "Priya Sharma", "+919876500000", "iPhone 15",
                LeadChannel.WHATSAPP, "WHATSAPP", Instant.now(FIXED_CLOCK));

        when(liveTenantsUseCase.getLiveTenantIds()).thenReturn(List.of(tenantId));
        when(whatsAppMessageRepository.countByTenantIdAndSenderTypeAndReceivedAtAfter(
                eq(tenantId), eq(SenderType.CUSTOMER), any())).thenReturn(5L);
        when(leadRepository.countByTenantIdAndCreatedAtAfter(eq(tenantId), any())).thenReturn(2L);
        when(leadRepository.findTop5ByTenantIdAndErasedFalseAndCreatedAtAfterOrderByCreatedAtDesc(eq(tenantId), any()))
                .thenReturn(List.of(lead1, lead2));
        when(auditLogRepository.countByTenantIdAndEventTypeAndOccurredAtAfter(eq(tenantId), any(), any()))
                .thenReturn(1L);
        when(tenantNamePort.getBusinessName(tenantId.toString())).thenReturn(Optional.of("Ravi Mobiles"));
        when(tenantOwnerPhonePort.getOwnerPhone(tenantId.toString())).thenReturn(Optional.of("+919999999999"));

        DailySummaryJob job = newJob(true);
        job.sendDailySummaries();

        ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
        verify(notificationService).sendMessage(eq(tenantId.toString()), eq("+919999999999"), messageCaptor.capture());

        String message = messageCaptor.getValue();
        assertThat(message).contains("Ravi Mobiles");
        assertThat(message).contains("Messages handled: 5");
        assertThat(message).contains("New leads captured: 2");
        assertThat(message).contains("Unanswered queries: 1");
        assertThat(message).contains("Ravi Kumar");
        assertThat(message).contains("Samsung Galaxy S24");
        assertThat(message).contains(DASHBOARD_URL);
    }

    @Test
    void showsMoreIndicatorWhenLeadCountExceedsTop5() {
        UUID tenantId = UUID.randomUUID();
        List<Lead> top5 = List.of(
                Lead.create(tenantId, "Lead One", "+910000000001", "Product A", LeadChannel.WHATSAPP, "WHATSAPP", Instant.now(FIXED_CLOCK)),
                Lead.create(tenantId, "Lead Two", "+910000000002", "Product B", LeadChannel.WHATSAPP, "WHATSAPP", Instant.now(FIXED_CLOCK)),
                Lead.create(tenantId, "Lead Three", "+910000000003", "Product C", LeadChannel.WHATSAPP, "WHATSAPP", Instant.now(FIXED_CLOCK)),
                Lead.create(tenantId, "Lead Four", "+910000000004", "Product D", LeadChannel.WHATSAPP, "WHATSAPP", Instant.now(FIXED_CLOCK)),
                Lead.create(tenantId, "Lead Five", "+910000000005", "Product E", LeadChannel.WHATSAPP, "WHATSAPP", Instant.now(FIXED_CLOCK)));

        when(liveTenantsUseCase.getLiveTenantIds()).thenReturn(List.of(tenantId));
        when(whatsAppMessageRepository.countByTenantIdAndSenderTypeAndReceivedAtAfter(
                eq(tenantId), eq(SenderType.CUSTOMER), any())).thenReturn(10L);
        when(leadRepository.countByTenantIdAndCreatedAtAfter(eq(tenantId), any())).thenReturn(7L);
        when(leadRepository.findTop5ByTenantIdAndErasedFalseAndCreatedAtAfterOrderByCreatedAtDesc(eq(tenantId), any()))
                .thenReturn(top5);
        when(auditLogRepository.countByTenantIdAndEventTypeAndOccurredAtAfter(eq(tenantId), any(), any()))
                .thenReturn(0L);
        when(tenantNamePort.getBusinessName(tenantId.toString())).thenReturn(Optional.of("Busy Shop"));
        when(tenantOwnerPhonePort.getOwnerPhone(tenantId.toString())).thenReturn(Optional.of("+919999999999"));

        DailySummaryJob job = newJob(true);
        job.sendDailySummaries();

        ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
        verify(notificationService).sendMessage(any(), any(), messageCaptor.capture());
        assertThat(messageCaptor.getValue()).contains("...and 2 more");
    }

    @Test
    void sanitizesMarkdownControlCharactersInInterpolatedText() {
        UUID tenantId = UUID.randomUUID();
        Lead lead = Lead.create(tenantId, "*URGENT* Ravi", "+919876543210", "_Galaxy_ S24",
                LeadChannel.WHATSAPP, "WHATSAPP", Instant.now(FIXED_CLOCK));

        when(liveTenantsUseCase.getLiveTenantIds()).thenReturn(List.of(tenantId));
        when(whatsAppMessageRepository.countByTenantIdAndSenderTypeAndReceivedAtAfter(
                eq(tenantId), eq(SenderType.CUSTOMER), any())).thenReturn(1L);
        when(leadRepository.countByTenantIdAndCreatedAtAfter(eq(tenantId), any())).thenReturn(1L);
        when(leadRepository.findTop5ByTenantIdAndErasedFalseAndCreatedAtAfterOrderByCreatedAtDesc(eq(tenantId), any()))
                .thenReturn(List.of(lead));
        when(auditLogRepository.countByTenantIdAndEventTypeAndOccurredAtAfter(eq(tenantId), any(), any()))
                .thenReturn(0L);
        when(tenantNamePort.getBusinessName(tenantId.toString())).thenReturn(Optional.of("*Shady* Shop"));
        when(tenantOwnerPhonePort.getOwnerPhone(tenantId.toString())).thenReturn(Optional.of("+919999999999"));

        DailySummaryJob job = newJob(true);
        job.sendDailySummaries();

        ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
        verify(notificationService).sendMessage(any(), any(), messageCaptor.capture());
        String message = messageCaptor.getValue();
        assertThat(message).doesNotContain("*URGENT*").doesNotContain("*Shady*").doesNotContain("_Galaxy_");
        assertThat(message).contains("URGENT Ravi").contains("Shady Shop").contains("Galaxy S24");
    }

    @Test
    void skipsTenantWithNoOwnerPhoneOnFile() {
        UUID tenantId = UUID.randomUUID();
        when(liveTenantsUseCase.getLiveTenantIds()).thenReturn(List.of(tenantId));
        when(whatsAppMessageRepository.countByTenantIdAndSenderTypeAndReceivedAtAfter(
                eq(tenantId), eq(SenderType.CUSTOMER), any())).thenReturn(3L);
        when(leadRepository.countByTenantIdAndCreatedAtAfter(eq(tenantId), any())).thenReturn(0L);
        when(leadRepository.findTop5ByTenantIdAndErasedFalseAndCreatedAtAfterOrderByCreatedAtDesc(eq(tenantId), any()))
                .thenReturn(List.of());
        when(auditLogRepository.countByTenantIdAndEventTypeAndOccurredAtAfter(eq(tenantId), any(), any()))
                .thenReturn(0L);
        when(tenantOwnerPhonePort.getOwnerPhone(tenantId.toString())).thenReturn(Optional.empty());

        DailySummaryJob job = newJob(true);
        job.sendDailySummaries();

        verify(notificationService, never()).sendMessage(any(), any(), any());
    }

    @Test
    void iteratesAllLiveTenantsIndividually() {
        UUID quietTenant = UUID.randomUUID();
        UUID activeTenant = UUID.randomUUID();
        when(liveTenantsUseCase.getLiveTenantIds()).thenReturn(List.of(quietTenant, activeTenant));
        when(whatsAppMessageRepository.countByTenantIdAndSenderTypeAndReceivedAtAfter(
                eq(quietTenant), eq(SenderType.CUSTOMER), any())).thenReturn(0L);
        when(whatsAppMessageRepository.countByTenantIdAndSenderTypeAndReceivedAtAfter(
                eq(activeTenant), eq(SenderType.CUSTOMER), any())).thenReturn(2L);
        when(leadRepository.countByTenantIdAndCreatedAtAfter(eq(activeTenant), any())).thenReturn(1L);
        when(leadRepository.findTop5ByTenantIdAndErasedFalseAndCreatedAtAfterOrderByCreatedAtDesc(eq(activeTenant), any()))
                .thenReturn(List.of());
        when(auditLogRepository.countByTenantIdAndEventTypeAndOccurredAtAfter(eq(activeTenant), any(), any()))
                .thenReturn(0L);
        when(tenantNamePort.getBusinessName(activeTenant.toString())).thenReturn(Optional.of("Active Shop"));
        when(tenantOwnerPhonePort.getOwnerPhone(activeTenant.toString())).thenReturn(Optional.of("+911111111111"));

        DailySummaryJob job = newJob(true);
        job.sendDailySummaries();

        verify(notificationService).sendMessage(eq(activeTenant.toString()), eq("+911111111111"), any());
        verify(notificationService, never()).sendMessage(eq(quietTenant.toString()), any(), any());
        verify(leadRepository, never()).countByTenantIdAndCreatedAtAfter(eq(quietTenant), any());
    }

    @Test
    void oneTenantFailureDoesNotStopSummaryForOtherTenants() {
        UUID failingTenant = UUID.randomUUID();
        UUID healthyTenant = UUID.randomUUID();
        when(liveTenantsUseCase.getLiveTenantIds()).thenReturn(List.of(failingTenant, healthyTenant));
        when(whatsAppMessageRepository.countByTenantIdAndSenderTypeAndReceivedAtAfter(
                eq(failingTenant), eq(SenderType.CUSTOMER), any())).thenThrow(new RuntimeException("DB blip"));
        when(whatsAppMessageRepository.countByTenantIdAndSenderTypeAndReceivedAtAfter(
                eq(healthyTenant), eq(SenderType.CUSTOMER), any())).thenReturn(1L);
        when(leadRepository.countByTenantIdAndCreatedAtAfter(eq(healthyTenant), any())).thenReturn(0L);
        when(leadRepository.findTop5ByTenantIdAndErasedFalseAndCreatedAtAfterOrderByCreatedAtDesc(eq(healthyTenant), any()))
                .thenReturn(List.of());
        when(auditLogRepository.countByTenantIdAndEventTypeAndOccurredAtAfter(eq(healthyTenant), any(), any()))
                .thenReturn(0L);
        when(tenantNamePort.getBusinessName(healthyTenant.toString())).thenReturn(Optional.of("Healthy Shop"));
        when(tenantOwnerPhonePort.getOwnerPhone(healthyTenant.toString())).thenReturn(Optional.of("+912222222222"));

        DailySummaryJob job = newJob(true);
        job.sendDailySummaries();

        verify(notificationService).sendMessage(eq(healthyTenant.toString()), eq("+912222222222"), any());
    }
}
