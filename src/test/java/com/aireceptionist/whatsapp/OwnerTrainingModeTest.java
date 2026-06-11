package com.aireceptionist.whatsapp;

import com.aireceptionist.knowledgebase.event.KbTrainingCompletedEvent;
import com.aireceptionist.knowledgebase.service.ConflictDetectionService;
import com.aireceptionist.knowledgebase.service.KnowledgeBaseService;
import com.aireceptionist.whatsapp.event.OwnerCommandReceivedEvent;
import com.aireceptionist.whatsapp.service.OwnerCommandParser;
import com.aireceptionist.whatsapp.service.WhatsAppNotificationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OwnerTrainingModeTest {

    private static final String TENANT_ID = UUID.randomUUID().toString();
    private static final String OWNER_PHONE = "+919876543210";

    private WhatsAppNotificationService notificationService;
    private KnowledgeBaseService knowledgeBaseService;
    private ConflictDetectionService conflictDetectionService;
    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOps;
    private ApplicationEventPublisher eventPublisher;
    private OwnerCommandParser parser;

    @BeforeEach
    void setUp() {
        notificationService = mock(WhatsAppNotificationService.class);
        knowledgeBaseService = mock(KnowledgeBaseService.class);
        conflictDetectionService = mock(ConflictDetectionService.class);
        redisTemplate = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenReturn(null);

        parser = new OwnerCommandParser(notificationService, knowledgeBaseService,
                conflictDetectionService, redisTemplate, new ObjectMapper(), eventPublisher);
    }

    @Test
    void trainingModeTakesPriorityOverCommandParsing() throws Exception {
        UUID auditLogId = UUID.randomUUID();
        String question = "What is your return policy?";
        String trainingJson = "{\"question\":\"" + question + "\",\"auditLogId\":\"" + auditLogId + "\"}";
        String trainKey = "train:" + TENANT_ID + ":" + OWNER_PHONE;
        when(valueOps.get(trainKey)).thenReturn(trainingJson);

        // Message that looks like a command but should be treated as training answer
        parser.onOwnerCommand(event("ADD: SomeProduct | 500"));

        verify(knowledgeBaseService).addFaqFromOwnerTraining(
                UUID.fromString(TENANT_ID), question, "ADD: SomeProduct | 500");
        verify(knowledgeBaseService, never()).addOrUpdateProduct(any(), anyString(), anyString());
        verify(redisTemplate).delete(trainKey);

        ArgumentCaptor<String> msgCaptor = ArgumentCaptor.forClass(String.class);
        verify(notificationService).sendMessage(eq(TENANT_ID), eq(OWNER_PHONE), msgCaptor.capture());
        assertThat(msgCaptor.getValue()).contains("Got it!").contains("✓");
    }

    @Test
    void trainingModePublishesKbTrainingCompletedEvent() throws Exception {
        UUID auditLogId = UUID.randomUUID();
        String question = "Do you deliver?";
        String trainingJson = "{\"question\":\"" + question + "\",\"auditLogId\":\"" + auditLogId + "\"}";
        when(valueOps.get("train:" + TENANT_ID + ":" + OWNER_PHONE)).thenReturn(trainingJson);

        parser.onOwnerCommand(event("Yes, we deliver within 2 days."));

        ArgumentCaptor<KbTrainingCompletedEvent> eventCaptor =
                ArgumentCaptor.forClass(KbTrainingCompletedEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        KbTrainingCompletedEvent published = eventCaptor.getValue();
        assertThat(published.getTenantId()).isEqualTo(TENANT_ID);
        assertThat(published.getAuditLogId()).isEqualTo(auditLogId);
        assertThat(published.getQuestion()).isEqualTo(question);
        assertThat(published.getAnswer()).isEqualTo("Yes, we deliver within 2 days.");
    }

    @Test
    void skipClearsTrainingKeyWithoutKbChange() throws Exception {
        UUID auditLogId = UUID.randomUUID();
        String trainingJson = "{\"question\":\"Any question\",\"auditLogId\":\"" + auditLogId + "\"}";
        String trainKey = "train:" + TENANT_ID + ":" + OWNER_PHONE;
        when(valueOps.get(trainKey)).thenReturn(trainingJson);

        parser.onOwnerCommand(event("SKIP"));

        verify(redisTemplate).delete(trainKey);
        verify(knowledgeBaseService, never()).addFaqFromOwnerTraining(any(), anyString(), anyString());
        ArgumentCaptor<KbTrainingCompletedEvent> eventCaptor = ArgumentCaptor.forClass(KbTrainingCompletedEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getAuditLogId()).isEqualTo(auditLogId);
        assertThat(eventCaptor.getValue().getAnswer()).isNull();

        ArgumentCaptor<String> msgCaptor = ArgumentCaptor.forClass(String.class);
        verify(notificationService).sendMessage(eq(TENANT_ID), eq(OWNER_PHONE), msgCaptor.capture());
        assertThat(msgCaptor.getValue()).contains("skipping");
    }

    @Test
    void skipIsCaseInsensitive() throws Exception {
        String trainingJson = "{\"question\":\"Q\",\"auditLogId\":\"" + UUID.randomUUID() + "\"}";
        String trainKey = "train:" + TENANT_ID + ":" + OWNER_PHONE;
        when(valueOps.get(trainKey)).thenReturn(trainingJson);

        parser.onOwnerCommand(event("skip"));

        verify(redisTemplate).delete(trainKey);
        verify(knowledgeBaseService, never()).addFaqFromOwnerTraining(any(), anyString(), anyString());
    }

    @Test
    void noTrainingContextFallsThroughToCommandParsing() {
        // No train key in Redis — falls through to normal command parsing
        when(valueOps.get("train:" + TENANT_ID + ":" + OWNER_PHONE)).thenReturn(null);
        when(valueOps.get("pending_cmd:" + TENANT_ID + ":" + OWNER_PHONE)).thenReturn(null);

        parser.onOwnerCommand(event("RANDOM garbage"));

        verify(knowledgeBaseService, never()).addFaqFromOwnerTraining(any(), anyString(), anyString());
        ArgumentCaptor<String> msgCaptor = ArgumentCaptor.forClass(String.class);
        verify(notificationService).sendMessage(eq(TENANT_ID), eq(OWNER_PHONE), msgCaptor.capture());
        assertThat(msgCaptor.getValue()).contains("ADD: Product | Price");
    }

    private OwnerCommandReceivedEvent event(String message) {
        return new OwnerCommandReceivedEvent(TENANT_ID, message, OWNER_PHONE, Instant.now());
    }
}
