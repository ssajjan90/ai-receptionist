package com.aireceptionist.tenant.adapter.out.notification;

import com.aireceptionist.common.exception.ExternalServiceException;
import com.aireceptionist.tenant.port.out.OwnerNotificationPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

/**
 * Delivers the owner's registration/login OTP over WhatsApp using a Meta <b>authentication
 * template</b> (code in the body + copy-code button), sent from the <em>platform's</em> WhatsApp
 * number — at registration the owner hasn't connected their own number yet.
 *
 * <p>Pre-MVP this only logged the code (never delivered, and readable by anyone with log access).
 * The code is never logged now. If {@code app.whatsapp.platform-phone-number-id} is blank (local
 * dev/tests) nothing is sent; set {@code app.otp.log-code=true} (local profile only) to see codes
 * while developing. Prod requires the platform number and template (application-prod.yml).
 */
@Component
public class WhatsAppOwnerNotificationAdapter implements OwnerNotificationPort {

    private static final Logger log = LoggerFactory.getLogger(WhatsAppOwnerNotificationAdapter.class);

    private final RestTemplate restTemplate;
    private final String apiUrl;
    private final String accessToken;
    private final String platformPhoneNumberId;
    private final String templateName;
    private final String templateLanguage;
    private final boolean logCodeForLocalDev;

    public WhatsAppOwnerNotificationAdapter(RestTemplate restTemplate,
                                            @Value("${app.whatsapp.api-url}") String apiUrl,
                                            @Value("${app.whatsapp.access-token}") String accessToken,
                                            @Value("${app.whatsapp.platform-phone-number-id:}") String platformPhoneNumberId,
                                            @Value("${app.otp.whatsapp-template:otp_login}") String templateName,
                                            @Value("${app.otp.whatsapp-template-language:en}") String templateLanguage,
                                            @Value("${app.otp.log-code:false}") boolean logCodeForLocalDev) {
        this.restTemplate = restTemplate;
        this.apiUrl = apiUrl;
        this.accessToken = accessToken;
        this.platformPhoneNumberId = platformPhoneNumberId;
        this.templateName = templateName;
        this.templateLanguage = templateLanguage;
        this.logCodeForLocalDev = logCodeForLocalDev;
    }

    @Override
    public void sendOtp(String phone, String otp) {
        if (platformPhoneNumberId == null || platformPhoneNumberId.isBlank()) {
            if (logCodeForLocalDev) {
                log.info("[local dev] OTP for {} is {}", phone, otp);
            } else {
                log.warn("OTP delivery not configured (app.whatsapp.platform-phone-number-id is blank) — OTP for {} not sent", phone);
            }
            return;
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(accessToken);
        // Meta authentication-template shape: the code fills the body and the copy-code button.
        Map<String, Object> body = Map.of(
                "messaging_product", "whatsapp",
                "recipient_type", "individual",
                "to", phone,
                "type", "template",
                "template", Map.of(
                        "name", templateName,
                        "language", Map.of("code", templateLanguage),
                        "components", List.of(
                                Map.of("type", "body",
                                        "parameters", List.of(Map.of("type", "text", "text", otp))),
                                Map.of("type", "button", "sub_type", "url", "index", "0",
                                        "parameters", List.of(Map.of("type", "text", "text", otp))))));
        try {
            restTemplate.postForObject(apiUrl + "/" + platformPhoneNumberId + "/messages",
                    new HttpEntity<>(body, headers), String.class);
            log.info("OTP sent to {} via WhatsApp template '{}'", phone, templateName);
        } catch (RestClientException ex) {
            throw new ExternalServiceException("Could not send the verification code. Please try again.", ex);
        }
    }
}
