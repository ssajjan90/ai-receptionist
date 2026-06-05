package com.aireceptionist.knowledgebase.ocr;

import com.aireceptionist.common.exception.ExternalServiceException;
import com.google.cloud.vision.v1.AnnotateImageRequest;
import com.google.cloud.vision.v1.BatchAnnotateImagesResponse;
import com.google.cloud.vision.v1.Feature;
import com.google.cloud.vision.v1.Image;
import com.google.cloud.vision.v1.ImageAnnotatorClient;
import com.google.protobuf.ByteString;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;

@Component
public class GoogleVisionOcrProvider implements OcrProvider {

    @Override
    public String providerName() {
        return "google";
    }

    @Override
    public String extractText(byte[] imageBytes, String mimeType) {
        try (ImageAnnotatorClient client = ImageAnnotatorClient.create()) {
            Image image = Image.newBuilder()
                    .setContent(ByteString.copyFrom(imageBytes))
                    .build();
            Feature feature = Feature.newBuilder()
                    .setType(Feature.Type.TEXT_DETECTION)
                    .build();
            AnnotateImageRequest request = AnnotateImageRequest.newBuilder()
                    .addFeatures(feature)
                    .setImage(image)
                    .build();
            BatchAnnotateImagesResponse response = client.batchAnnotateImages(List.of(request));
            if (response.getResponsesCount() == 0 || response.getResponses(0).hasError()) {
                throw new ExternalServiceException("Google Vision API returned an OCR error.");
            }
            return response.getResponses(0).getFullTextAnnotation().getText();
        } catch (IOException ex) {
            throw new ExternalServiceException("Google Vision API error: " + ex.getMessage(), ex);
        }
    }
}
