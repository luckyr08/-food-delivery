package com.fooddelivery.payment;

import com.fooddelivery.common.error.ApiException;
import com.fooddelivery.common.error.BadRequestException;
import com.fooddelivery.common.error.ErrorCode;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

/**
 * Gateway → us. Public URL, so authenticity comes from the HMAC signature over the RAW body (read as bytes
 * before any JSON parsing). 2xx = handled (incl. duplicates); gateways retry anything else.
 */
@RestController
@RequestMapping("/api/payments/webhook")
public class PaymentWebhookController {

    private final WebhookSignatureVerifier verifier;
    private final PaymentWebhookService service;
    private final ObjectMapper objectMapper;

    public PaymentWebhookController(WebhookSignatureVerifier verifier, PaymentWebhookService service,
                                    ObjectMapper objectMapper) {
        this.verifier = verifier;
        this.service = service;
        this.objectMapper = objectMapper;
    }

    @PostMapping
    public Map<String, String> receive(@RequestHeader("X-Signature") String signature, @RequestBody byte[] body) {
        verifier.verify(signature, body); // 401 before we even parse
        WebhookEvent event;
        try {
            event = objectMapper.readValue(body, WebhookEvent.class);
        } catch (JacksonException e) {
            throw new BadRequestException(ErrorCode.INVALID_REQUEST, "Malformed webhook payload");
        }
        if (event.eventId() == null || event.type() == null) {
            throw new BadRequestException(ErrorCode.INVALID_REQUEST, "eventId and type are required");
        }
        PaymentWebhookService.Outcome outcome = service.handle(event);
        if (outcome == PaymentWebhookService.Outcome.AMOUNT_MISMATCH) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, ErrorCode.PAYMENT_AMOUNT_MISMATCH,
                    "Captured amount does not match the order total") {
            };
        }
        return Map.of("status", outcome.name());
    }
}
