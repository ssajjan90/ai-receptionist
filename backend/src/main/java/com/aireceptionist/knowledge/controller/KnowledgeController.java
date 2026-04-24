package com.aireceptionist.knowledge.controller;

import com.aireceptionist.common.response.ApiResponse;
import com.aireceptionist.knowledge.dto.KnowledgeBaseRequest;
import com.aireceptionist.knowledge.dto.KnowledgeBaseResponse;
import com.aireceptionist.knowledge.entity.IndustryType;
import com.aireceptionist.knowledge.service.KnowledgeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
@Tag(name = "Knowledge Base", description = "Manage tenant and default industry knowledge base")
public class KnowledgeController {

    private final KnowledgeService knowledgeService;

    @GetMapping("/api/tenants/{tenantId}/knowledge")
    @Operation(summary = "List all knowledge entries for a tenant")
    public ResponseEntity<ApiResponse<List<KnowledgeBaseResponse>>> getByTenant(@PathVariable Long tenantId) {
        return ResponseEntity.ok(ApiResponse.ok(knowledgeService.findByTenant(tenantId)));
    }

    @PostMapping("/api/tenants/{tenantId}/knowledge")
    @Operation(summary = "Add a knowledge entry for a tenant")
    public ResponseEntity<ApiResponse<KnowledgeBaseResponse>> createForTenant(
            @PathVariable Long tenantId,
            @Valid @RequestBody KnowledgeBaseRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.created(knowledgeService.createForTenant(tenantId, request)));
    }

    @PutMapping("/api/knowledge/{id}")
    @Operation(summary = "Update a knowledge entry")
    public ResponseEntity<ApiResponse<KnowledgeBaseResponse>> update(
            @PathVariable Long id,
            @Valid @RequestBody KnowledgeBaseRequest request) {
        return ResponseEntity.ok(ApiResponse.ok("Updated successfully", knowledgeService.update(id, request)));
    }

    @DeleteMapping("/api/knowledge/{id}")
    @Operation(summary = "Delete a knowledge entry")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long id) {
        knowledgeService.delete(id);
        return ResponseEntity.ok(ApiResponse.ok("Deleted successfully", null));
    }

    @PostMapping("/api/knowledge-base")
    @Operation(summary = "Create tenant-specific or default industry knowledge entry")
    public ResponseEntity<ApiResponse<KnowledgeBaseResponse>> create(@Valid @RequestBody KnowledgeBaseRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.created(knowledgeService.create(request)));
    }

    @GetMapping("/api/knowledge-base/tenant/{tenantId}")
    @Operation(summary = "List knowledge entries for a tenant")
    public ResponseEntity<ApiResponse<List<KnowledgeBaseResponse>>> getTenantKnowledge(@PathVariable Long tenantId) {
        return ResponseEntity.ok(ApiResponse.ok(knowledgeService.findByTenant(tenantId)));
    }

    @GetMapping("/api/knowledge-base/industry/{industry}")
    @Operation(summary = "List active default knowledge entries for an industry")
    public ResponseEntity<ApiResponse<List<KnowledgeBaseResponse>>> getIndustryDefaults(@PathVariable IndustryType industry) {
        return ResponseEntity.ok(ApiResponse.ok(knowledgeService.findDefaultByIndustry(industry)));
    }

    @PostMapping("/api/knowledge-base/seed-defaults")
    @Operation(summary = "Seed default multilingual knowledge entries for all industries")
    public ResponseEntity<ApiResponse<String>> seedDefaults() {
        int inserted = knowledgeService.seedDefaultKnowledge();
        return ResponseEntity.ok(ApiResponse.ok("Seed completed", inserted + " entries inserted"));
    }
}
