package com.aireceptionist.knowledgebase.adapter.in.web;

import com.aireceptionist.api.VersionedRestController;
import com.aireceptionist.common.api.ApiResponse;
import com.aireceptionist.common.exception.AuthorizationException;
import com.aireceptionist.common.exception.ValidationException;
import com.aireceptionist.common.security.TenantAwareAuthentication;
import com.aireceptionist.knowledgebase.adapter.in.web.dto.OcrConfirmRequest;
import com.aireceptionist.knowledgebase.adapter.in.web.dto.OcrConfirmResponse;
import com.aireceptionist.knowledgebase.adapter.in.web.dto.OcrImportResponse;
import com.aireceptionist.knowledgebase.adapter.in.web.dto.OcrLowConfidenceError;
import com.aireceptionist.knowledgebase.domain.EntryType;
import com.aireceptionist.knowledgebase.dto.CreateKnowledgeEntryRequest;
import com.aireceptionist.knowledgebase.dto.KnowledgeEntryMapper;
import com.aireceptionist.knowledgebase.dto.KnowledgeEntryResponse;
import com.aireceptionist.knowledgebase.dto.UpdateKnowledgeEntryRequest;
import com.aireceptionist.knowledgebase.exception.OcrLowConfidenceException;
import com.aireceptionist.knowledgebase.service.KnowledgeBaseService;
import com.aireceptionist.knowledgebase.service.OcrIngestionService;
import com.aireceptionist.knowledgebase.service.ProductKnowledgeEntry;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@RestController
@RequestMapping("/v1/tenants/{tenantId}/knowledge-base")
public class KnowledgeBaseController extends VersionedRestController {

    private static final long MAX_IMAGE_BYTES = 5L * 1024L * 1024L;
    private static final Set<String> ALLOWED_IMAGE_TYPES = Set.of("image/jpeg", "image/png");

    private final OcrIngestionService ocrIngestionService;
    private final KnowledgeBaseService knowledgeBaseService;
    private final KnowledgeEntryMapper knowledgeEntryMapper;

    public KnowledgeBaseController(OcrIngestionService ocrIngestionService,
                                   KnowledgeBaseService knowledgeBaseService,
                                   KnowledgeEntryMapper knowledgeEntryMapper) {
        this.ocrIngestionService = ocrIngestionService;
        this.knowledgeBaseService = knowledgeBaseService;
        this.knowledgeEntryMapper = knowledgeEntryMapper;
    }

    @PostMapping("/ocr-import")
    public ResponseEntity<ApiResponse<OcrImportResponse>> importImage(@PathVariable UUID tenantId,
                                                                      @RequestParam("image") MultipartFile image,
                                                                      Authentication authentication) throws IOException {
        validateTenantOwnership(tenantId, authentication);
        validateImage(image);

        Instant startedAt = Instant.now();
        String rawText;
        try {
            rawText = ocrIngestionService.extractFromImage(image.getBytes(), image.getContentType())
                    .get(55, TimeUnit.SECONDS);
        } catch (TimeoutException ex) {
            return ResponseEntity
                    .status(HttpStatus.GATEWAY_TIMEOUT)
                    .body(ApiResponse.error("OCR_TIMEOUT", "OCR processing timed out.", null));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return ResponseEntity
                    .status(HttpStatus.GATEWAY_TIMEOUT)
                    .body(ApiResponse.error("OCR_TIMEOUT", "OCR processing was interrupted.", null));
        } catch (ExecutionException ex) {
            if (ex.getCause() instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("OCR processing failed", ex.getCause());
        }

        var entries = ocrIngestionService.parseProductPrices(rawText);
        return ResponseEntity.ok(ApiResponse.ok(new OcrImportResponse(
                entries,
                rawText,
                Duration.between(startedAt, Instant.now()).toMillis()
        )));
    }

    @PostMapping("/ocr-import/confirm")
    public ApiResponse<OcrConfirmResponse> confirmImport(@PathVariable UUID tenantId,
                                                         @Valid @RequestBody OcrConfirmRequest request,
                                                         Authentication authentication) {
        validateTenantOwnership(tenantId, authentication);
        int saved = knowledgeBaseService.bulkUpsertOcrProducts(
                tenantId,
                request.entries().stream()
                        .map(entry -> new ProductKnowledgeEntry(entry.productName(), entry.price()))
                        .toList()
        );
        return ApiResponse.ok(new OcrConfirmResponse(tenantId, saved, "OCR entries saved"));
    }

    @GetMapping
    @Transactional(readOnly = true)
    public ApiResponse<Page<KnowledgeEntryResponse>> listEntries(
            @PathVariable UUID tenantId,
            @RequestParam(required = false) EntryType type,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable,
            Authentication authentication) {
        validateTenantOwnership(tenantId, authentication);
        Page<KnowledgeEntryResponse> page = knowledgeBaseService
                .findAllByTenantId(tenantId, type, pageable)
                .map(knowledgeEntryMapper::toResponse);
        return ApiResponse.ok(page);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<KnowledgeEntryResponse> createEntry(
            @PathVariable UUID tenantId,
            @Valid @RequestBody CreateKnowledgeEntryRequest request,
            Authentication authentication) {
        validateTenantOwnership(tenantId, authentication);
        return ApiResponse.ok(knowledgeEntryMapper.toResponse(
                knowledgeBaseService.createEntry(tenantId, request)));
    }

    @PutMapping("/{entryId}")
    public ApiResponse<KnowledgeEntryResponse> updateEntry(
            @PathVariable UUID tenantId,
            @PathVariable UUID entryId,
            @Valid @RequestBody UpdateKnowledgeEntryRequest request,
            Authentication authentication) {
        validateTenantOwnership(tenantId, authentication);
        return ApiResponse.ok(knowledgeEntryMapper.toResponse(
                knowledgeBaseService.updateEntry(tenantId, entryId, request)));
    }

    @DeleteMapping("/{entryId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteEntry(
            @PathVariable UUID tenantId,
            @PathVariable UUID entryId,
            Authentication authentication) {
        validateTenantOwnership(tenantId, authentication);
        knowledgeBaseService.deleteEntryById(tenantId, entryId);
    }

    private void validateImage(MultipartFile image) {
        if (image == null || image.isEmpty()) {
            throw new ValidationException("INVALID_FILE_TYPE", "A JPEG or PNG image is required.");
        }
        if (!ALLOWED_IMAGE_TYPES.contains(image.getContentType())) {
            throw new ValidationException("INVALID_FILE_TYPE", "Only JPEG and PNG images are supported.");
        }
        if (image.getSize() > MAX_IMAGE_BYTES) {
            throw new ValidationException("FILE_TOO_LARGE", "Uploaded file exceeds the 5MB limit.");
        }
    }

    private void validateTenantOwnership(UUID tenantId, Authentication authentication) {
        if (!(authentication instanceof TenantAwareAuthentication tenantAuthentication)) {
            throw new AuthorizationException("FORBIDDEN", "Authenticated tenant context is required.");
        }

        if (!tenantId.toString().equals(tenantAuthentication.getTenantId())) {
            throw new AuthorizationException("FORBIDDEN", "Tenant access is forbidden.");
        }
    }

    @ExceptionHandler(OcrLowConfidenceException.class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    public ApiResponse<Void> handleOcrLowConfidence(OcrLowConfidenceException ex) {
        return ApiResponse.error(
                ex.getErrorCode(),
                ex.getMessage(),
                java.util.Map.of("ocr", new OcrLowConfidenceError(ex.getRawText(), ex.getPartialEntries()))
        );
    }
}
