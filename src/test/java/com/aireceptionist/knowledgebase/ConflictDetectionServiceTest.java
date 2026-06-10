package com.aireceptionist.knowledgebase;

import com.aireceptionist.knowledgebase.domain.EntryType;
import com.aireceptionist.knowledgebase.domain.KnowledgeEntry;
import com.aireceptionist.knowledgebase.dto.ConflictInfo;
import com.aireceptionist.knowledgebase.repository.KnowledgeEntryRepository;
import com.aireceptionist.knowledgebase.service.ConflictDetectionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ConflictDetectionServiceTest {

    private KnowledgeEntryRepository repository;
    private ConflictDetectionService service;

    private final UUID tenantId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        repository = mock(KnowledgeEntryRepository.class);
        service = new ConflictDetectionService(repository);
    }

    @Test
    void noConflictWhenProductDoesNotExist() {
        when(repository.findByTenantIdAndTypeAndProductName(tenantId, EntryType.PRODUCT, "Widget"))
                .thenReturn(Optional.empty());

        Optional<ConflictInfo> result = service.checkConflict(tenantId, "Widget", "500");

        assertThat(result).isEmpty();
    }

    @Test
    void noConflictWhenProductExistsWithSamePrice() {
        KnowledgeEntry entry = KnowledgeEntry.product(tenantId, "Widget", "500", "WIZARD");
        when(repository.findByTenantIdAndTypeAndProductName(tenantId, EntryType.PRODUCT, "Widget"))
                .thenReturn(Optional.of(entry));

        Optional<ConflictInfo> result = service.checkConflict(tenantId, "Widget", "500");

        assertThat(result).isEmpty();
    }

    @Test
    void noConflictWhenSamePriceDifferentCase() {
        KnowledgeEntry entry = KnowledgeEntry.product(tenantId, "Widget", "500", "WIZARD");
        when(repository.findByTenantIdAndTypeAndProductName(tenantId, EntryType.PRODUCT, "Widget"))
                .thenReturn(Optional.of(entry));

        Optional<ConflictInfo> result = service.checkConflict(tenantId, "Widget", " 500 ");

        assertThat(result).isEmpty();
    }

    @Test
    void conflictWhenProductExistsWithDifferentPrice() {
        KnowledgeEntry entry = KnowledgeEntry.product(tenantId, "Widget", "500", "WIZARD");
        when(repository.findByTenantIdAndTypeAndProductName(tenantId, EntryType.PRODUCT, "Widget"))
                .thenReturn(Optional.of(entry));

        Optional<ConflictInfo> result = service.checkConflict(tenantId, "Widget", "750");

        assertThat(result).isPresent();
        ConflictInfo conflict = result.get();
        assertThat(conflict.productName()).isEqualTo("Widget");
        assertThat(conflict.existingPrice()).isEqualTo("500");
        assertThat(conflict.newPrice()).isEqualTo("750");
    }

    @Test
    void noConflictWhenExistingPriceIsNull() {
        KnowledgeEntry entry = KnowledgeEntry.product(tenantId, "Widget", null, "WIZARD");
        when(repository.findByTenantIdAndTypeAndProductName(tenantId, EntryType.PRODUCT, "Widget"))
                .thenReturn(Optional.of(entry));

        Optional<ConflictInfo> result = service.checkConflict(tenantId, "Widget", "500");

        assertThat(result).isEmpty();
    }
}
