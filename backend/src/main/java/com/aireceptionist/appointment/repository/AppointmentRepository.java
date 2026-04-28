package com.aireceptionist.appointment.repository;

import com.aireceptionist.appointment.entity.Appointment;
import com.aireceptionist.appointment.entity.Appointment.AppointmentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface AppointmentRepository extends JpaRepository<Appointment, Long> {

    List<Appointment> findByTenantId(Long tenantId);

    List<Appointment> findByTenantIdAndStatus(Long tenantId, AppointmentStatus status);

    List<Appointment> findByTenantIdAndAppointmentTimeBetween(Long tenantId, LocalDateTime from, LocalDateTime to);

    Optional<Appointment> findByIdAndTenantId(Long id, Long tenantId);
}
