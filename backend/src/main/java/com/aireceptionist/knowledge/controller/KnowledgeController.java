package com.aireceptionist.knowledge.controller;

import com.aireceptionist.common.response.ApiResponse;
import com.aireceptionist.knowledge.dto.KnowledgeRequest;
import com.aireceptionist.knowledge.dto.KnowledgeResponse;
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
@Tag(name = "Knowledge Base", description = "Manage tenant knowledge (FAQs, services, policies)")
public class KnowledgeController {

    private final KnowledgeService knowledgeService;

    @GetMapping("/api/tenants/{tenantId}/knowledge")
    @Operation(summary = "List all knowledge entries for a tenant")
    public ResponseEntity<ApiResponse<List<KnowledgeResponse>>> getByTenant(@PathVariable Long tenantId) {
        return ResponseEntity.ok(ApiResponse.ok(knowledgeService.findByTenant(tenantId)));
    }

    @PostMapping("/api/tenants/{tenantId}/knowledge")
    @Operation(summary = "Add a knowledge entry for a tenant")
    public ResponseEntity<ApiResponse<KnowledgeResponse>> create(
            @PathVariable Long tenantId,
            @Valid @RequestBody KnowledgeRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.created(knowledgeService.create(tenantId, request)));
    }

    @PutMapping("/api/knowledge/{id}")
    @Operation(summary = "Update a knowledge entry")
    public ResponseEntity<ApiResponse<KnowledgeResponse>> update(
            @PathVariable Long id,
            @Valid @RequestBody KnowledgeRequest request) {
        return ResponseEntity.ok(ApiResponse.ok("Updated successfully", knowledgeService.update(id, request)));
    }

    @DeleteMapping("/api/knowledge/{id}")
    @Operation(summary = "Delete a knowledge entry")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long id) {
        knowledgeService.delete(id);
        return ResponseEntity.ok(ApiResponse.ok("Deleted successfully", null));
    }
}
