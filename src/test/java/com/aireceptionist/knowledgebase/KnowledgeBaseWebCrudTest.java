package com.aireceptionist.knowledgebase;

import com.aireceptionist.AbstractIntegrationTest;
import com.aireceptionist.common.security.JwtTokenProvider;
import com.aireceptionist.knowledgebase.domain.EntryType;
import com.aireceptionist.knowledgebase.dto.CreateKnowledgeEntryRequest;
import com.aireceptionist.knowledgebase.dto.UpdateKnowledgeEntryRequest;
import com.aireceptionist.knowledgebase.repository.KnowledgeEntryRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class KnowledgeBaseWebCrudTest extends AbstractIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired JwtTokenProvider tokenProvider;
    @Autowired KnowledgeEntryRepository entryRepository;
    @Autowired StringRedisTemplate redisTemplate;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clearData() {
        entryRepository.deleteAll();
    }

    @Test
    void createEntryPersistsToDbAndCanBeRetrievedAndDeleted() throws Exception {
        UUID tenantId = UUID.randomUUID();
        // knowledge_entries.tenant_id has a FK to tenants(id) (V2), so a tenant row must exist first.
        jdbcTemplate.update(
                "INSERT INTO tenants (id, business_name, phone_number, tier, status) VALUES (?, ?, ?, 'PRO', 'ACTIVE')",
                tenantId, "KB Web CRUD Test Business", "+91" + System.nanoTime() % 10_000_000_000L);
        String token = tokenProvider.generateToken(tenantId.toString(), tenantId.toString(), "OWNER", "PRO");
        String base = "/v1/tenants/" + tenantId + "/knowledge-base";

        CreateKnowledgeEntryRequest createReq = new CreateKnowledgeEntryRequest(
                EntryType.PRODUCT, "Masala Tea", null, null, "25");

        MvcResult createResult = mockMvc.perform(post(base)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createReq)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.productName").value("Masala Tea"))
                .andExpect(jsonPath("$.data.price").value("25"))
                .andReturn();

        String responseBody = createResult.getResponse().getContentAsString();
        String entryId = objectMapper.readTree(responseBody).at("/data/id").asText();

        assertThat(entryRepository.findById(UUID.fromString(entryId))).isPresent();

        mockMvc.perform(get(base + "?page=0&size=20")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[0].productName").value("Masala Tea"));

        UpdateKnowledgeEntryRequest updateReq = new UpdateKnowledgeEntryRequest(null, null, null, "30");

        mockMvc.perform(put(base + "/" + entryId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.price").value("30"));

        assertThat(entryRepository.findById(UUID.fromString(entryId)))
                .isPresent()
                .hasValueSatisfying(e -> assertThat(e.getPrice()).isEqualTo("30"));

        mockMvc.perform(delete(base + "/" + entryId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        assertThat(entryRepository.findById(UUID.fromString(entryId))).isEmpty();
    }

    @Test
    void listEntriesRejectsCrossTenantsAccess() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID otherTenantId = UUID.randomUUID();
        String token = tokenProvider.generateToken(otherTenantId.toString(), otherTenantId.toString(), "OWNER", "PRO");

        mockMvc.perform(get("/v1/tenants/" + tenantId + "/knowledge-base")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));
    }
}
