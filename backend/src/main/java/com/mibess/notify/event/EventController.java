package com.mibess.notify.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.mibess.notify.application.ApiKeyService;
import com.mibess.notify.auth.RateLimiter;
import com.mibess.notify.shared.Json;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeIn;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.security.*;
import java.time.OffsetDateTime;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@SecurityScheme(
  name = "ApplicationKey",
  type = SecuritySchemeType.APIKEY,
  in = SecuritySchemeIn.HEADER,
  paramName = "X-API-Key"
)
public class EventController {

  private final ApiKeyService keys;
  private final EventService events;
  private final RateLimiter limits;
  private final Json json;
  private final int limit;

  public EventController(
    ApiKeyService keys,
    EventService events,
    RateLimiter limits,
    Json json,
    @Value("${notify.rate-limit}") int limit
  ) {
    this.keys = keys;
    this.events = events;
    this.limits = limits;
    this.json = json;
    this.limit = limit;
  }

  @PostMapping("/api/v1/events")
  @Operation(
    summary = "Publicar evento de negócio",
    description = "Aceita o evento de forma assíncrona. Application inferida exclusivamente pela API key. Idempotency-Key obrigatório.",
    security = @SecurityRequirement(name = "ApplicationKey")
  )
  public ResponseEntity<EventService.Accepted> accept(
    @RequestHeader(value = "X-API-Key", required = false) String key,
    @RequestHeader(
      value = "Idempotency-Key",
      required = false
    ) String idempotency,
    @RequestBody JsonNode payload
  ) {
    var app = keys.authenticate(key);
    limits.check("events:" + app.id(), limit);
    if (
      !payload.isObject() || json.write(payload).length() > 65536
    ) throw new IllegalArgumentException(
      "Evento deve ser objeto JSON com até 64KB"
    );
    var fields = payload.fieldNames();
    while (fields.hasNext())
      if (
        !Set.of(
          "type",
          "occurredAt",
          "correlationId",
          "recipient",
          "data"
        ).contains(fields.next())
      ) throw new IllegalArgumentException("Evento contém campo não permitido");
    if (
      !payload.path("type").asText().matches("[A-Z][A-Z0-9_]{1,99}")
    ) throw new IllegalArgumentException("Tipo de evento inválido");
    if (!payload.path("data").isObject()) throw new IllegalArgumentException(
      "data deve ser um objeto"
    );
    if (
      payload.has("recipient") && !payload.path("recipient").isObject()
    ) throw new IllegalArgumentException("recipient deve ser um objeto");
    if (
      payload.path("correlationId").asText().length() > 150
    ) throw new IllegalArgumentException("correlationId muito longo");
    if (payload.has("occurredAt")) {
      try {
        OffsetDateTime.parse(payload.path("occurredAt").asText());
      } catch (Exception e) {
        throw new IllegalArgumentException(
          "occurredAt deve incluir data, hora e fuso"
        );
      }
    }
    return ResponseEntity.accepted().body(
      events.accept(app, idempotency, payload)
    );
  }
}
