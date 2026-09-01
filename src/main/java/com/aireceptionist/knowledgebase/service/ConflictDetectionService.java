package com.aireceptionist.knowledgebase.service;

import com.aireceptionist.knowledgebase.domain.EntryType;
import com.aireceptionist.knowledgebase.dto.ConflictInfo;
import com.aireceptionist.knowledgebase.repository.KnowledgeEntryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class ConflictDetectionService {

    private final KnowledgeEntryRepository repository;

    public ConflictDetectionService(KnowledgeEntryRepository repository) {
        this.repository = repository;
    }

    public Optional<ConflictInfo> checkConflict(UUID tenantId, String productName, String newPrice) {
        return checkConflict(tenantId, productName, newPrice, null);
    }

    /**
     * Same as {@link #checkConflict(UUID, String, String)}, but excludes {@code excludeEntryId} from
     * the match — for use when updating an entry against its own unchanged product name.
     */
    public Optional<ConflictInfo> checkConflict(UUID tenantId, String productName, String newPrice, UUID excludeEntryId) {
        return repository.findByTenantIdAndTypeAndProductName(tenantId, EntryType.PRODUCT, productName)
                .filter(entry -> excludeEntryId == null || !excludeEntryId.equals(entry.getId()))
                .filter(entry -> entry.getPrice() != null && !entry.getPrice().equalsIgnoreCase(newPrice.trim()))
                .map(entry -> new ConflictInfo(productName, entry.getPrice(), newPrice.trim()));
    }
}
