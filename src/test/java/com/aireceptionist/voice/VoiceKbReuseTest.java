package com.aireceptionist.voice;

import com.aireceptionist.AbstractIntegrationTest;
import com.aireceptionist.common.ai.AiChatPort;
import com.aireceptionist.common.ai.AiResponseResult;
import com.aireceptionist.common.multitenancy.TenantContext;
import com.aireceptionist.knowledgebase.service.KnowledgeBaseService;
import com.aireceptionist.voice.domain.VoiceCall;
import com.aireceptionist.voice.repository.VoiceCallRepository;
import com.aireceptionist.whatsapp.event.OwnerCommandReceivedEvent;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Story 6.4 (AC1-AC3): an entry the owner adds over WhatsApp is what the voice channel answers
 * from — same {@code knowledge_entries} table, same KB/response caches, same invalidation.
 * The stubbed LLM simply reads back the "Knowledge Base:" section of the system prompt it was
 * given, so the spoken answer proves which KB data reached the voice pipeline.
 */
@AutoConfigureMockMvc
class VoiceKbReuseTest extends AbstractIntegrationTest {

    @TestConfiguration
    static class KbEchoLlmConfig {
        @Bean
        @Primary
        AiChatPort kbEchoAiChatPort() {
            return (system, user) -> {
                int kb = system.indexOf("Knowledge Base:");
                String answer = kb >= 0 ? system.substring(kb).replace('\n', ' ').trim() : "No KB data";
                return new AiResponseResult(answer, 0.95);
            };
        }
    }

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired VoiceCallRepository voiceCallRepository;
    @Autowired KnowledgeBaseService knowledgeBaseService;

    @Value("${app.exotel.shared-secret}")
    String sharedSecret;

    private String sign(String... fields) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec(sharedSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
        return HexFormat.of().formatHex(mac.doFinal(String.join("", fields).getBytes(StandardCharsets.UTF_8)));
    }

    private String askByVoice(String callSid, String caller, String businessPhone, String text) throws Exception {
        return mockMvc.perform(post("/webhooks/voice/transcript")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("CallSid", callSid).param("From", caller).param("To", businessPhone)
                        .param("TranscriptionText", text)
                        .header("X-Exotel-Signature", sign(callSid, caller, businessPhone, text)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private int kbEntryCount(UUID tenantId) {
        Integer count = jdbcTemplate.execute((ConnectionCallback<Integer>) c -> {
            try (var set = c.prepareStatement("SELECT set_config('app.current_tenant', ?, false)")) {
                set.setString(1, tenantId.toString());
                set.execute();
            }
            try (var q = c.prepareStatement("SELECT COUNT(*) FROM knowledge_entries WHERE tenant_id = ?")) {
                q.setObject(1, tenantId);
                try (var rs = q.executeQuery()) {
                    rs.next();
                    return rs.getInt(1);
                }
            } finally {
                c.createStatement().execute("RESET app.current_tenant");
            }
        });
        return count == null ? 0 : count;
    }

    @Test
    void kbEntryAddedViaWhatsAppIsUsedByVoiceAndUpdatesPropagate() throws Exception {
        UUID tenantId = UUID.randomUUID();
        String businessPhone = "+91" + (System.nanoTime() % 10_000_000_000L);
        String ownerPhone = "+915" + String.format("%09d", System.nanoTime() % 1_000_000_000L);
        jdbcTemplate.update(
                "INSERT INTO tenants (id, business_name, phone_number, owner_phone, tier, status) VALUES (?, ?, ?, ?, 'PRO', 'LIVE')",
                tenantId, "KB Reuse Mobiles", businessPhone, ownerPhone);

        // AC1: owner adds a product over WhatsApp.
        publishAndCommit(new OwnerCommandReceivedEvent(tenantId.toString(), "ADD: Redmi Note 13 | 18999",
                ownerPhone, Instant.now()));
        await().atMost(10, TimeUnit.SECONDS).until(() -> kbEntryCount(tenantId) == 1);

        String callSid = "CA-" + UUID.randomUUID();
        String caller = "+919833333333";
        String firstAnswer = askByVoice(callSid, caller, businessPhone, "price of Redmi Note 13");
        assertThat(firstAnswer).contains("Redmi Note 13").contains("18999");

        // AC2: an update (web-interface path: same KnowledgeBaseService) reaches voice well inside
        // 60s — the KB and LLM response caches are invalidated, so the very same question re-reads.
        TenantContext.setCurrentTenant(tenantId.toString());
        try {
            knowledgeBaseService.addOrUpdateProduct(tenantId, "Redmi Note 13", "17499");
        } finally {
            TenantContext.clear();
        }
        long start = System.nanoTime();
        await().atMost(60, TimeUnit.SECONDS).pollInterval(200, TimeUnit.MILLISECONDS).untilAsserted(() ->
                assertThat(askByVoice(callSid, caller, businessPhone, "price of Redmi Note 13")).contains("17499"));
        assertThat(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start)).isLessThan(60);

        // AC3: transcript (text) is stored on the call.
        TenantContext.setCurrentTenant(tenantId.toString());
        VoiceCall call;
        try {
            call = voiceCallRepository.findByCallSid(callSid).orElseThrow();
        } finally {
            TenantContext.clear();
        }
        assertThat(call.getTranscript()).contains("Caller: price of Redmi Note 13").contains("18999");
        assertThat(call.getAiResponseCount()).isGreaterThanOrEqualTo(2);
        assertThat(call.getLastConfidence()).isNotNull();
    }

    @Test
    void voiceCallsTableHasNoAudioColumn() {
        List<String> columns = jdbcTemplate.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_name = 'voice_calls' ORDER BY column_name",
                String.class);

        assertThat(columns).containsExactlyInAnyOrder(
                "id", "tenant_id", "call_sid", "caller_phone", "status", "started_at", "ended_at",
                "duration_seconds", "transcript", "ai_response_count", "last_confidence");
        assertThat(jdbcTemplate.queryForList(
                "SELECT data_type FROM information_schema.columns WHERE table_name = 'voice_calls'", String.class))
                .doesNotContain("bytea", "oid");
    }
}
