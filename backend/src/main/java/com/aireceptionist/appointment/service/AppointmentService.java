package com.aireceptionist.appointment.service;

import com.aireceptionist.appointment.dto.AppointmentRequest;
import com.aireceptionist.appointment.dto.AppointmentResponse;
import com.aireceptionist.appointment.dto.AppointmentStatusRequest;
import com.aireceptionist.appointment.entity.Appointment;
import com.aireceptionist.appointment.repository.AppointmentRepository;
import com.aireceptionist.common.exception.ResourceNotFoundException;
import com.aireceptionist.tenant.service.TenantService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AppointmentService {

    private final AppointmentRepository appointmentRepository;
    private final TenantService tenantService;

    public AppointmentResponse findById(Long id) {
        return toResponse(getOrThrow(id));
    }

    public List<AppointmentResponse> findByTenant(Long tenantId) {
        tenantService.getTenantOrThrow(tenantId);
        return appointmentRepository.findByTenantId(tenantId).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public AppointmentResponse create(AppointmentRequest request) {
        tenantService.getTenantOrThrow(request.getTenantId());
        Appointment appointment = Appointment.builder()
                .tenantId(request.getTenantId())
                .customerName(request.getCustomerName())
                .phone(request.getPhone())
                .serviceName(request.getServiceName())
                .appointmentTime(request.getAppointmentTime())
                .notes(request.getNotes())
                .build();
        return toResponse(appointmentRepository.save(appointment));
    }

    @Transactional
    public AppointmentResponse updateStatus(Long id, AppointmentStatusRequest request) {
        Appointment appointment = getOrThrow(id);
        appointment.setStatus(request.getStatus());
        return toResponse(appointmentRepository.save(appointment));
    }

    private Appointment getOrThrow(Long id) {
        return appointmentRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Appointment", id));
    }

    private AppointmentResponse toResponse(Appointment a) {
        return AppointmentResponse.builder()
                .id(a.getId())
                .tenantId(a.getTenantId())
                .customerName(a.getCustomerName())
                .phone(a.getPhone())
                .serviceName(a.getServiceName())
                .appointmentTime(a.getAppointmentTime())
                .status(a.getStatus())
                .notes(a.getNotes())
                .createdAt(a.getCreatedAt())
                .updatedAt(a.getUpdatedAt())
                .build();
    }
}
