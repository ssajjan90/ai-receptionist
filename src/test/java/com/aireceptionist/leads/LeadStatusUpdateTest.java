package com.aireceptionist.leads;

import com.aireceptionist.AbstractIntegrationTest;
import com.aireceptionist.common.security.JwtTokenProvider;
import com.aireceptionist.leads.domain.Lead;
import com.aireceptionist.leads.domain.LeadChannel;
import com.aireceptionist.leads.domain.LeadStatus;
import com.aireceptionist.leads.repository.LeadRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class LeadStatusUpdateTest extends AbstractIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired JwtTokenProvider tokenProvider;
    @Autowired LeadRepository leadRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    private UUID seedTenant() {
        UUID tenantId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO tenants (id, business_name, phone_number, tier, status) VALUES (?, ?, ?, 'PRO', 'ACTIVE')",
                tenantId, "Test Business", "+91" + System.nanoTime() % 10_000_000_000L);
        return tenantId;
    }

    @Test
    void patchUpdatesLeadStatusAndPersists() throws Exception {
        UUID tenantId = seedTenant();
        Lead lead = Lead.create(tenantId, "Ravi Kumar", "+919876543210",
                "Samsung Galaxy S24", LeadChannel.WHATSAPP, "WHATSAPP", Instant.now());
        leadRepository.save(lead);
        String token = tokenProvider.generateToken(tenantId.toString(), tenantId.toString(), "OWNER", "PRO");

        mockMvc.perform(patch("/v1/tenants/" + tenantId + "/leads/" + lead.getId())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"CONTACTED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CONTACTED"));

        assertThat(leadRepository.findById(lead.getId()))
                .isPresent()
                .hasValueSatisfying(l -> assertThat(l.getStatus()).isEqualTo(LeadStatus.CONTACTED));
    }

    @Test
    void patchRejectsCrossTenantLeadUpdate() throws Exception {
        UUID ownerTenantId = seedTenant();
        UUID otherTenantId = seedTenant();
        Lead lead = Lead.create(ownerTenantId, "Ravi Kumar", "+919876543211",
                "Samsung Galaxy S24", LeadChannel.WHATSAPP, "WHATSAPP", Instant.now());
        leadRepository.save(lead);
        String token = tokenProvider.generateToken(otherTenantId.toString(), otherTenantId.toString(), "OWNER", "PRO");

        mockMvc.perform(patch("/v1/tenants/" + otherTenantId + "/leads/" + lead.getId())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"CONTACTED\"}"))
                .andExpect(status().isForbidden());

        assertThat(leadRepository.findById(lead.getId()))
                .isPresent()
                .hasValueSatisfying(l -> assertThat(l.getStatus()).isEqualTo(LeadStatus.NEW));
    }
}
