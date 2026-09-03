package com.aireceptionist.leads.service;

import com.aireceptionist.common.audit.AuditEventType;
import com.aireceptionist.common.audit.AuditLogEntry;
import com.aireceptionist.common.audit.AuditLogRepository;
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

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class LeadService {

    private static final Logger log = LoggerFactory.getLogger(LeadService.class);

    private final LeadRepository leadRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final AuditLogRepository auditLogRepository;

    public LeadService(LeadRepository leadRepository, ApplicationEventPublisher eventPublisher,
                       AuditLogRepository auditLogRepository) {
        this.leadRepository = leadRepository;
        this.eventPublisher = eventPublisher;
        this.auditLogRepository = auditLogRepository;
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
            return leadRepository.findByTenantId(tenantId, pageable);
        }
        return leadRepository.findByTenantIdAndStatus(tenantId, status, pageable);
    }

    public Lead updateStatus(UUID tenantId, UUID leadId, LeadStatus newStatus) {
        Lead lead = leadRepository.findById(leadId)
                .orElseThrow(() -> new NotFoundException("LEAD_NOT_FOUND", "Lead not found"));

        // Row-Level Security (V8__create_rls_policies.sql) already scopes findById() to the
        // caller's tenant via app.current_tenant, so a cross-tenant leadId is invisible above and
        // surfaces as NotFoundException. This check is defense-in-depth only; it intentionally
        // returns 404 (not a distinct 403) to match RLS's behavior and avoid leaking cross-tenant
        // lead existence.
        if (!lead.getTenantId().equals(tenantId)) {
            throw new NotFoundException("LEAD_NOT_FOUND", "Lead not found");
        }

        if (Boolean.TRUE.equals(lead.getErased())) {
            throw new NotFoundException("LEAD_NOT_FOUND", "Lead not found");
        }

        lead.updateStatus(newStatus);
        leadRepository.save(lead);
        return lead;
    }

    @Transactional(readOnly = true)
    public List<Lead> exportLeads(UUID tenantId) {
        return leadRepository.findByTenantIdAndErasedFalse(tenantId);
    }

    public void eraseLead(UUID tenantId, UUID leadId) {
        Lead lead = leadRepository.findById(leadId)
                .orElseThrow(() -> new NotFoundException("LEAD_NOT_FOUND", "Lead not found"));

        // Same RLS defense-in-depth rationale as updateStatus(): a cross-tenant leadId is
        // already invisible to findById() above; this check just makes intent explicit.
        if (!lead.getTenantId().equals(tenantId)) {
            throw new NotFoundException("LEAD_NOT_FOUND", "Lead not found");
        }

        if (Boolean.TRUE.equals(lead.getErased())) {
            // Idempotent no-op: the requested end state (erased, PII gone) already holds, so a
            // repeat erasure request (client retry, double-tap in the dashboard) succeeds silently
            // rather than surfacing as an error.
            return;
        }

        lead.erase();
        // saveAndFlush, not save: this method's @Transactional connection is shared with
        // auditLogRepository.save() below, which does its own SET app.current_tenant / INSERT /
        // RESET on that same connection. Without an explicit flush here, Hibernate defers this
        // UPDATE to commit time — by then app.current_tenant has already been RESET, and leads'
        // RLS policy (V8/W99) silently matches zero rows, failing the optimistic-lock check.
        // Flushing now runs the UPDATE while app.current_tenant is still set from findById above.
        leadRepository.saveAndFlush(lead);

        auditLogRepository.save(new AuditLogEntry(UUID.randomUUID(), tenantId,
                AuditEventType.DATA_ERASED, null, leadId.toString(), Instant.now()));
        log.info("Lead erased id={} tenant={}", leadId, tenantId);
    }

    public void eraseAllLeads(UUID tenantId) {
        int erasedCount = leadRepository.bulkEraseByTenantId(tenantId);
        if (erasedCount == 0) {
            log.info("Bulk erase requested for tenant={} but no non-erased leads existed; no-op", tenantId);
            return;
        }

        auditLogRepository.save(new AuditLogEntry(UUID.randomUUID(), tenantId,
                AuditEventType.DATA_ERASED_BULK, null, String.valueOf(erasedCount), Instant.now()));
        log.info("Bulk erased {} leads for tenant={}", erasedCount, tenantId);
    }
}
