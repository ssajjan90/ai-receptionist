package com.aireceptionist.admin.dto;

import java.util.List;

public record BroadcastResult(
        int sent,
        int failed,
        List<String> failedTenantIds
) {
}
