package com.aireceptionist.voice;

import com.aireceptionist.common.exception.ExternalServiceException;
import com.aireceptionist.voice.service.ExotelApiClient;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** Story 6.3 (AC3): outbound Exotel connect request shape (per the story's Dev Notes). */
class ExotelApiClientTest {

    private final RestTemplate restTemplate = new RestTemplate();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
    private final ExotelApiClient client =
            new ExotelApiClient(restTemplate, "https://api.exotel.test/", "acme", "key", "token");

    @Test
    void connectCallPostsBasicAuthFormToConnectEndpoint() {
        server.expect(requestTo("https://api.exotel.test/v1/Accounts/acme/Calls/connect.json"))
                .andExpect(method(POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Basic "
                        + Base64.getEncoder().encodeToString("key:token".getBytes(StandardCharsets.UTF_8))))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(content().formDataContains(java.util.Map.of(
                        "From", "CA-1", "To", "+919111111111", "CallerId", "+918000000000")))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        client.connectCall("CA-1", "+919111111111", "+918000000000");

        server.verify();
    }

    @Test
    void exotelErrorBecomesExternalServiceException() {
        server.expect(requestTo("https://api.exotel.test/v1/Accounts/acme/Calls/connect.json"))
                .andRespond(withServerError());

        assertThatThrownBy(() -> client.connectCall("CA-1", "+919111111111", null))
                .isInstanceOf(ExternalServiceException.class);
    }
}
