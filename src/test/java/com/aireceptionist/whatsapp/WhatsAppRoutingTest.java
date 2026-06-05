package com.aireceptionist.whatsapp;

import com.aireceptionist.tenant.port.in.ResolveTenantByWhatsAppPhoneUseCase;
import com.aireceptionist.tenant.port.in.ResolvedTenantWhatsAppRoute;
import com.aireceptionist.whatsapp.adapter.in.web.WhatsAppWebhookController;
import com.aireceptionist.whatsapp.event.InboundWhatsAppMessageEvent;
import com.aireceptionist.whatsapp.event.OwnerCommandReceivedEvent;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class WhatsAppRoutingTest {

    private static final String APP_SECRET = "test-secret";
    private static final String OWNER_PHONE = "910000000001";
    private static final String CUSTOMER_PHONE = "919876543210";

    private final ResolveTenantByWhatsAppPhoneUseCase resolver = mock(ResolveTenantByWhatsAppPhoneUseCase.class);
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new WhatsAppWebhookController(
            "verify-token",
            APP_SECRET,
            new ObjectMapper(),
            resolver,
            eventPublisher
    )).build();

    @Test
    void customerMessagePublishesInboundWhatsAppMessageEvent() throws Exception {
        UUID tenantId = UUID.randomUUID();
        byte[] body = webhookBody(CUSTOMER_PHONE).getBytes(StandardCharsets.UTF_8);
        when(resolver.resolveByPhoneNumberId("PHONE_1"))
                .thenReturn(Optional.of(new ResolvedTenantWhatsAppRoute(tenantId, "PHONE_1", OWNER_PHONE)));

        mockMvc.perform(post("/webhooks/whatsapp")
                        .contentType("application/json")
                        .header("X-Hub-Signature-256", signature(body))
                        .content(body))
                .andExpect(status().isOk());

        ArgumentCaptor<InboundWhatsAppMessageEvent> captor = ArgumentCaptor.forClass(InboundWhatsAppMessageEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().getTenantIdValue()).isEqualTo(tenantId);
        assertThat(captor.getValue().getSenderPhone()).isEqualTo(CUSTOMER_PHONE);
        assertThat(captor.getValue().getMessageText()).isEqualTo("Hello");
    }

    @Test
    void ownerPhonePublishesOwnerCommandReceivedEvent() throws Exception {
        UUID tenantId = UUID.randomUUID();
        byte[] body = webhookBody(OWNER_PHONE).getBytes(StandardCharsets.UTF_8);
        when(resolver.resolveByPhoneNumberId("PHONE_1"))
                .thenReturn(Optional.of(new ResolvedTenantWhatsAppRoute(tenantId, "PHONE_1", OWNER_PHONE)));

        mockMvc.perform(post("/webhooks/whatsapp")
                        .contentType("application/json")
                        .header("X-Hub-Signature-256", signature(body))
                        .content(body))
                .andExpect(status().isOk());

        ArgumentCaptor<OwnerCommandReceivedEvent> captor = ArgumentCaptor.forClass(OwnerCommandReceivedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().getTenantId()).isEqualTo(tenantId.toString());
        assertThat(captor.getValue().getSenderPhone()).isEqualTo(OWNER_PHONE);
        assertThat(captor.getValue().getRawMessage()).isEqualTo("Hello");
    }

    @Test
    void unknownPhoneNumberIdReturns200WithoutPublishingEvent() throws Exception {
        byte[] body = webhookBody(CUSTOMER_PHONE).getBytes(StandardCharsets.UTF_8);
        when(resolver.resolveByPhoneNumberId("PHONE_1")).thenReturn(Optional.empty());

        mockMvc.perform(post("/webhooks/whatsapp")
                        .contentType("application/json")
                        .header("X-Hub-Signature-256", signature(body))
                        .content(body))
                .andExpect(status().isOk());

        verify(eventPublisher, never()).publishEvent(new Object());
    }

    private String webhookBody(String senderPhone) {
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
                          "from": "%s",
                          "type": "text",
                          "text": {"body": "Hello"}
                        }]
                      }
                    }]
                  }]
                }
                """.formatted(senderPhone);
    }

    private String signature(byte[] body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(APP_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body));
    }
}
