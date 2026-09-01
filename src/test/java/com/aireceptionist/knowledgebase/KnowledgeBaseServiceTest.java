package com.aireceptionist.knowledgebase;

import com.aireceptionist.common.exception.BusinessRuleException;
import com.aireceptionist.common.exception.ValidationException;
import com.aireceptionist.knowledgebase.domain.EntryType;
import com.aireceptionist.knowledgebase.domain.KnowledgeEntry;
import com.aireceptionist.knowledgebase.dto.ConflictInfo;
import com.aireceptionist.knowledgebase.dto.CreateKnowledgeEntryRequest;
import com.aireceptionist.knowledgebase.dto.UpdateKnowledgeEntryRequest;
import com.aireceptionist.knowledgebase.event.KnowledgeEntryAddedEvent;
import com.aireceptionist.knowledgebase.repository.KnowledgeEntryRepository;
import com.aireceptionist.knowledgebase.service.ConflictDetectionService;
import com.aireceptionist.knowledgebase.service.KnowledgeBaseService;
import com.aireceptionist.knowledgebase.service.ProductKnowledgeEntry;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
    private final ConflictDetectionService conflictDetectionService = mock(ConflictDetectionService.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> valueOps = mock(ValueOperations.class);
    private final KnowledgeBaseService service;

    KnowledgeBaseServiceTest() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        service = new KnowledgeBaseService(repository, eventPublisher, redisTemplate, new ObjectMapper(),
                conflictDetectionService, 5);
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

    @Test
    void createEntryRejectsDuplicateProductNameEvenWithSamePrice() {
        UUID tenantId = UUID.randomUUID();
        KnowledgeEntry existing = KnowledgeEntry.product(tenantId, "Tea", "20", "WEB");
        when(repository.findByTenantIdAndTypeAndProductName(tenantId, EntryType.PRODUCT, "Tea"))
                .thenReturn(Optional.of(existing));

        CreateKnowledgeEntryRequest request = new CreateKnowledgeEntryRequest(EntryType.PRODUCT, "Tea", null, null, "20");

        assertThatThrownBy(() -> service.createEntry(tenantId, request))
                .isInstanceOf(BusinessRuleException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void createEntryRejectsDuplicateFaqQuestion() {
        UUID tenantId = UUID.randomUUID();
        KnowledgeEntry existing = KnowledgeEntry.faq(tenantId, "What are your hours?", "9-5", "WEB");
        when(repository.findByTenantIdAndTypeAndQuestion(tenantId, EntryType.FAQ, "What are your hours?"))
                .thenReturn(Optional.of(existing));

        CreateKnowledgeEntryRequest request = new CreateKnowledgeEntryRequest(
                EntryType.FAQ, null, "What are your hours?", "10-6", null);

        assertThatThrownBy(() -> service.createEntry(tenantId, request))
                .isInstanceOf(BusinessRuleException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void createEntryRejectsServiceType() {
        UUID tenantId = UUID.randomUUID();
        CreateKnowledgeEntryRequest request = new CreateKnowledgeEntryRequest(EntryType.SERVICE, null, null, null, null);

        assertThatThrownBy(() -> service.createEntry(tenantId, request))
                .isInstanceOf(ValidationException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void updateEntryOnFaqIgnoresPriceFieldWithoutCorruptingAnswer() {
        UUID tenantId = UUID.randomUUID();
        UUID entryId = UUID.randomUUID();
        KnowledgeEntry faqEntry = KnowledgeEntry.faq(tenantId, "What are your hours?", "9-5", "WEB");
        when(repository.findById(entryId)).thenReturn(Optional.of(faqEntry));

        UpdateKnowledgeEntryRequest request = new UpdateKnowledgeEntryRequest(null, null, null, "999");
        service.updateEntry(tenantId, entryId, request);

        assertThat(faqEntry.getAnswer()).isEqualTo("9-5");
        assertThat(faqEntry.getPrice()).isNull();
    }

    @Test
    void updateEntryOnProductChecksConflictExcludingItself() {
        UUID tenantId = UUID.randomUUID();
        UUID entryId = UUID.randomUUID();
        KnowledgeEntry productEntry = KnowledgeEntry.product(tenantId, "Tea", "20", "WEB");
        when(repository.findById(entryId)).thenReturn(Optional.of(productEntry));
        when(conflictDetectionService.checkConflict(tenantId, "Tea", "30", entryId))
                .thenReturn(Optional.empty());

        UpdateKnowledgeEntryRequest request = new UpdateKnowledgeEntryRequest(null, null, null, "30");
        service.updateEntry(tenantId, entryId, request);

        assertThat(productEntry.getPrice()).isEqualTo("30");
        assertThat(productEntry.getAnswer()).isEqualTo("30");
        verify(conflictDetectionService).checkConflict(tenantId, "Tea", "30", entryId);
    }

    @Test
    void updateEntryRejectsPriceThatConflictsWithAnotherProduct() {
        UUID tenantId = UUID.randomUUID();
        UUID entryId = UUID.randomUUID();
        KnowledgeEntry productEntry = KnowledgeEntry.product(tenantId, "Tea", "20", "WEB");
        when(repository.findById(entryId)).thenReturn(Optional.of(productEntry));
        when(conflictDetectionService.checkConflict(tenantId, "Tea", "999", entryId))
                .thenReturn(Optional.of(new ConflictInfo("Tea", "20", "999")));

        UpdateKnowledgeEntryRequest request = new UpdateKnowledgeEntryRequest(null, null, null, "999");

        assertThatThrownBy(() -> service.updateEntry(tenantId, entryId, request))
                .isInstanceOf(BusinessRuleException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void updateEntryNoOpRequestDoesNotOverwriteSource() {
        UUID tenantId = UUID.randomUUID();
        UUID entryId = UUID.randomUUID();
        KnowledgeEntry productEntry = KnowledgeEntry.product(tenantId, "Tea", "20", "OCR");
        when(repository.findById(entryId)).thenReturn(Optional.of(productEntry));

        UpdateKnowledgeEntryRequest request = new UpdateKnowledgeEntryRequest(null, null, null, null);
        service.updateEntry(tenantId, entryId, request);

        assertThat(productEntry.getSource()).isEqualTo("OCR");
    }
}
