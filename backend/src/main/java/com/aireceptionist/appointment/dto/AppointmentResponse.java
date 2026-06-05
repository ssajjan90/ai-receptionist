package com.aireceptionist.appointment.dto;

import com.aireceptionist.appointment.entity.Appointment.AppointmentStatus;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class AppointmentResponse {

    private Long id;
    private Long tenantId;
    private String customerName;
    private String phone;
    private String serviceName;
    private LocalDateTime appointmentTime;
    private AppointmentStatus status;
    private String notes;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
