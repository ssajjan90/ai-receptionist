package com.aireceptionist.leads.service;

import com.aireceptionist.leads.domain.Lead;
import com.aireceptionist.leads.dto.CreateLeadCommand;
import com.aireceptionist.leads.dto.LeadResponse;
import com.aireceptionist.leads.event.LeadCapturedEvent;
import com.aireceptionist.leads.repository.LeadRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
                Boolean.TRUE.equals(lead.getErased())
        );
    }
}
