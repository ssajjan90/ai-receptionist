package com.aireceptionist.knowledgebase;

import com.aireceptionist.common.api.GlobalExceptionHandler;
import com.aireceptionist.common.exception.BusinessRuleException;
import com.aireceptionist.common.security.TenantAwareAuthentication;
import com.aireceptionist.knowledgebase.adapter.in.web.KnowledgeBaseController;
import com.aireceptionist.knowledgebase.adapter.in.web.dto.OcrExtractedEntry;
import com.aireceptionist.knowledgebase.domain.EntryType;
import com.aireceptionist.knowledgebase.domain.KnowledgeEntry;
import com.aireceptionist.knowledgebase.dto.CreateKnowledgeEntryRequest;
import com.aireceptionist.knowledgebase.dto.KnowledgeEntryMapper;
import com.aireceptionist.knowledgebase.dto.UpdateKnowledgeEntryRequest;
import com.aireceptionist.knowledgebase.service.KnowledgeBaseService;
import com.aireceptionist.knowledgebase.service.OcrIngestionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class KnowledgeBaseControllerTest {

    private final OcrIngestionService ocrIngestionService = mock(OcrIngestionService.class);
    private final KnowledgeBaseService knowledgeBaseService = mock(KnowledgeBaseService.class);
    private final KnowledgeEntryMapper knowledgeEntryMapper = new KnowledgeEntryMapper();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new KnowledgeBaseController(ocrIngestionService, knowledgeBaseService, knowledgeEntryMapper))
            .setControllerAdvice(new GlobalExceptionHandler())
            .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver())
            .build();

    @Test
    void importRejectsFilesLargerThanFiveMbBeforeOcrProcessing() throws Exception {
        UUID tenantId = UUID.randomUUID();
        MockMultipartFile image = new MockMultipartFile(
                "image",
                "large.png",
                "image/png",
                new byte[(5 * 1024 * 1024) + 1]
        );

        mockMvc.perform(multipart("/v1/tenants/{tenantId}/knowledge-base/ocr-import", tenantId)
                        .file(image)
                        .principal(authentication(tenantId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("FILE_TOO_LARGE"));

        verify(ocrIngestionService, never()).extractFromImage(any(), any());
    }

    @Test
    void importRejectsNonImageFilesBeforeOcrProcessing() throws Exception {
        UUID tenantId = UUID.randomUUID();
        MockMultipartFile image = new MockMultipartFile(
                "image",
                "menu.pdf",
                "application/pdf",
                "not-image".getBytes(StandardCharsets.UTF_8)
        );

        mockMvc.perform(multipart("/v1/tenants/{tenantId}/knowledge-base/ocr-import", tenantId)
                        .file(image)
                        .principal(authentication(tenantId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_FILE_TYPE"));

        verify(ocrIngestionService, never()).extractFromImage(any(), any());
    }

    @Test
    void importReturnsExtractedEntriesForValidImage() throws Exception {
        UUID tenantId = UUID.randomUUID();
        MockMultipartFile image = new MockMultipartFile(
                "image",
                "menu.png",
                "image/png",
                "image".getBytes(StandardCharsets.UTF_8)
        );
        String rawText = "Tea - ₹20\nCoffee - ₹30";
        when(ocrIngestionService.extractFromImage(any(), eq("image/png")))
                .thenReturn(CompletableFuture.completedFuture(rawText));
        when(ocrIngestionService.parseProductPrices(rawText))
                .thenReturn(List.of(
                        new OcrExtractedEntry("Tea", "20", 0.5),
                        new OcrExtractedEntry("Coffee", "30", 0.5)
                ));

        mockMvc.perform(multipart("/v1/tenants/{tenantId}/knowledge-base/ocr-import", tenantId)
                        .file(image)
                        .principal(authentication(tenantId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.extractedEntries[0].productName").value("Tea"))
                .andExpect(jsonPath("$.data.rawText").value(rawText));
    }

    @Test
    void listEntriesReturnsPaginatedResultsForAuthenticatedOwner() throws Exception {
        UUID tenantId = UUID.randomUUID();
        KnowledgeEntry entry = KnowledgeEntry.product(tenantId, "Tea", "20", "WEB");
        when(knowledgeBaseService.findAllByTenantId(eq(tenantId), isNull(), any()))
                .thenReturn(new PageImpl<>(List.of(entry), PageRequest.of(0, 5), 1));

        mockMvc.perform(get("/v1/tenants/{tenantId}/knowledge-base?page=0&size=5", tenantId)
                        .principal(authentication(tenantId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.content[0].productName").value("Tea"))
                .andExpect(jsonPath("$.data.totalElements").value(1));
    }

    @Test
    void listEntriesReturnsForbiddenForWrongTenant() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID otherTenantId = UUID.randomUUID();

        mockMvc.perform(get("/v1/tenants/{tenantId}/knowledge-base", tenantId)
                        .principal(authentication(otherTenantId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));
    }

    @Test
    void createEntryReturns422WhenConflictDetected() throws Exception {
        UUID tenantId = UUID.randomUUID();
        CreateKnowledgeEntryRequest request = new CreateKnowledgeEntryRequest(
                EntryType.PRODUCT, "Tea", null, null, "30");
        when(knowledgeBaseService.createEntry(eq(tenantId), any()))
                .thenThrow(new BusinessRuleException("KB_CONFLICT", "Conflict detected"));

        mockMvc.perform(post("/v1/tenants/{tenantId}/knowledge-base", tenantId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .principal(authentication(tenantId)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("KB_CONFLICT"));
    }

    @Test
    void deleteEntryPublishesEventViaServiceCall() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID entryId = UUID.randomUUID();

        mockMvc.perform(delete("/v1/tenants/{tenantId}/knowledge-base/{entryId}", tenantId, entryId)
                        .principal(authentication(tenantId)))
                .andExpect(status().isNoContent());

        verify(knowledgeBaseService).deleteEntryById(tenantId, entryId);
    }

    private TenantAwareAuthentication authentication(UUID tenantId) {
        return new TenantAwareAuthentication(tenantId.toString(), tenantId.toString(), "OWNER", "BASIC");
    }
}
