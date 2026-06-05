package com.aireceptionist.ai.service;

public enum Language {
    ENGLISH, HINDI, HINGLISH, OTHER;

    public String toLangCode() {
        return switch (this) {
            case ENGLISH -> "en";
            case HINDI -> "hi";
            case HINGLISH -> "hi-en";
            case OTHER -> "en";
        };
    }

    public String toDisplayName() {
        return switch (this) {
            case ENGLISH -> "English";
            case HINDI -> "Hindi";
            case HINGLISH -> "Hinglish";
            case OTHER -> "English";
        };
    }
}
