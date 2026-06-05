package com.aireceptionist.tenant.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class TenantResponse {

    private Long id;
    private String name;
    private String industry;
    private String phone;
    private String email;
    private String address;
    private String workingHours;
    private String defaultLanguage;
    private String supportedLanguages;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
