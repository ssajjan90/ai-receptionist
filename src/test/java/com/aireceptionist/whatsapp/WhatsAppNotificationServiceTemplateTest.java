package com.aireceptionist.whatsapp;

import com.aireceptionist.tenant.port.in.GetTenantPhoneNumberIdUseCase;
import com.aireceptionist.whatsapp.service.WhatsAppNotificationService;
import com.aireceptionist.whatsapp.service.WhatsAppQueueService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** W70: covers the new template-message send path, kept as a plain-Mockito unit test (no
 * Testcontainers) since it only needs to verify the outbound HTTP payload shape. */
class WhatsAppNotificationServiceTemplateTest {

    private final RestTemplate restTemplate = new RestTemplate();
    private final WhatsAppQueueService queueService = mock(WhatsAppQueueService.class);
    private final GetTenantPhoneNumberIdUseCase phoneNumberIdResolver = mock(GetTenantPhoneNumberIdUseCase.class);
    private final MockRestServiceServer mockServer = MockRestServiceServer.bindTo(restTemplate).build();

    private final WhatsAppNotificationService service = new WhatsAppNotificationService(
            restTemplate, queueService, phoneNumberIdResolver,
            "https://graph.facebook.com/v19.0", "test-access-token");

    @Test
    void sendTemplateMessageBuildsMetaTemplatePayload() {
        UUID tenantId = UUID.randomUUID();
        when(phoneNumberIdResolver.findPhoneNumberId(tenantId)).thenReturn(Optional.of("phone-id-1"));

        mockServer.expect(requestTo("https://graph.facebook.com/v19.0/phone-id-1/messages"))
                .andExpect(method(POST))
                .andExpect(content().string(allOf(
                        containsString("\"type\":\"template\""),
                        containsString("\"name\":\"account_suspended\""),
                        containsString("\"code\":\"en\""),
                        containsString("Suresh Stores"))))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        service.sendTemplateMessage(tenantId.toString(), "+919876543210", "account_suspended", "en",
                List.of("Suresh Stores"));

        mockServer.verify();
        verifyNoInteractions(queueService);
    }
}
