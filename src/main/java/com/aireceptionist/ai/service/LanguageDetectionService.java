package com.aireceptionist.ai.service;

import org.apache.tika.langdetect.optimaize.OptimaizeLangDetector;
import org.apache.tika.language.detect.LanguageDetector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;

@Service
public class LanguageDetectionService {

    private static final Logger log = LoggerFactory.getLogger(LanguageDetectionService.class);

    private final LanguageDetector detector;

    public LanguageDetectionService() {
        LanguageDetector d = null;
        try {
            d = new OptimaizeLangDetector().loadModels();
        } catch (IOException ex) {
            log.error("Failed to load language detection models — all messages will default to ENGLISH: {}", ex.getMessage());
        }
        this.detector = d;
    }

    public synchronized Language detectLanguage(String text) {
        if (text == null || text.isBlank()) {
            return Language.ENGLISH;
        }
        if (isHinglish(text)) {
            return Language.HINGLISH;
        }
        if (detector == null) {
            return Language.ENGLISH;
        }
        try {
            String langCode = detector.detect(text).getLanguage();
            return switch (langCode != null ? langCode : "") {
                case "hi" -> Language.HINDI;
                case "en" -> Language.ENGLISH;
                default -> Language.ENGLISH;
            };
        } catch (Exception ex) {
            log.debug("Language detection failed, defaulting to ENGLISH: {}", ex.getMessage());
            return Language.ENGLISH;
        }
    }

    private boolean isHinglish(String text) {
        boolean hasDevanagari = false;
        boolean hasLatin = false;
        for (char c : text.toCharArray()) {
            if (c >= 'ऀ' && c <= 'ॿ') {
                hasDevanagari = true;
            } else if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')) {
                hasLatin = true;
            }
            if (hasDevanagari && hasLatin) {
                return true;
            }
        }
        return false;
    }
}
