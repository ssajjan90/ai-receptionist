package com.aireceptionist.knowledgebase;

import com.aireceptionist.knowledgebase.exception.OcrLowConfidenceException;
import com.aireceptionist.knowledgebase.ocr.OcrProvider;
import com.aireceptionist.knowledgebase.service.OcrIngestionService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OcrIngestionServiceTest {

    private final OcrIngestionService service = new OcrIngestionService(
            List.of(new StubOcrProvider()),
            "stub"
    );

    @Test
    void parseProductPricesExtractsIndianPricePatterns() {
        var entries = service.parseProductPrices("""
                Tea - ₹20
                Coffee: Rs. 30
                Sandwich  150/-
                Header
                """);

        assertThat(entries)
                .extracting(entry -> entry.productName() + "=" + entry.price())
                .containsExactly("Coffee=30", "Sandwich=150", "Tea=20");
    }

    @Test
    void parseProductPricesTreatsDuplicateProductsAsUpdates() {
        var entries = service.parseProductPrices("""
                Tea - ₹20
                Coffee - ₹30
                Tea - ₹25
                """);

        assertThat(entries).hasSize(2);
        assertThat(entries)
                .filteredOn(entry -> entry.productName().equals("Tea"))
                .singleElement()
                .extracting("price")
                .isEqualTo("25");
    }

    @Test
    void parseProductPricesThrowsLowConfidenceWhenFewerThanTwoEntries() {
        assertThatThrownBy(() -> service.parseProductPrices("""
                Tea - ₹20
                Menu Header
                """))
                .isInstanceOf(OcrLowConfidenceException.class)
                .extracting("errorCode")
                .isEqualTo("OCR_LOW_CONFIDENCE");
    }

    private static class StubOcrProvider implements OcrProvider {

        @Override
        public String providerName() {
            return "stub";
        }

        @Override
        public String extractText(byte[] imageBytes, String mimeType) {
            return "Tea - ₹20\nCoffee - ₹30";
        }
    }
}
