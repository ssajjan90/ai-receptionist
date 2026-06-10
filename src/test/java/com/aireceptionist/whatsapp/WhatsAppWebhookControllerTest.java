package com.aireceptionist.whatsapp;

import com.aireceptionist.tenant.port.in.ResolveTenantByWhatsAppPhoneUseCase;
import com.aireceptionist.tenant.port.in.ResolvedTenantWhatsAppRoute;
import com.aireceptionist.whatsapp.adapter.in.web.WhatsAppWebhookController;
import com.aireceptionist.whatsapp.event.InboundWhatsAppMessageEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class WhatsAppWebhookControllerTest {

    private static final String APP_SECRET = "test-secret";

    private final ResolveTenantByWhatsAppPhoneUseCase resolver = mock(ResolveTenantByWhatsAppPhoneUseCase.class);
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
    private final com.aireceptionist.whatsapp.service.WhatsAppNotificationService notificationService =
            mock(com.aireceptionist.whatsapp.service.WhatsAppNotificationService.class);
    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new WhatsAppWebhookController(
            "verify-token",
            APP_SECRET,
            new ObjectMapper(),
            resolver,
            eventPublisher,
            notificationService
    )).build();

    @Test
    void getWebhookVerificationReturnsChallengeForValidToken() throws Exception {
        mockMvc.perform(get("/webhooks/whatsapp")
                        .param("hub.mode", "subscribe")
                        .param("hub.challenge", "abc123")
                        .param("hub.verify_token", "verify-token"))
                .andExpect(status().isOk())
                .andExpect(content().string("abc123"));
    }

    @Test
    void getWebhookVerificationRejectsInvalidToken() throws Exception {
        mockMvc.perform(get("/webhooks/whatsapp")
                        .param("hub.mode", "subscribe")
                        .param("hub.challenge", "abc123")
                        .param("hub.verify_token", "wrong-token"))
                .andExpect(status().isForbidden());
    }

    @Test
    void postWebhookWithValidSignaturePublishesInboundMessageEvent() throws Exception {
        UUID tenantId = UUID.randomUUID();
        byte[] body = webhookBody().getBytes(StandardCharsets.UTF_8);
        when(resolver.resolveByPhoneNumberId("PHONE_1"))
                .thenReturn(Optional.of(new ResolvedTenantWhatsAppRoute(tenantId, "PHONE_1", "910000000000")));

        mockMvc.perform(post("/webhooks/whatsapp")
                        .contentType("application/json")
                        .header("X-Hub-Signature-256", signature(body))
                        .content(body))
                .andExpect(status().isOk());

        ArgumentCaptor<InboundWhatsAppMessageEvent> captor = ArgumentCaptor.forClass(InboundWhatsAppMessageEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().getTenantIdValue()).isEqualTo(tenantId);
        assertThat(captor.getValue().getPhoneNumberId()).isEqualTo("PHONE_1");
        assertThat(captor.getValue().getMessageId()).isEqualTo("wamid.1");
        assertThat(captor.getValue().getMessageText()).isEqualTo("Hello");
    }

    @Test
    void postWebhookWithInvalidSignatureReturnsOkWithoutPublishingEvent() throws Exception {
        mockMvc.perform(post("/webhooks/whatsapp")
                        .contentType("application/json")
                        .header("X-Hub-Signature-256", "sha256=bad")
                        .content(webhookBody()))
                .andExpect(status().isOk());

        verify(eventPublisher, never()).publishEvent(any());
    }

    private String webhookBody() {
        return """
                {
                  "object": "whatsapp_business_account",
                  "entry": [{
                    "id": "WABA_1",
                    "changes": [{
                      "field": "messages",
                      "value": {
                        "messaging_product": "whatsapp",
                        "metadata": {"phone_number_id": "PHONE_1"},
                        "messages": [{
                          "id": "wamid.1",
                          "from": "919876543210",
                          "type": "text",
                          "text": {"body": "Hello"}
                        }]
                      }
                    }]
                  }]
                }
                """;
    }

    private String signature(byte[] body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(APP_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body));
    }
}
