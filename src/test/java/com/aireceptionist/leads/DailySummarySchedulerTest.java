package com.aireceptionist.leads;

import com.aireceptionist.AbstractIntegrationTest;
import com.aireceptionist.common.audit.AuditEventType;
import com.aireceptionist.common.audit.AuditLogEntry;
import com.aireceptionist.common.audit.AuditLogRepository;
import com.aireceptionist.common.multitenancy.TenantContext;
import com.aireceptionist.leads.domain.Lead;
import com.aireceptionist.leads.domain.LeadChannel;
import com.aireceptionist.leads.repository.LeadRepository;
import com.aireceptionist.leads.service.DailySummaryJob;
import com.aireceptionist.tenant.adapter.out.persistence.JpaTenantRepository;
import com.aireceptionist.tenant.domain.BusinessTenant;
import com.aireceptionist.tenant.port.out.TenantRegistrationRepository;
import com.aireceptionist.whatsapp.domain.WhatsAppMessage;
import com.aireceptionist.whatsapp.repository.WhatsAppMessageRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@TestPropertySource(properties = "features.daily-summary.enabled=true")
class DailySummarySchedulerTest extends AbstractIntegrationTest {

    @Autowired DailySummaryJob dailySummaryJob;
    @Autowired TenantRegistrationRepository tenantRepository;
    @Autowired JpaTenantRepository jpaTenantRepository;
    @Autowired WhatsAppMessageRepository whatsAppMessageRepository;
    @Autowired LeadRepository leadRepository;
    @Autowired AuditLogRepository auditLogRepository;
    @Autowired RestTemplate restTemplate;

    @Value("${app.whatsapp.api-url}")
    String whatsAppApiUrl;

    private final List<UUID> seededTenantIds = new ArrayList<>();

    @AfterEach
    void cleanUpSeededTenants() {
        jpaTenantRepository.deleteAllById(seededTenantIds);
        seededTenantIds.clear();
    }

    private UUID seedLiveTenant(String suffix) {
        BusinessTenant tenant = BusinessTenant.register(
                "Shop " + suffix, "Owner " + suffix, "+91900000" + suffix,
                "+91800000" + suffix, "owner" + suffix + "@example.com", "hash");
        tenant.connectWhatsApp("waba-" + suffix, "phone-number-id-" + suffix);
        UUID tenantId = tenantRepository.save(tenant).getId();
        seededTenantIds.add(tenantId);
        return tenantId;
    }

    private static String uniqueSuffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    @Test
    void sendsSummaryForActiveTenantAndSkipsTenantWithNoInteractions() {
        String activeSuffix = uniqueSuffix();
        String quietSuffix = uniqueSuffix();
        UUID activeTenantId = seedLiveTenant(activeSuffix);
        seedLiveTenant(quietSuffix);

        Instant recently = Instant.now().minus(2, ChronoUnit.HOURS);

        TenantContext.setCurrentTenant(activeTenantId.toString());
        try {
            whatsAppMessageRepository.save(WhatsAppMessage.inboundCustomer(
                    activeTenantId, "wamid-" + activeSuffix, "+919111000001", "Do you have Samsung phones?"));
            leadRepository.save(Lead.create(activeTenantId, "Ravi Kumar", "+919111000001",
                    "Samsung Galaxy S24", LeadChannel.WHATSAPP, "WHATSAPP", recently));
        } finally {
            TenantContext.clear();
        }
        auditLogRepository.save(new AuditLogEntry(UUID.randomUUID(), activeTenantId,
                AuditEventType.AUDIT_LOW_CONFIDENCE, BigDecimal.valueOf(0.20), "hash-" + activeSuffix, recently));

        MockRestServiceServer mockServer = MockRestServiceServer.bindTo(restTemplate).build();
        mockServer.expect(requestTo(whatsAppApiUrl + "/phone-number-id-" + activeSuffix + "/messages"))
                .andExpect(method(POST))
                .andExpect(content().string(containsString("\"to\":\"+91900000" + activeSuffix + "\"")))
                .andExpect(content().string(containsString("Ravi Kumar")))
                .andExpect(content().string(containsString("Samsung Galaxy S24")))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        dailySummaryJob.sendDailySummaries();

        mockServer.verify();
    }
}
