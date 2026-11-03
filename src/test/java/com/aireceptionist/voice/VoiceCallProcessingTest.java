package com.aireceptionist.voice;

import com.aireceptionist.AbstractIntegrationTest;
import com.aireceptionist.common.ai.AiChatPort;
import com.aireceptionist.common.ai.AiResponseResult;
import com.aireceptionist.common.multitenancy.TenantContext;
import com.aireceptionist.voice.domain.VoiceCall;
import com.aireceptionist.voice.domain.VoiceCallStatus;
import com.aireceptionist.voice.repository.VoiceCallRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Story 6.2 (AC1-AC6): simulates Exotel's call + transcript webhooks end to end against the real
 * AI pipeline (LlmService, KB search, confidence-gate aspect) with only the LLM port stubbed.
 */
@AutoConfigureMockMvc
class VoiceCallProcessingTest extends AbstractIntegrationTest {

    static final AtomicReference<AiResponseResult> NEXT_LLM_RESULT = new AtomicReference<>();
    static final List<String> LLM_USER_MESSAGES = new CopyOnWriteArrayList<>();

    @TestConfiguration
    static class StubLlmConfig {
        @Bean
        @Primary
        AiChatPort stubAiChatPort() {
            return (system, user) -> {
                LLM_USER_MESSAGES.add(user);
                return NEXT_LLM_RESULT.get();
            };
        }
    }

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired VoiceCallRepository voiceCallRepository;

    @Value("${app.exotel.shared-secret}")
    String sharedSecret;

    private UUID tenantId;
    private String businessPhone;
    private final String caller = "+919812345678";

    @BeforeEach
    void seed() {
        tenantId = UUID.randomUUID();
        businessPhone = "+91" + (System.nanoTime() % 10_000_000_000L);
        jdbcTemplate.update(
                "INSERT INTO tenants (id, business_name, phone_number, tier, status) VALUES (?, ?, ?, 'PRO', 'LIVE')",
                tenantId, "Gupta Electronics", businessPhone);
        NEXT_LLM_RESULT.set(new AiResponseResult("The Samsung S24 costs 74999 rupees.", 0.92));
        LLM_USER_MESSAGES.clear();
    }

    private String sign(String... fields) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec(sharedSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
        return HexFormat.of().formatHex(mac.doFinal(String.join("", fields).getBytes(StandardCharsets.UTF_8)));
    }

    private ResultActions postTranscript(String callSid, String text) throws Exception {
        return mockMvc.perform(post("/webhooks/voice/transcript")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("CallSid", callSid)
                .param("From", caller)
                .param("To", businessPhone)
                .param("TranscriptionText", text)
                .header("X-Exotel-Signature", sign(callSid, caller, businessPhone, text)));
    }

    private VoiceCall loadCall(String callSid) {
        TenantContext.setCurrentTenant(tenantId.toString());
        try {
            return voiceCallRepository.findByCallSid(callSid).orElseThrow();
        } finally {
            TenantContext.clear();
        }
    }

    private String uniqueQuery(String base) {
        // LlmService caches responses per (tenant, query); tenant is fresh per test so this is belt-and-braces.
        return base + " " + UUID.randomUUID().toString().substring(0, 6);
    }

    @Test
    void incomingCallPlaysGreetingThenRecordsFirstUtterance() throws Exception {
        String callSid = "CA-" + UUID.randomUUID();
        String direction = "inbound";
        String callStatus = "ringing";

        mockMvc.perform(post("/webhooks/voice")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("CallSid", callSid)
                        .param("From", caller)
                        .param("To", businessPhone)
                        .param("Direction", direction)
                        .param("Status", callStatus)
                        .header("X-Exotel-Signature", sign(callSid, caller, businessPhone, direction, callStatus)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(
                        "<Say voice=\"woman\" language=\"en-in\">Hello! Welcome to Gupta Electronics. How can I help you today?</Say>"
                                + "<Record action=\"/webhooks/voice/transcript\" maxLength=\"30\" transcribe=\"true\"/>")));
    }

    @Test
    void transcriptIsAnsweredByLlmAndRecordedOnVoiceCall() throws Exception {
        String callSid = "CA-" + UUID.randomUUID();
        String query = uniqueQuery("What is the price of Samsung S24?");

        postTranscript(callSid, query)
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("The Samsung S24 costs 74999 rupees.")))
                .andExpect(content().string(containsString("<Record action=\"/webhooks/voice/transcript\"")));

        assertThat(LLM_USER_MESSAGES).anyMatch(m -> m.contains(query));

        VoiceCall call = loadCall(callSid);
        assertThat(call.getTenantId()).isEqualTo(tenantId);
        assertThat(call.getCallerPhone()).isEqualTo(caller);
        assertThat(call.getAiResponseCount()).isEqualTo(1);
        assertThat(call.getTranscript())
                .isEqualTo("Caller: " + query + "\nAI: The Samsung S24 costs 74999 rupees.\n");
        assertThat(call.getLastConfidence()).isEqualByComparingTo(new BigDecimal("0.92"));
        assertThat(call.getStatus()).isEqualTo(VoiceCallStatus.HANDLED);
        assertThat(call.getDurationSeconds()).isNotNull().isGreaterThanOrEqualTo(0);
        assertThat(call.getStartedAt()).isNotNull();
    }

    @Test
    void secondTurnAppendsTranscriptAndIncrementsCount() throws Exception {
        String callSid = "CA-" + UUID.randomUUID();
        postTranscript(callSid, uniqueQuery("Do you have iPhones?")).andExpect(status().isOk());
        NEXT_LLM_RESULT.set(new AiResponseResult("We are open 10am to 9pm.", 0.88));
        postTranscript(callSid, uniqueQuery("What are your timings?")).andExpect(status().isOk());

        VoiceCall call = loadCall(callSid);
        assertThat(call.getAiResponseCount()).isEqualTo(2);
        assertThat(call.getTranscript()).contains("Do you have iPhones?").contains("We are open 10am to 9pm.");
        assertThat(call.getLastConfidence()).isEqualByComparingTo(new BigDecimal("0.88"));
    }

    @Test
    void lowConfidenceIsGatedAndLlmAnswerIsNotSpoken() throws Exception {
        String callSid = "CA-" + UUID.randomUUID();
        NEXT_LLM_RESULT.set(new AiResponseResult("Made-up uncertain answer", 0.30));

        postTranscript(callSid, uniqueQuery("Do you repair drones?"))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("Made-up uncertain answer"))));

        VoiceCall call = loadCall(callSid);
        assertThat(call.getLastConfidence()).isEqualByComparingTo(new BigDecimal("0.30"));
        assertThat(call.getTranscript()).doesNotContain("Made-up uncertain answer");

        // audit_log carries RLS (V9) — scope the read to this tenant on one connection.
        Integer lowConfidenceAudits = jdbcTemplate.execute((org.springframework.jdbc.core.ConnectionCallback<Integer>) c -> {
            try (var set = c.prepareStatement("SELECT set_config('app.current_tenant', ?, false)")) {
                set.setString(1, tenantId.toString());
                set.execute();
            }
            try (var q = c.prepareStatement(
                    "SELECT COUNT(*) FROM audit_log WHERE tenant_id = ? AND event_type = 'AUDIT_LOW_CONFIDENCE'")) {
                q.setObject(1, tenantId);
                try (var rs = q.executeQuery()) {
                    rs.next();
                    return rs.getInt(1);
                }
            } finally {
                c.createStatement().execute("RESET app.current_tenant");
            }
        });
        assertThat(lowConfidenceAudits).isPositive();
    }

    @Test
    void blankTranscriptRepromptsWithoutCallingLlm() throws Exception {
        String callSid = "CA-" + UUID.randomUUID();

        postTranscript(callSid, "   ")
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("didn't catch that")))
                .andExpect(content().string(containsString("<Record")));

        assertThat(LLM_USER_MESSAGES).isEmpty();
    }

    @Test
    void invalidSignatureIsRejectedWithoutProcessing() throws Exception {
        String callSid = "CA-" + UUID.randomUUID();

        mockMvc.perform(post("/webhooks/voice/transcript")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("CallSid", callSid)
                        .param("From", caller)
                        .param("To", businessPhone)
                        .param("TranscriptionText", "hello")
                        .header("X-Exotel-Signature", "forged"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("unable to process")))
                .andExpect(content().string(containsString("<Hangup/>")));

        assertThat(LLM_USER_MESSAGES).isEmpty();
    }

    @Test
    void unknownAudioIdReturns404() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/webhooks/voice/audio/" + UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }
}
