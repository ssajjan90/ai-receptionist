package com.aireceptionist.leads.service;

import com.aireceptionist.leads.domain.LeadChannel;
import com.aireceptionist.leads.dto.CreateLeadCommand;
import com.aireceptionist.whatsapp.event.LeadCaptureRequestedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class LeadCaptureEventListener {

    private static final Logger log = LoggerFactory.getLogger(LeadCaptureEventListener.class);

    private final LeadService leadService;

    public LeadCaptureEventListener(LeadService leadService) {
        this.leadService = leadService;
    }

    @ApplicationModuleListener
    void onLeadCaptureRequested(LeadCaptureRequestedEvent event) {
        log.info("Processing lead capture for tenant={}", event.getTenantId());
        CreateLeadCommand command = new CreateLeadCommand(
                UUID.fromString(event.getTenantId()),
                event.getCustomerName(),
                event.getCapturedPhone(),
                event.getProductIntent(),
                LeadChannel.WHATSAPP,
                "WHATSAPP",
                event.getConsentedAt()
        );
        try {
            leadService.createLead(command, event.getOwnerPhone());
        } catch (DataIntegrityViolationException e) {
            log.info("Duplicate lead ignored (idempotent) for tenant={}, phone={}",
                    event.getTenantId(), event.getCapturedPhone());
        }
    }
}
