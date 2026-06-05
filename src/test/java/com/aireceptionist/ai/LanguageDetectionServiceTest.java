package com.aireceptionist.ai;

import com.aireceptionist.ai.service.Language;
import com.aireceptionist.ai.service.LanguageDetectionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LanguageDetectionServiceTest {

    private LanguageDetectionService service;

    @BeforeEach
    void setUp() throws Exception {
        service = new LanguageDetectionService();
    }

    @Test
    void detectsEnglish() {
        Language result = service.detectLanguage("What is the price of iPhone 14?");
        assertThat(result).isEqualTo(Language.ENGLISH);
    }

    @Test
    void detectsHindi() {
        Language result = service.detectLanguage("आईफोन 14 का दाम क्या है?");
        assertThat(result).isEqualTo(Language.HINDI);
    }

    @Test
    void detectsHinglish_mixedDevanagariAndLatin() {
        Language result = service.detectLanguage("iPhone का price क्या है?");
        assertThat(result).isEqualTo(Language.HINGLISH);
    }

    @Test
    void detectsHinglish_withLatinBrand() {
        Language result = service.detectLanguage("Samsung S24 कितने का है?");
        assertThat(result).isEqualTo(Language.HINGLISH);
    }

    @Test
    void nullInputDefaultsToEnglish() {
        assertThat(service.detectLanguage(null)).isEqualTo(Language.ENGLISH);
    }

    @Test
    void blankInputDefaultsToEnglish() {
        assertThat(service.detectLanguage("   ")).isEqualTo(Language.ENGLISH);
    }

    @Test
    void pureLatinWithoutDevanagariIsNotHinglish() {
        Language result = service.detectLanguage("hello world");
        assertThat(result).isNotEqualTo(Language.HINGLISH);
    }
}
