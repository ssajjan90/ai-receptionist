package com.aireceptionist.knowledge.repository;

import com.aireceptionist.knowledge.entity.IndustryType;
import com.aireceptionist.knowledge.entity.KnowledgeBase;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface KnowledgeBaseRepository extends JpaRepository<KnowledgeBase, Long> {

    List<KnowledgeBase> findByTenantId(Long tenantId);

    List<KnowledgeBase> findByTenantIdAndActiveTrue(Long tenantId);

    List<KnowledgeBase> findByTenantIdAndIndustryAndLanguageIgnoreCaseAndActiveTrue(Long tenantId,
                                                                                     IndustryType industry,
                                                                                     String language);

    List<KnowledgeBase> findByTenantIdAndIndustryAndActiveTrue(Long tenantId, IndustryType industry);

    List<KnowledgeBase> findByTenantIdIsNullAndIndustryAndLanguageIgnoreCaseAndActiveTrue(IndustryType industry,
                                                                                            String language);

    List<KnowledgeBase> findByTenantIdIsNullAndIndustryAndActiveTrue(IndustryType industry);

    Optional<KnowledgeBase> findByIdAndTenantId(Long id, Long tenantId);
}
