package com.aireceptionist.ai;

import com.aireceptionist.knowledgebase.event.UnansweredQueryFlaggedEvent;
import com.aireceptionist.whatsapp.service.UnansweredQueryListener;
import com.aireceptionist.whatsapp.service.WhatsAppNotificationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class UnansweredQueryListenerTest {

    private WhatsAppNotificationService notificationService;
    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOps;
    private UnansweredQueryListener listener;

    @BeforeEach
    void setUp() {
        notificationService = mock(WhatsAppNotificationService.class);
        redisTemplate = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        listener = new UnansweredQueryListener(notificationService, redisTemplate, new ObjectMapper());
    }

    @Test
    void storesTrainingContextInRedisAndSendsTrainingNotification() {
        String tenantId = UUID.randomUUID().toString();
        UUID auditLogId = UUID.randomUUID();
        UnansweredQueryFlaggedEvent event = new UnansweredQueryFlaggedEvent(
                tenantId, "+919876543210", "What is the price of Samsung S24?", "+911234567890", auditLogId);

        listener.onUnansweredQuery(event);

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> valueCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOps).set(keyCaptor.capture(), valueCaptor.capture(), any());

        assertThat(keyCaptor.getValue()).isEqualTo("train:" + tenantId + ":+911234567890");
        assertThat(valueCaptor.getValue()).contains("Samsung S24").contains(auditLogId.toString());

        ArgumentCaptor<String> msgCaptor = ArgumentCaptor.forClass(String.class);
        verify(notificationService).sendMessage(anyString(), anyString(), msgCaptor.capture());
        assertThat(msgCaptor.getValue())
                .contains("⚠️")
                .contains("Samsung S24")
                .contains("Reply with the correct answer")
                .contains("SKIP");
    }

    @Test
    void skipsStorageAndNotificationWhenOwnerPhoneIsNull() {
        String tenantId = UUID.randomUUID().toString();
        UnansweredQueryFlaggedEvent event = new UnansweredQueryFlaggedEvent(
                tenantId, "+919876543210", "random question", null, UUID.randomUUID());

        listener.onUnansweredQuery(event);

        verifyNoInteractions(notificationService);
        verifyNoInteractions(redisTemplate);
    }
}
