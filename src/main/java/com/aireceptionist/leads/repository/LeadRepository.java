package com.aireceptionist.leads.repository;

import com.aireceptionist.leads.domain.Lead;
import com.aireceptionist.leads.domain.LeadStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.UUID;

public interface LeadRepository extends JpaRepository<Lead, UUID> {

    Page<Lead> findByTenantIdOrderByCreatedAtDesc(UUID tenantId, Pageable pageable);

    Page<Lead> findByTenantIdAndStatus(UUID tenantId, LeadStatus status, Pageable pageable);

    long countByTenantIdAndCreatedAtAfter(UUID tenantId, Instant since);
}
