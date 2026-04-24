package com.aireceptionist.lead.controller;

import com.aireceptionist.common.response.ApiResponse;
import com.aireceptionist.lead.dto.LeadRequest;
import com.aireceptionist.lead.dto.LeadResponse;
import com.aireceptionist.lead.dto.LeadStatusRequest;
import com.aireceptionist.lead.service.LeadService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Tag(name = "Leads", description = "Customer lead management")
public class LeadController {

    private final LeadService leadService;

    @PostMapping("/leads")
    @Operation(summary = "Create a new lead")
    public ResponseEntity<ApiResponse<LeadResponse>> create(@Valid @RequestBody LeadRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.created(leadService.create(request)));
    }

    @GetMapping("/tenants/{tenantId}/leads")
    @Operation(summary = "List leads for a tenant")
    public ResponseEntity<ApiResponse<List<LeadResponse>>> getByTenant(@PathVariable Long tenantId) {
        return ResponseEntity.ok(ApiResponse.ok(leadService.findByTenant(tenantId)));
    }

    @GetMapping("/leads/{id}")
    @Operation(summary = "Get lead by ID")
    public ResponseEntity<ApiResponse<LeadResponse>> getById(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.ok(leadService.findById(id)));
    }

    @PutMapping("/leads/{id}/status")
    @Operation(summary = "Update lead status")
    public ResponseEntity<ApiResponse<LeadResponse>> updateStatus(
            @PathVariable Long id,
            @Valid @RequestBody LeadStatusRequest request) {
        return ResponseEntity.ok(ApiResponse.ok("Status updated", leadService.updateStatus(id, request)));
    }
}
