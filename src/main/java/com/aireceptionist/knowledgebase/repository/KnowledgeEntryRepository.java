package com.aireceptionist.knowledgebase.repository;

import com.aireceptionist.knowledgebase.domain.EntryType;
import com.aireceptionist.knowledgebase.domain.KnowledgeEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface KnowledgeEntryRepository extends JpaRepository<KnowledgeEntry, UUID> {

    List<KnowledgeEntry> findByTenantId(UUID tenantId);

    List<KnowledgeEntry> findByTenantIdAndType(UUID tenantId, EntryType type);

    List<KnowledgeEntry> findByTenantIdAndTypeAndSource(UUID tenantId, EntryType type, String source);

    Optional<KnowledgeEntry> findByTenantIdAndTypeAndProductName(UUID tenantId, EntryType type, String productName);

    Optional<KnowledgeEntry> findByTenantIdAndTypeAndQuestion(UUID tenantId, EntryType type, String question);

    Optional<KnowledgeEntry> findByTenantIdAndTypeAndSourceAndProductName(UUID tenantId, EntryType type, String source,
                                                                          String productName);

    Optional<KnowledgeEntry> findByTenantIdAndTypeAndSourceAndQuestion(UUID tenantId, EntryType type, String source,
                                                                       String question);

    Optional<KnowledgeEntry> findByTenantIdAndProductName(UUID tenantId, String productName);

    Optional<KnowledgeEntry> findByTenantIdAndQuestion(UUID tenantId, String question);

    void deleteByTenantId(UUID tenantId);

    @Query("SELECT e FROM KnowledgeEntry e WHERE e.tenantId = :tenantId AND " +
           "(LOWER(COALESCE(e.productName, '')) LIKE LOWER(:pattern) OR " +
           " LOWER(COALESCE(e.question, '')) LIKE LOWER(:pattern) OR " +
           " LOWER(COALESCE(e.answer, '')) LIKE LOWER(:pattern))")
    List<KnowledgeEntry> searchByKeyword(@Param("tenantId") UUID tenantId, @Param("pattern") String pattern);
}
