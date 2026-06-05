package com.aireceptionist.whatsapp;

import com.aireceptionist.tenant.port.in.GetLiveTenantsUseCase;
import com.aireceptionist.whatsapp.service.WhatsAppQueueProcessor;
import com.aireceptionist.whatsapp.service.WhatsAppQueueService;
import com.aireceptionist.whatsapp.service.WhatsAppNotificationService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WhatsAppQueueProcessorTest {

    private final WhatsAppQueueService queueService = mock(WhatsAppQueueService.class);
    private final WhatsAppNotificationService notificationService = mock(WhatsAppNotificationService.class);
    private final GetLiveTenantsUseCase getLiveTenantsUseCase = mock(GetLiveTenantsUseCase.class);

    private final WhatsAppQueueProcessor processor =
            new WhatsAppQueueProcessor(queueService, notificationService, getLiveTenantsUseCase);

    @Test
    void processorSendsQueuedMessagesForLiveTenants() {
        UUID tenantId = UUID.randomUUID();
        when(getLiveTenantsUseCase.getLiveTenantIds()).thenReturn(List.of(tenantId));
        when(queueService.dequeueMessages(tenantId.toString(), 10))
                .thenReturn(List.of("PHONE:919876543210|MSG:Hello"));

        processor.processQueues();

        verify(notificationService).sendMessage(tenantId.toString(), "919876543210", "Hello");
    }

    @Test
    void processorSkipsTenantsWithEmptyQueue() {
        UUID tenantId = UUID.randomUUID();
        when(getLiveTenantsUseCase.getLiveTenantIds()).thenReturn(List.of(tenantId));
        when(queueService.dequeueMessages(anyString(), anyInt())).thenReturn(List.of());

        processor.processQueues();

        verify(notificationService, never()).sendMessage(anyString(), anyString(), anyString());
    }

    @Test
    void processorReenqueuesMessageOnSendFailure() {
        UUID tenantId = UUID.randomUUID();
        when(getLiveTenantsUseCase.getLiveTenantIds()).thenReturn(List.of(tenantId));
        when(queueService.dequeueMessages(tenantId.toString(), 10))
                .thenReturn(List.of("PHONE:919876543210|MSG:Retry me"));
        doThrow(new RuntimeException("API down"))
                .when(notificationService).sendMessage(anyString(), anyString(), anyString());

        processor.processQueues();

        verify(queueService).enqueueMessage(tenantId.toString(), "919876543210", "Retry me");
    }

    @Test
    void processorDoesNothingWhenNoLiveTenants() {
        when(getLiveTenantsUseCase.getLiveTenantIds()).thenReturn(List.of());

        processor.processQueues();

        verify(queueService, never()).dequeueMessages(anyString(), anyInt());
        verify(notificationService, never()).sendMessage(anyString(), anyString(), anyString());
    }

    @Test
    void processorSendsMultipleMessagesForSingleTenant() {
        UUID tenantId = UUID.randomUUID();
        String tid = tenantId.toString();
        when(getLiveTenantsUseCase.getLiveTenantIds()).thenReturn(List.of(tenantId));
        when(queueService.dequeueMessages(tid, 10)).thenReturn(List.of(
                "PHONE:9191|MSG:First",
                "PHONE:9192|MSG:Second"
        ));

        processor.processQueues();

        verify(notificationService).sendMessage(eq(tid), eq("9191"), eq("First"));
        verify(notificationService).sendMessage(eq(tid), eq("9192"), eq("Second"));
    }
}
