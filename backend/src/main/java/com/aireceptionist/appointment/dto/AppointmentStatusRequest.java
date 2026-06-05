package com.aireceptionist.appointment.dto;

import com.aireceptionist.appointment.entity.Appointment.AppointmentStatus;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class AppointmentStatusRequest {

    @NotNull(message = "Status is required")
    private AppointmentStatus status;
}
