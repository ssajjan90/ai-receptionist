package com.aireceptionist.appointment.controller;

import com.aireceptionist.appointment.dto.AppointmentRequest;
import com.aireceptionist.appointment.dto.AppointmentResponse;
import com.aireceptionist.appointment.dto.AppointmentStatusRequest;
import com.aireceptionist.appointment.service.AppointmentService;
import com.aireceptionist.common.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Tag(name = "Appointments", description = "Appointment scheduling and management")
public class AppointmentController {

    private final AppointmentService appointmentService;

    @PostMapping("/appointments")
    @Operation(summary = "Book a new appointment")
    public ResponseEntity<ApiResponse<AppointmentResponse>> create(@Valid @RequestBody AppointmentRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.created(appointmentService.create(request)));
    }

    @GetMapping("/tenants/{tenantId}/appointments")
    @Operation(summary = "List appointments for a tenant")
    @PreAuthorize("@authUtils.isCurrentUserSuperAdmin() or @authUtils.getCurrentUserTenantId() == #tenantId")
    public ResponseEntity<ApiResponse<List<AppointmentResponse>>> getByTenant(@PathVariable Long tenantId) {
        return ResponseEntity.ok(ApiResponse.ok(appointmentService.findByTenant(tenantId)));
    }

    @GetMapping("/appointments/{id}")
    @Operation(summary = "Get appointment by ID")
    @PreAuthorize("@authUtils.isCurrentUserSuperAdmin() or @authUtils.getCurrentUserTenantId() == @appointmentService.getTenantIdByAppointmentId(#id)")
    public ResponseEntity<ApiResponse<AppointmentResponse>> getById(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.ok(appointmentService.findById(id)));
    }

    @PutMapping("/appointments/{id}/status")
    @Operation(summary = "Update appointment status")
    @PreAuthorize("@authUtils.isCurrentUserSuperAdmin() or @authUtils.getCurrentUserTenantId() == @appointmentService.getTenantIdByAppointmentId(#id)")
    public ResponseEntity<ApiResponse<AppointmentResponse>> updateStatus(
            @PathVariable Long id,
            @Valid @RequestBody AppointmentStatusRequest request) {
        return ResponseEntity.ok(ApiResponse.ok("Status updated", appointmentService.updateStatus(id, request)));
    }
}
