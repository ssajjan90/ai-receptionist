package com.aireceptionist.leads.service;

import com.aireceptionist.common.exception.AuthorizationException;
import com.aireceptionist.common.exception.NotFoundException;
import com.aireceptionist.leads.domain.Lead;
import com.aireceptionist.leads.domain.LeadStatus;
import com.aireceptionist.leads.dto.CreateLeadCommand;
import com.aireceptionist.leads.dto.LeadResponse;
import com.aireceptionist.leads.event.LeadCapturedEvent;
import com.aireceptionist.leads.repository.LeadRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@Transactional
public class LeadService {

    private static final Logger log = LoggerFactory.getLogger(LeadService.class);

    private final LeadRepository leadRepository;
    private final ApplicationEventPublisher eventPublisher;

    public LeadService(LeadRepository leadRepository, ApplicationEventPublisher eventPublisher) {
        this.leadRepository = leadRepository;
        this.eventPublisher = eventPublisher;
    }

    public LeadResponse createLead(CreateLeadCommand command, String ownerPhone) {
        if (command.tenantId() == null) {
            throw new IllegalArgumentException("tenantId must not be null");
        }

        Lead lead = Lead.create(
                command.tenantId(),
                command.customerName(),
                command.phone(),
                command.productIntent(),
                command.channel(),
                command.consentChannel(),
                command.consentedAt()
        );

        leadRepository.save(lead);
        log.info("Lead created id={} tenant={}", lead.getId(), command.tenantId());

        eventPublisher.publishEvent(new LeadCapturedEvent(
                command.tenantId().toString(),
                lead.getId(),
                command.customerName(),
                command.phone(),
                command.productIntent(),
                ownerPhone
        ));

        return new LeadResponse(
                lead.getId(),
                lead.getTenantId(),
                lead.getCustomerName(),
                lead.getPhone(),
                lead.getProductIntent(),
                lead.getChannel(),
                lead.getStatus(),
                lead.getConsentTimestamp(),
                lead.getCreatedAt(),
                Boolean.TRUE.equals(lead.getErased())
        );
    }

    @Transactional(readOnly = true)
    public Page<Lead> findLeads(UUID tenantId, LeadStatus status, Pageable pageable) {
        if (status == null) {
            return leadRepository.findByTenantIdOrderByCreatedAtDesc(tenantId, pageable);
        }
        return leadRepository.findByTenantIdAndStatus(tenantId, status, pageable);
    }

    public Lead updateStatus(UUID tenantId, UUID leadId, LeadStatus newStatus) {
        Lead lead = leadRepository.findById(leadId)
                .orElseThrow(() -> new NotFoundException("LEAD_NOT_FOUND", "Lead not found"));

        if (!lead.getTenantId().equals(tenantId)) {
            throw new AuthorizationException("FORBIDDEN", "Tenant access is forbidden.");
        }

        lead.updateStatus(newStatus);
        leadRepository.save(lead);
        return lead;
    }
}
