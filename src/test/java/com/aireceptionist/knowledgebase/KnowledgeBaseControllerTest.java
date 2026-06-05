package com.aireceptionist.knowledgebase;

import com.aireceptionist.common.api.GlobalExceptionHandler;
import com.aireceptionist.common.security.TenantAwareAuthentication;
import com.aireceptionist.knowledgebase.adapter.in.web.KnowledgeBaseController;
import com.aireceptionist.knowledgebase.adapter.in.web.dto.OcrExtractedEntry;
import com.aireceptionist.knowledgebase.service.KnowledgeBaseService;
import com.aireceptionist.knowledgebase.service.OcrIngestionService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class KnowledgeBaseControllerTest {

    private final OcrIngestionService ocrIngestionService = mock(OcrIngestionService.class);
    private final KnowledgeBaseService knowledgeBaseService = mock(KnowledgeBaseService.class);
    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new KnowledgeBaseController(ocrIngestionService, knowledgeBaseService))
            .setControllerAdvice(new GlobalExceptionHandler())
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

    private TenantAwareAuthentication authentication(UUID tenantId) {
        return new TenantAwareAuthentication(tenantId.toString(), tenantId.toString(), "OWNER", "BASIC");
    }
}
