package com.aireceptionist.admin.controller;

import com.aireceptionist.admin.dto.AdminConversationResponse;
import com.aireceptionist.admin.service.AdminApiService;
import com.aireceptionist.common.response.ApiResponse;
import com.aireceptionist.lead.dto.LeadResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
@Tag(name = "Admin APIs", description = "Tenant-admin and super-admin reporting endpoints")
public class AdminController {

    private final AdminApiService adminApiService;

    @GetMapping("/conversations/tenant/{tenantId}")
    @Operation(summary = "List conversations for a tenant")
    public ResponseEntity<ApiResponse<List<AdminConversationResponse>>> getConversationsByTenant(@PathVariable Long tenantId) {
        return ResponseEntity.ok(ApiResponse.ok(adminApiService.getConversationsByTenant(tenantId)));
    }

    @GetMapping("/conversations/{id}")
    @Operation(summary = "Get conversation details by ID")
    public ResponseEntity<ApiResponse<AdminConversationResponse>> getConversationById(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.ok(adminApiService.getConversationById(id)));
    }

    @GetMapping("/leads/tenant/{tenantId}")
    @Operation(summary = "List leads for a tenant")
    public ResponseEntity<ApiResponse<List<LeadResponse>>> getLeadsByTenant(@PathVariable Long tenantId) {
        return ResponseEntity.ok(ApiResponse.ok(adminApiService.getLeadsByTenant(tenantId)));
    }
}
