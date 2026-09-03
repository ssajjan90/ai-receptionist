package com.aireceptionist.api;

import com.aireceptionist.common.api.GlobalExceptionHandler;
import com.aireceptionist.common.exception.ExternalServiceException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class GlobalExceptionHandlerTest {

    private MockMvc mockMvc;

    record SampleRequest(@NotBlank String name) {}

    @RestController
    static class FakeController {
        @GetMapping("/v1/test-error")
        public String internalError() {
            throw new RuntimeException("secret internal details — must not leak");
        }

        @GetMapping("/v1/test-get-only")
        public String getOnly() {
            return "ok";
        }

        @GetMapping("/v1/test-external-service-error")
        public String externalServiceError() {
            throw new ExternalServiceException("Google Vision API key AIza-secret-internal-detail rejected");
        }

        @PostMapping("/v1/test-body")
        public String withBody(@Valid @RequestBody SampleRequest request) {
            return request.name();
        }
    }

    @BeforeEach
    void setUp() {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();

        mockMvc = MockMvcBuilders
                .standaloneSetup(new FakeController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator)
                .build();
    }

    @Test
    void unexpectedExceptionReturns500WithoutLeakingMessage() throws Exception {
        mockMvc.perform(get("/v1/test-error").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("An unexpected error occurred"))
                .andExpect(content().string(not(containsString("secret internal details"))));
    }

    @Test
    void methodNotAllowedReturns405() throws Exception {
        mockMvc.perform(post("/v1/test-get-only").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("METHOD_NOT_ALLOWED"));
    }

    @Test
    void malformedJsonBodyReturns400() throws Exception {
        mockMvc.perform(post("/v1/test-body")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content("{bad json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("MALFORMED_REQUEST"));
    }

    @Test
    void beanValidationFailureReturns400WithFieldErrors() throws Exception {
        mockMvc.perform(post("/v1/test-body")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details.name").exists());
    }

    @Test
    void externalServiceExceptionReturns502WithoutLeakingRawMessage() throws Exception {
        mockMvc.perform(get("/v1/test-external-service-error").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("EXTERNAL_SERVICE_ERROR"))
                .andExpect(content().string(not(containsString("AIza-secret-internal-detail"))));
    }

    @Test
    void errorResponseContainsTimestamp() throws Exception {
        mockMvc.perform(get("/v1/test-error").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.timestamp").exists());
    }
}
