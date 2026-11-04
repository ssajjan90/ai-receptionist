package com.aireceptionist.tenant;

import com.aireceptionist.common.exception.ExternalServiceException;
import com.aireceptionist.tenant.adapter.out.notification.WhatsAppOwnerNotificationAdapter;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** MVP readiness: owner OTPs go out as a WhatsApp authentication template from the platform number. */
class WhatsAppOtpDeliveryTest {

    private final RestTemplate restTemplate = new RestTemplate();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();

    private WhatsAppOwnerNotificationAdapter adapter(String platformPhoneId) {
        return new WhatsAppOwnerNotificationAdapter(restTemplate, "https://graph.facebook.com/v19.0", "token",
                platformPhoneId, "otp_login", "en", false);
    }

    @Test
    void sendsAuthenticationTemplateWithCodeInBodyAndCopyButton() {
        server.expect(requestTo("https://graph.facebook.com/v19.0/platform-123/messages"))
                .andExpect(method(POST))
                .andExpect(header("Authorization", "Bearer token"))
                .andExpect(content().string(allOf(
                        containsString("\"to\":\"+919876543210\""),
                        containsString("\"type\":\"template\""),
                        containsString("\"name\":\"otp_login\""),
                        containsString("\"type\":\"body\""),
                        containsString("\"sub_type\":\"url\""),
                        containsString("\"text\":\"482913\""))))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        adapter("platform-123").sendOtp("+919876543210", "482913");

        server.verify();
    }

    @Test
    void metaFailureSurfacesSoTheOwnerCanRetry() {
        server.expect(requestTo("https://graph.facebook.com/v19.0/platform-123/messages")).andRespond(withServerError());

        assertThatThrownBy(() -> adapter("platform-123").sendOtp("+919876543210", "482913"))
                .isInstanceOf(ExternalServiceException.class);
    }

    @Test
    void unconfiguredPlatformNumberSendsNothing() {
        adapter("").sendOtp("+919876543210", "482913");
        server.verify(); // no request expected, none made
    }
}
