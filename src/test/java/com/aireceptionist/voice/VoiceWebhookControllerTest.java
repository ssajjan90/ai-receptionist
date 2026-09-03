package com.aireceptionist.voice;

import com.aireceptionist.AbstractIntegrationTest;
import com.aireceptionist.common.multitenancy.TenantContext;
import com.aireceptionist.voice.domain.VoiceCall;
import com.aireceptionist.voice.repository.VoiceCallRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Story 6.1 (AC1-AC4). {@code @WebMvcTest} was tried first (per Task 6) but doesn't work in this
 * app: {@code AdminWebConfig implements WebMvcConfigurer} and depends on a JPA repository, so it's
 * always pulled into any web-slice context and fails to wire — a pre-existing, app-wide obstacle,
 * not specific to this controller. Falling back to this codebase's established webhook-controller
 * test style ({@code AbstractIntegrationTest} + {@code @AutoConfigureMockMvc}), matching
 * {@code AdminControllerTest} et al.
 */
@AutoConfigureMockMvc
class VoiceWebhookControllerTest extends AbstractIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired VoiceCallRepository voiceCallRepository;

    @Value("${app.exotel.shared-secret}")
    String sharedSecret;

    private record TenantSeed(UUID tenantId, String businessPhone) {
    }

    private TenantSeed seedTenant(String tier) {
        UUID tenantId = UUID.randomUUID();
        String businessPhone = "+91" + System.nanoTime() % 10_000_000_000L;
        jdbcTemplate.update(
                "INSERT INTO tenants (id, business_name, phone_number, tier, status) VALUES (?, ?, ?, ?, 'LIVE')",
                tenantId, "Voice Webhook Test Business", businessPhone, tier);
        return new TenantSeed(tenantId, businessPhone);
    }

    // voice_calls carries RLS (V8/W99): the async listener correctly scopes its own save
    // (ExotelCallService), but this verification read runs on the test thread, which has no
    // tenant context of its own — it needs the same scope.
    private Optional<VoiceCall> findVoiceCallScoped(UUID tenantId, String callSid) {
        TenantContext.setCurrentTenant(tenantId.toString());
        try {
            return voiceCallRepository.findByCallSid(callSid);
        } finally {
            TenantContext.clear();
        }
    }

    private String sign(String callSid, String from, String to, String direction, String status) throws Exception {
        String canonical = callSid + from + to + direction + status;
        Mac mac = Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec(sharedSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
        return HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void proTierAcceptsCallAndRecordsVoiceCall() throws Exception {
        TenantSeed tenant = seedTenant("PRO");
        String to = tenant.businessPhone();
        String callSid = "CA-" + UUID.randomUUID();
        String from = "+919876543210";
        String direction = "inbound";
        String status = "ringing";

        mockMvc.perform(post("/webhooks/voice")
                        .contentType(org.springframework.http.MediaType.APPLICATION_FORM_URLENCODED)
                        .param("CallSid", callSid)
                        .param("From", from)
                        .param("To", to)
                        .param("Direction", direction)
                        .param("Status", status)
                        .header("X-Exotel-Signature", sign(callSid, from, to, direction, status)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("<Record")));

        await().atMost(15, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(findVoiceCallScoped(tenant.tenantId(), callSid)).isPresent());
    }

    @Test
    void basicTierRedirectsToWhatsAppWithoutRecordingVoiceCall() throws Exception {
        TenantSeed tenant = seedTenant("BASIC");
        String to = tenant.businessPhone();
        String callSid = "CA-" + UUID.randomUUID();
        String from = "+919876543210";
        String direction = "inbound";
        String status = "ringing";

        mockMvc.perform(post("/webhooks/voice")
                        .contentType(org.springframework.http.MediaType.APPLICATION_FORM_URLENCODED)
                        .param("CallSid", callSid)
                        .param("From", from)
                        .param("To", to)
                        .param("Direction", direction)
                        .param("Status", status)
                        .header("X-Exotel-Signature", sign(callSid, from, to, direction, status)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("WhatsApp")))
                .andExpect(content().string(not(containsString("<Record"))));

        // No async event published for BASIC — give any accidental listener a moment, then assert absence.
        Thread.sleep(500);
        assertThat(findVoiceCallScoped(tenant.tenantId(), callSid)).isEmpty();
    }

    @Test
    void invalidSignatureReturns200WithNoTenantLookupOrVoiceCall() throws Exception {
        TenantSeed tenant = seedTenant("PRO");
        String to = tenant.businessPhone();
        String callSid = "CA-" + UUID.randomUUID();

        mockMvc.perform(post("/webhooks/voice")
                        .contentType(org.springframework.http.MediaType.APPLICATION_FORM_URLENCODED)
                        .param("CallSid", callSid)
                        .param("From", "+919876543210")
                        .param("To", to)
                        .param("Direction", "inbound")
                        .param("Status", "ringing")
                        .header("X-Exotel-Signature", "not-a-valid-signature"))
                .andExpect(status().isOk());

        Thread.sleep(500);
        assertThat(findVoiceCallScoped(tenant.tenantId(), callSid)).isEmpty();
    }

    @Test
    void missingSignatureReturns200WithNoVoiceCall() throws Exception {
        TenantSeed tenant = seedTenant("PRO");
        String to = tenant.businessPhone();
        String callSid = "CA-" + UUID.randomUUID();

        mockMvc.perform(post("/webhooks/voice")
                        .contentType(org.springframework.http.MediaType.APPLICATION_FORM_URLENCODED)
                        .param("CallSid", callSid)
                        .param("From", "+919876543210")
                        .param("To", to)
                        .param("Direction", "inbound")
                        .param("Status", "ringing"))
                .andExpect(status().isOk());

        Thread.sleep(500);
        assertThat(findVoiceCallScoped(tenant.tenantId(), callSid)).isEmpty();
    }

    @Test
    void unknownPhoneReturns200WithGenericMessage() throws Exception {
        String callSid = "CA-" + UUID.randomUUID();
        String from = "+919876543210";
        String to = "+910000000000";
        String direction = "inbound";
        String status = "ringing";

        mockMvc.perform(post("/webhooks/voice")
                        .contentType(org.springframework.http.MediaType.APPLICATION_FORM_URLENCODED)
                        .param("CallSid", callSid)
                        .param("From", from)
                        .param("To", to)
                        .param("Direction", direction)
                        .param("Status", status)
                        .header("X-Exotel-Signature", sign(callSid, from, to, direction, status)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("unable to process")));
    }
}
