package com.aireceptionist.knowledgebase.ocr;

import com.aireceptionist.common.exception.ExternalServiceException;
import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.TesseractException;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.io.IOException;

@Component
public class TesseractOcrProvider implements OcrProvider {

    @Override
    public String providerName() {
        return "tesseract";
    }

    @Override
    public String extractText(byte[] imageBytes, String mimeType) {
        try {
            var image = ImageIO.read(new ByteArrayInputStream(imageBytes));
            if (image == null) {
                throw new ExternalServiceException("Unable to decode image for OCR.");
            }
            Tesseract tesseract = new Tesseract();
            return tesseract.doOCR(image);
        } catch (IOException | TesseractException ex) {
            throw new ExternalServiceException("Tesseract OCR error: " + ex.getMessage(), ex);
        }
    }
}
