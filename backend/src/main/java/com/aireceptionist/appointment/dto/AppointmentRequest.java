package com.aireceptionist.appointment.dto;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AppointmentRequest {

    @NotNull(message = "Tenant ID is required")
    private Long tenantId;

    @NotBlank(message = "Customer name is required")
    @Size(max = 255, message = "Customer name must be at most 255 characters")
    private String customerName;

    @Pattern(regexp = "^\\+?[0-9\\s\\-().]{7,20}$", message = "Invalid phone number")
    private String phone;

    @NotBlank(message = "Service name is required")
    @Size(max = 255, message = "Service name must be at most 255 characters")
    private String serviceName;

    @NotNull(message = "Appointment time is required")
    @Future(message = "Appointment time must be in the future")
    private LocalDateTime appointmentTime;

    @Size(max = 1000, message = "Notes must be at most 1000 characters")
    private String notes;
}
