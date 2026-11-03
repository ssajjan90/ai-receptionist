package com.aireceptionist.voice;

import com.aireceptionist.AbstractIntegrationTest;
import com.aireceptionist.common.ai.AiChatPort;
import com.aireceptionist.common.ai.AiResponseResult;
import com.aireceptionist.common.exception.ExternalServiceException;
import com.aireceptionist.common.multitenancy.TenantContext;
import com.aireceptionist.common.security.JwtTokenProvider;
import com.aireceptionist.voice.domain.VoiceCall;
import com.aireceptionist.voice.domain.VoiceCallStatus;
import com.aireceptionist.voice.repository.VoiceCallRepository;
import com.aireceptionist.voice.service.ExotelApiClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.client.RestTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Story 6.3 (AC1, AC3-AC5): hold → transfer / timeout, with the Exotel REST API faked. */
@AutoConfigureMockMvc
class CallTransferTest extends AbstractIntegrationTest {

    static final List<String[]> EXOTEL_CONNECTS = new CopyOnWriteArrayList<>();
    static final AtomicBoolean EXOTEL_DOWN = new AtomicBoolean(false);

    @TestConfiguration
    static class FakeExotelConfig {
        @Bean
        @Primary
        ExotelApiClient fakeExotelApiClient() {
            return new ExotelApiClient(new RestTemplate(), "http://unused", "sid", "key", "token") {
                @Override
                public void connectCall(String callSid, String toPhone, String callerId) {
                    if (EXOTEL_DOWN.get()) {
                        throw new ExternalServiceException("Exotel call transfer failed");
                    }
                    EXOTEL_CONNECTS.add(new String[]{callSid, toPhone, callerId});
                }
            };
        }

        @Bean
        @Primary
        AiChatPort confidentAiChatPort() {
            return (system, user) -> new AiResponseResult("Sure, happy to help.", 0.95);
        }
    }

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired VoiceCallRepository voiceCallRepository;
    @Autowired StringRedisTemplate redisTemplate;
    @Autowired JwtTokenProvider tokenProvider;

    @Value("${app.exotel.shared-secret}")
    String sharedSecret;

    private UUID tenantId;
    private String businessPhone;
    private String ownerPhone;
    private final String caller = "+919822222222";

    @BeforeEach
    void seed() {
        ownerPhone = "+917" + String.format("%09d", System.nanoTime() % 1_000_000_000L);
        tenantId = seedTenant(ownerPhone);
        businessPhone = jdbcTemplate.queryForObject("SELECT phone_number FROM tenants WHERE id = ?", String.class, tenantId);
        EXOTEL_CONNECTS.clear();
        EXOTEL_DOWN.set(false);
    }

    private UUID seedTenant(String owner) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO tenants (id, business_name, phone_number, owner_phone, tier, status) VALUES (?, ?, ?, ?, 'PRO', 'LIVE')",
                id, "Transfer Test Shop", "+91" + (System.nanoTime() % 10_000_000_000L), owner);
        return id;
    }

    private String sign(String... fields) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec(sharedSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
        return HexFormat.of().formatHex(mac.doFinal(String.join("", fields).getBytes(StandardCharsets.UTF_8)));
    }

    private ResultActions askForHuman(String callSid) throws Exception {
        String text = "I want to talk to a real person";
        return mockMvc.perform(post("/webhooks/voice/transcript")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("CallSid", callSid).param("From", caller).param("To", businessPhone)
                .param("TranscriptionText", text)
                .header("X-Exotel-Signature", sign(callSid, caller, businessPhone, text)));
    }

    private ResultActions timeout(String callSid) throws Exception {
        return mockMvc.perform(post("/webhooks/voice/transfer-timeout")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("CallSid", callSid).param("From", caller).param("To", businessPhone)
                .header("X-Exotel-Signature", sign(callSid, caller, businessPhone)));
    }

    private ResultActions transfer(String callSid, UUID asTenant) throws Exception {
        String token = tokenProvider.generateToken(asTenant.toString(), asTenant.toString(), "OWNER", "PRO");
        return mockMvc.perform(post("/v1/voice/calls/" + callSid + "/transfer")
                .header("Authorization", "Bearer " + token));
    }

    private VoiceCall loadCall(String callSid) {
        TenantContext.setCurrentTenant(tenantId.toString());
        try {
            return voiceCallRepository.findByCallSid(callSid).orElseThrow();
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void humanRequestPlaysHoldMessageAndParksCallForTransfer() throws Exception {
        String callSid = "CA-" + UUID.randomUUID();

        askForHuman(callSid)
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Please hold while I connect you to our team member")))
                .andExpect(content().string(containsString("<Pause length=\"60\"/>")))
                .andExpect(content().string(containsString("<Redirect>/webhooks/voice/transfer-timeout</Redirect>")));

        assertThat(redisTemplate.opsForValue().get("voice:transfer:" + callSid)).isEqualTo(caller + "|" + businessPhone);
        assertThat(loadCall(callSid).getTranscript()).contains("real person").contains("Please hold");
    }

    @Test
    void ownerTransferBridgesCallToOwnerAndMarksTransferred() throws Exception {
        String callSid = "CA-" + UUID.randomUUID();
        askForHuman(callSid).andExpect(status().isOk());

        transfer(callSid, tenantId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("TRANSFERRED"));

        assertThat(EXOTEL_CONNECTS).hasSize(1);
        assertThat(EXOTEL_CONNECTS.get(0)).containsExactly(callSid, ownerPhone, businessPhone);
        VoiceCall call = loadCall(callSid);
        assertThat(call.getStatus()).isEqualTo(VoiceCallStatus.TRANSFERRED);
        assertThat(call.getEndedAt()).isNotNull();
        assertThat(redisTemplate.hasKey("voice:transfer:" + callSid)).isFalse();

        // The hold then ends: nothing more to say, no "no agent" message, status stays TRANSFERRED.
        timeout(callSid)
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("No agent"))));
        assertThat(loadCall(callSid).getStatus()).isEqualTo(VoiceCallStatus.TRANSFERRED);
    }

    @Test
    void timeoutWithoutTransferTellsCallerNoAgentAndMarksMissed() throws Exception {
        String callSid = "CA-" + UUID.randomUUID();
        askForHuman(callSid).andExpect(status().isOk());

        timeout(callSid)
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(
                        "No agent is available right now. I'll have someone WhatsApp you shortly.")))
                .andExpect(content().string(containsString("<Hangup/>")));

        VoiceCall call = loadCall(callSid);
        assertThat(call.getStatus()).isEqualTo(VoiceCallStatus.MISSED);
        assertThat(redisTemplate.hasKey("voice:transfer:" + callSid)).isFalse();

        // Too late to transfer now.
        transfer(callSid, tenantId).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("TRANSFER_NOT_PENDING"));
        assertThat(EXOTEL_CONNECTS).isEmpty();
    }

    @Test
    void anotherTenantCannotTransferTheCall() throws Exception {
        String callSid = "CA-" + UUID.randomUUID();
        askForHuman(callSid).andExpect(status().isOk());

        transfer(callSid, seedTenant("+916" + String.format("%09d", System.nanoTime() % 1_000_000_000L))).andExpect(status().isNotFound());

        assertThat(EXOTEL_CONNECTS).isEmpty();
        assertThat(redisTemplate.hasKey("voice:transfer:" + callSid)).isTrue();
    }

    @Test
    void exotelFailureReturns502AndKeepsCallWaitingForRetry() throws Exception {
        String callSid = "CA-" + UUID.randomUUID();
        askForHuman(callSid).andExpect(status().isOk());
        EXOTEL_DOWN.set(true);

        transfer(callSid, tenantId).andExpect(status().isBadGateway());

        assertThat(redisTemplate.hasKey("voice:transfer:" + callSid)).isTrue();
        assertThat(loadCall(callSid).getStatus()).isNotEqualTo(VoiceCallStatus.TRANSFERRED);
    }

    @Test
    void transferRequiresJwt() throws Exception {
        // App-wide, an unauthenticated POST is rejected with 403 (GETs get 401) — either way, no transfer.
        mockMvc.perform(post("/v1/voice/calls/CA-x/transfer")).andExpect(status().is4xxClientError());
        assertThat(EXOTEL_CONNECTS).isEmpty();
    }
}
