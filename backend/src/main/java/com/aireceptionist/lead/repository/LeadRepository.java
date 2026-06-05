package com.aireceptionist.lead.repository;

import com.aireceptionist.lead.entity.Lead;
import com.aireceptionist.lead.entity.Lead.LeadStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface LeadRepository extends JpaRepository<Lead, Long> {

    List<Lead> findByTenantId(Long tenantId);

    List<Lead> findByTenantIdAndStatus(Long tenantId, LeadStatus status);
}
