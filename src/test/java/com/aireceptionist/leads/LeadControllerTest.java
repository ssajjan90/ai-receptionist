package com.aireceptionist.leads;

import com.aireceptionist.common.api.GlobalExceptionHandler;
import com.aireceptionist.common.security.TenantAwareAuthentication;
import com.aireceptionist.leads.api.LeadController;
import com.aireceptionist.leads.domain.Lead;
import com.aireceptionist.leads.domain.LeadChannel;
import com.aireceptionist.leads.domain.LeadStatus;
import com.aireceptionist.leads.dto.LeadMapper;
import com.aireceptionist.leads.service.LeadService;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class LeadControllerTest {

    private final LeadService leadService = mock(LeadService.class);
    private final LeadMapper leadMapper = new LeadMapper();
    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new LeadController(leadService, leadMapper))
            .setControllerAdvice(new GlobalExceptionHandler())
            .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver())
            .build();

    private static Lead newLead(UUID tenantId, LeadStatus status) {
        Lead lead = Lead.create(tenantId, "Ravi Kumar", "+919876543210",
                "Samsung Galaxy S24", LeadChannel.WHATSAPP, "WHATSAPP", Instant.now());
        lead.updateStatus(status);
        return lead;
    }

    private TenantAwareAuthentication authentication(UUID tenantId) {
        return new TenantAwareAuthentication(tenantId.toString(), tenantId.toString(), "OWNER", "BASIC");
    }

    @Test
    void listsLeadsForTenantOrderedByCreatedAtDesc() throws Exception {
        UUID tenantId = UUID.randomUUID();
        Lead lead = newLead(tenantId, LeadStatus.NEW);
        Page<Lead> page = new PageImpl<>(List.of(lead), PageRequest.of(0, 20), 1);
        when(leadService.findLeads(eq(tenantId), isNull(), any())).thenReturn(page);

        mockMvc.perform(get("/v1/tenants/{tenantId}/leads", tenantId)
                        .principal(authentication(tenantId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].customerName").value("Ravi Kumar"))
                .andExpect(jsonPath("$.data.content[0].erased").value(false));
    }

    @Test
    void filtersLeadsByStatus() throws Exception {
        UUID tenantId = UUID.randomUUID();
        Lead lead = newLead(tenantId, LeadStatus.CONTACTED);
        Page<Lead> page = new PageImpl<>(List.of(lead), PageRequest.of(0, 20), 1);
        when(leadService.findLeads(eq(tenantId), eq(LeadStatus.CONTACTED), any())).thenReturn(page);

        mockMvc.perform(get("/v1/tenants/{tenantId}/leads", tenantId)
                        .param("status", "CONTACTED")
                        .principal(authentication(tenantId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].status").value("CONTACTED"));
    }

    @Test
    void rejectsListForMismatchedTenant() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID otherTenantId = UUID.randomUUID();

        mockMvc.perform(get("/v1/tenants/{tenantId}/leads", tenantId)
                        .principal(authentication(otherTenantId)))
                .andExpect(status().isForbidden());
    }

    @Test
    void updatesLeadStatusViaPatch() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID leadId = UUID.randomUUID();
        Lead lead = newLead(tenantId, LeadStatus.CONTACTED);
        when(leadService.updateStatus(eq(tenantId), eq(leadId), eq(LeadStatus.CONTACTED))).thenReturn(lead);

        mockMvc.perform(patch("/v1/tenants/{tenantId}/leads/{leadId}", tenantId, leadId)
                        .principal(authentication(tenantId))
                        .contentType(APPLICATION_JSON)
                        .content("{\"status\":\"CONTACTED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CONTACTED"));
    }

    @Test
    void rejectsPatchForMismatchedTenant() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID otherTenantId = UUID.randomUUID();
        UUID leadId = UUID.randomUUID();

        mockMvc.perform(patch("/v1/tenants/{tenantId}/leads/{leadId}", tenantId, leadId)
                        .principal(authentication(otherTenantId))
                        .contentType(APPLICATION_JSON)
                        .content("{\"status\":\"CONTACTED\"}"))
                .andExpect(status().isForbidden());
    }
}
