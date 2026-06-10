package com.aireceptionist.ai;

import com.aireceptionist.knowledgebase.event.UnansweredQueryFlaggedEvent;
import com.aireceptionist.whatsapp.service.UnansweredQueryListener;
import com.aireceptionist.whatsapp.service.WhatsAppNotificationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class UnansweredQueryListenerTest {

    @Mock WhatsAppNotificationService notificationService;
    @InjectMocks UnansweredQueryListener listener;

    @Test
    void sendsOwnerNotificationWithOriginalQuery() {
        String tenantId = UUID.randomUUID().toString();
        UnansweredQueryFlaggedEvent event = new UnansweredQueryFlaggedEvent(
                tenantId, "+919876543210", "What is the price of Samsung S24?", "+911234567890");

        listener.onUnansweredQuery(event);

        verify(notificationService).sendMessage(
                tenantId,
                "+911234567890",
                "⚠️ Unanswered query from +919876543210: 'What is the price of Samsung S24?'. Reply to train the AI."
        );
    }

    @Test
    void skipsNotificationWhenOwnerPhoneIsNull() {
        String tenantId = UUID.randomUUID().toString();
        UnansweredQueryFlaggedEvent event = new UnansweredQueryFlaggedEvent(
                tenantId, "+919876543210", "random question", null);

        listener.onUnansweredQuery(event);

        verifyNoInteractions(notificationService);
    }
}
