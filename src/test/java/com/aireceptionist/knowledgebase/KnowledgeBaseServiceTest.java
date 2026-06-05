package com.aireceptionist.knowledgebase;

import com.aireceptionist.knowledgebase.domain.EntryType;
import com.aireceptionist.knowledgebase.domain.KnowledgeEntry;
import com.aireceptionist.knowledgebase.event.KnowledgeEntryAddedEvent;
import com.aireceptionist.knowledgebase.repository.KnowledgeEntryRepository;
import com.aireceptionist.knowledgebase.service.ProductKnowledgeEntry;
import com.aireceptionist.knowledgebase.service.KnowledgeBaseService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KnowledgeBaseServiceTest {

    private final KnowledgeEntryRepository repository = mock(KnowledgeEntryRepository.class);
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> valueOps = mock(ValueOperations.class);
    private final KnowledgeBaseService service;

    KnowledgeBaseServiceTest() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        service = new KnowledgeBaseService(repository, eventPublisher, redisTemplate, new ObjectMapper(), 5);
    }

    @Test
    void bulkUpsertProductsSavesNewEntriesAndPublishesEvent() {
        UUID tenantId = UUID.randomUUID();
        when(repository.findByTenantIdAndTypeAndSource(tenantId, EntryType.PRODUCT, "WIZARD"))
                .thenReturn(List.of());
        when(repository.findByTenantIdAndTypeAndSourceAndProductName(tenantId, EntryType.PRODUCT, "WIZARD", "Tea"))
                .thenReturn(Optional.empty());

        int count = service.bulkUpsertProducts(tenantId, List.of(new ProductKnowledgeEntry("Tea", "20")));

        assertThat(count).isEqualTo(1);
        ArgumentCaptor<Iterable<KnowledgeEntry>> captor = ArgumentCaptor.forClass(Iterable.class);
        verify(repository).saveAll(captor.capture());
        assertThat(captor.getValue())
                .singleElement()
                .satisfies(entry -> {
                    assertThat(entry.getTenantId()).isEqualTo(tenantId);
                    assertThat(entry.getType()).isEqualTo(EntryType.PRODUCT);
                    assertThat(entry.getProductName()).isEqualTo("Tea");
                    assertThat(entry.getPrice()).isEqualTo("20");
                    assertThat(entry.getSource()).isEqualTo("WIZARD");
                });
        verify(eventPublisher).publishEvent(any(KnowledgeEntryAddedEvent.class));
    }

    @Test
    void bulkUpsertProductsUpdatesExistingEntryWithoutCreatingDuplicate() {
        UUID tenantId = UUID.randomUUID();
        KnowledgeEntry existing = KnowledgeEntry.onboardingProduct(tenantId, "Tea", "20");
        when(repository.findByTenantIdAndTypeAndSource(tenantId, EntryType.PRODUCT, "WIZARD"))
                .thenReturn(List.of(existing));
        when(repository.findByTenantIdAndTypeAndSourceAndProductName(tenantId, EntryType.PRODUCT, "WIZARD", "Tea"))
                .thenReturn(Optional.of(existing));

        service.bulkUpsertProducts(tenantId, List.of(new ProductKnowledgeEntry("Tea", "25")));

        assertThat(existing.getPrice()).isEqualTo("25");
        verify(repository).saveAll(List.of(existing));
        verify(repository, never()).delete(existing);
    }

    @Test
    void bulkUpsertProductsDeletesOmittedWizardProducts() {
        UUID tenantId = UUID.randomUUID();
        KnowledgeEntry tea = KnowledgeEntry.onboardingProduct(tenantId, "Tea", "20");
        KnowledgeEntry coffee = KnowledgeEntry.onboardingProduct(tenantId, "Coffee", "30");
        when(repository.findByTenantIdAndTypeAndSource(tenantId, EntryType.PRODUCT, "WIZARD"))
                .thenReturn(List.of(tea, coffee));
        when(repository.findByTenantIdAndTypeAndSourceAndProductName(tenantId, EntryType.PRODUCT, "WIZARD", "Tea"))
                .thenReturn(Optional.of(tea));

        service.bulkUpsertProducts(tenantId, List.of(new ProductKnowledgeEntry("Tea", "25")));

        verify(repository).delete(coffee);
    }
}
