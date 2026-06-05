package com.aireceptionist.integration.calendar;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * Placeholder for calendar integration (Google Calendar / Calendly / custom).
 */
@Slf4j
@Service
public class CalendarService {

    public String createEvent(Long tenantId, String customerName, String serviceName, LocalDateTime time) {
        log.info("[Calendar STUB] Creating event for tenant {} | Customer: {} | Service: {} | Time: {}",
                tenantId, customerName, serviceName, time);
        // TODO: integrate with Google Calendar API or similar
        return "STUB_EVENT_ID_" + System.currentTimeMillis();
    }

    public void cancelEvent(String eventId) {
        log.info("[Calendar STUB] Cancelling event: {}", eventId);
        // TODO: integrate with calendar API
    }
}
