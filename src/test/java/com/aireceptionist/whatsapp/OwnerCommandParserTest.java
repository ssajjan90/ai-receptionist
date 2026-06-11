package com.aireceptionist.whatsapp;

import com.aireceptionist.knowledgebase.dto.ConflictInfo;
import com.aireceptionist.knowledgebase.service.ConflictDetectionService;
import com.aireceptionist.knowledgebase.service.KnowledgeBaseService;
import com.aireceptionist.whatsapp.event.OwnerCommandReceivedEvent;
import com.aireceptionist.whatsapp.service.OwnerCommandParser;
import com.aireceptionist.whatsapp.service.WhatsAppNotificationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OwnerCommandParserTest {

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
        when(valueOps.get(anyString())).thenReturn(null); // no pending command by default

        parser = new OwnerCommandParser(notificationService, knowledgeBaseService,
                conflictDetectionService, redisTemplate, new ObjectMapper(), eventPublisher);
    }

    @Test
    void addCommandCallsUpsertProduct() {
        when(conflictDetectionService.checkConflict(any(), anyString(), anyString()))
                .thenReturn(Optional.empty());

        parser.onOwnerCommand(event("ADD: iPhone 14 | 72000"));

        verify(knowledgeBaseService).addOrUpdateProduct(UUID.fromString(TENANT_ID), "iPhone 14", "72000");
        ArgumentCaptor<String> msgCaptor = ArgumentCaptor.forClass(String.class);
        verify(notificationService).sendMessage(eq(TENANT_ID), eq(OWNER_PHONE), msgCaptor.capture());
        assertThat(msgCaptor.getValue()).contains("iPhone 14").contains("72000").contains("✓");
    }

    @Test
    void updateCommandCallsUpsertProduct() {
        when(conflictDetectionService.checkConflict(any(), anyString(), anyString()))
                .thenReturn(Optional.empty());

        parser.onOwnerCommand(event("UPDATE: Samsung S24 | 65000"));

        verify(knowledgeBaseService).addOrUpdateProduct(UUID.fromString(TENANT_ID), "Samsung S24", "65000");
    }

    @Test
    void deleteCommandCallsDeleteEntry() {
        when(knowledgeBaseService.deleteEntry(any(), anyString())).thenReturn(true);

        parser.onOwnerCommand(event("DELETE: Old Product"));

        verify(knowledgeBaseService).deleteEntry(UUID.fromString(TENANT_ID), "Old Product");
        ArgumentCaptor<String> msgCaptor = ArgumentCaptor.forClass(String.class);
        verify(notificationService).sendMessage(eq(TENANT_ID), eq(OWNER_PHONE), msgCaptor.capture());
        assertThat(msgCaptor.getValue()).contains("Deleted").contains("Old Product");
    }

    @Test
    void deleteCommandNotFoundSendsNotFoundMessage() {
        when(knowledgeBaseService.deleteEntry(any(), anyString())).thenReturn(false);

        parser.onOwnerCommand(event("DELETE: Nonexistent Product"));

        ArgumentCaptor<String> msgCaptor = ArgumentCaptor.forClass(String.class);
        verify(notificationService).sendMessage(eq(TENANT_ID), eq(OWNER_PHONE), msgCaptor.capture());
        assertThat(msgCaptor.getValue()).contains("Not found");
    }

    @Test
    void addFaqCommandCallsUpsertFaq() {
        parser.onOwnerCommand(event("ADD FAQ: What is your return policy? | 30-day return policy"));

        verify(knowledgeBaseService).addOrUpdateFaq(UUID.fromString(TENANT_ID),
                "What is your return policy?", "30-day return policy");
        ArgumentCaptor<String> msgCaptor = ArgumentCaptor.forClass(String.class);
        verify(notificationService).sendMessage(eq(TENANT_ID), eq(OWNER_PHONE), msgCaptor.capture());
        assertThat(msgCaptor.getValue()).contains("FAQ").contains("✓");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "add: Product | 100",
        "ADD: Product | 100",
        "Add: Product | 100"
    })
    void addCommandIsCaseInsensitive(String message) {
        when(conflictDetectionService.checkConflict(any(), anyString(), anyString()))
                .thenReturn(Optional.empty());

        parser.onOwnerCommand(event(message));

        verify(knowledgeBaseService).addOrUpdateProduct(eq(UUID.fromString(TENANT_ID)),
                eq("Product"), eq("100"));
    }

    @Test
    void addCommandTrimsWhitespace() {
        when(conflictDetectionService.checkConflict(any(), anyString(), anyString()))
                .thenReturn(Optional.empty());

        parser.onOwnerCommand(event("ADD:   Laptop X   |   45000  "));

        verify(knowledgeBaseService).addOrUpdateProduct(UUID.fromString(TENANT_ID), "Laptop X", "45000");
    }

    @Test
    void addFaqIsCheckedBeforeAddToAvoidRegexCollision() {
        parser.onOwnerCommand(event("ADD FAQ: Is this available? | Yes it is"));

        verify(knowledgeBaseService).addOrUpdateFaq(any(), eq("Is this available?"), eq("Yes it is"));
        verify(knowledgeBaseService, never()).addOrUpdateProduct(any(), anyString(), anyString());
    }

    @Test
    void unrecognisedCommandSendsHelpMessage() {
        parser.onOwnerCommand(event("RANDOM garbage command"));

        ArgumentCaptor<String> msgCaptor = ArgumentCaptor.forClass(String.class);
        verify(notificationService).sendMessage(eq(TENANT_ID), eq(OWNER_PHONE), msgCaptor.capture());
        String help = msgCaptor.getValue();
        assertThat(help).contains("ADD: Product | Price");
        assertThat(help).contains("UPDATE: Product | New Price");
        assertThat(help).contains("DELETE: Product");
        assertThat(help).contains("ADD FAQ: Question | Answer");

        verify(knowledgeBaseService, never()).addOrUpdateProduct(any(), anyString(), anyString());
        verify(knowledgeBaseService, never()).deleteEntry(any(), anyString());
    }

    @Test
    void conflictDetectedSendsWarningAndStoresPendingCommand() throws Exception {
        ConflictInfo conflict = new ConflictInfo("iPhone 14", "65000", "72000");
        when(conflictDetectionService.checkConflict(any(), eq("iPhone 14"), eq("72000")))
                .thenReturn(Optional.of(conflict));

        parser.onOwnerCommand(event("ADD: iPhone 14 | 72000"));

        verify(knowledgeBaseService, never()).addOrUpdateProduct(any(), anyString(), anyString());
        ArgumentCaptor<String> msgCaptor = ArgumentCaptor.forClass(String.class);
        verify(notificationService).sendMessage(eq(TENANT_ID), eq(OWNER_PHONE), msgCaptor.capture());
        assertThat(msgCaptor.getValue()).contains("⚠️").contains("Conflict").contains("65000").contains("72000");
        verify(valueOps).set(anyString(), anyString(), any());
    }

    @Test
    void confirmResolvesConflictAndAppliesChange() throws Exception {
        String pendingJson = "{\"product\":\"iPhone 14\",\"price\":\"72000\",\"existingPrice\":\"65000\"}";
        String key = "pending_cmd:" + TENANT_ID + ":" + OWNER_PHONE;
        when(valueOps.get(key)).thenReturn(pendingJson);

        parser.onOwnerCommand(event("CONFIRM"));

        verify(knowledgeBaseService).addOrUpdateProduct(UUID.fromString(TENANT_ID), "iPhone 14", "72000");
        verify(redisTemplate).delete(key);
        ArgumentCaptor<String> msgCaptor = ArgumentCaptor.forClass(String.class);
        verify(notificationService).sendMessage(eq(TENANT_ID), eq(OWNER_PHONE), msgCaptor.capture());
        assertThat(msgCaptor.getValue()).contains("iPhone 14").contains("72000").contains("✓");
    }

    @Test
    void cancelKeepsCurrentPrice() throws Exception {
        String pendingJson = "{\"product\":\"iPhone 14\",\"price\":\"72000\",\"existingPrice\":\"65000\"}";
        String key = "pending_cmd:" + TENANT_ID + ":" + OWNER_PHONE;
        when(valueOps.get(key)).thenReturn(pendingJson);

        parser.onOwnerCommand(event("CANCEL"));

        verify(knowledgeBaseService, never()).addOrUpdateProduct(any(), anyString(), anyString());
        verify(redisTemplate).delete(key);
        ArgumentCaptor<String> msgCaptor = ArgumentCaptor.forClass(String.class);
        verify(notificationService).sendMessage(eq(TENANT_ID), eq(OWNER_PHONE), msgCaptor.capture());
        assertThat(msgCaptor.getValue()).contains("Keeping").contains("65000");
    }

    @Test
    void blankMessageIsIgnoredSilently() {
        parser.onOwnerCommand(event("   "));

        verify(notificationService, never()).sendMessage(anyString(), anyString(), anyString());
        verify(knowledgeBaseService, never()).addOrUpdateProduct(any(), anyString(), anyString());
    }

    private OwnerCommandReceivedEvent event(String message) {
        return new OwnerCommandReceivedEvent(TENANT_ID, message, OWNER_PHONE, Instant.now());
    }
}
