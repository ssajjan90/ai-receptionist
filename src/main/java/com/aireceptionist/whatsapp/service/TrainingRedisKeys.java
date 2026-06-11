package com.aireceptionist.whatsapp.service;

import java.time.Duration;

final class TrainingRedisKeys {

    static final String PREFIX = "train:";
    static final Duration TTL = Duration.ofHours(24);

    private TrainingRedisKeys() {}
}
