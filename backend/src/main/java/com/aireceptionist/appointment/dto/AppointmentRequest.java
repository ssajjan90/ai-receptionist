package com.aireceptionist.appointment.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AppointmentRequest {

    @NotNull(message = "Tenant ID is required")
    private Long tenantId;

    private String customerName;
    private String phone;
    private String serviceName;
    private LocalDateTime appointmentTime;
    private String notes;
}
