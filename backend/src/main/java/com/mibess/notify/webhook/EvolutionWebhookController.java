package com.mibess.notify.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mibess.notify.shared.*;
import com.mibess.notify.shared.ResourceStore.*;
import com.mibess.notify.whatsapp.WhatsAppEvolutionProvider;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.http.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/webhooks/evolution/whatsapp")
public class EvolutionWebhookController {

  private final JdbcClient db;
  private final ResourceStore store;
  private final WhatsAppEvolutionProvider evolution;
  private final WebhookService webhooks;
  private final Json json;

  public EvolutionWebhookController(
    JdbcClient db,
    ResourceStore store,
    WhatsAppEvolutionProvider evolution,
    WebhookService webhooks,
    Json json
  ) {
    this.db = db;
    this.store = store;
    this.evolution = evolution;
    this.webhooks = webhooks;
    this.json = json;
  }

  @PostMapping("/{channelId}")
  public ResponseEntity<Void> receive(
    @PathVariable UUID channelId,
    @RequestHeader(
      value = "X-Notify-Webhook-Token",
      required = false
    ) String token,
    @RequestBody byte[] bytes
  ) {
    if (bytes.length > 262144) throw new ResponseStatusException(
      HttpStatus.PAYLOAD_TOO_LARGE
    );
    if (
      token == null || token.isBlank() || token.length() > 500
    ) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    var workspace = db
      .sql(
        "SELECT workspace_id FROM channel_connections WHERE id=:id AND enabled AND spec->>'provider'='WHATSAPP_EVOLUTION'"
      )
      .param("id", channelId)
      .query(UUID.class)
      .optional()
      .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
    var channel = store.get(Kind.CHANNELS, workspace, channelId);
    String expected = evolution
      .credentials(channel)
      .path("webhookToken")
      .asText();
    if (
      expected.isBlank() ||
      !MessageDigest.isEqual(
        token.getBytes(StandardCharsets.UTF_8),
        expected.getBytes(StandardCharsets.UTF_8)
      )
    ) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    var body = json.read(new String(bytes, StandardCharsets.UTF_8));
    if (
      body == null ||
      !channel
        .spec()
        .path("instanceName")
        .asText()
        .equals(body.path("instance").asText())
    ) throw new ResponseStatusException(
      HttpStatus.BAD_REQUEST,
      "Instância inválida"
    );
    // Store only normalized statuses and opt-out commands. Evolution includes its apikey in raw payloads.
    var value = normalize(json, body);
    if (
      !value.path("statuses").isEmpty() || !value.path("messages").isEmpty()
    ) webhooks.accept(
      channel,
      value,
      Crypto.hash(channel.id() + ":" + json.write(value))
    );
    return ResponseEntity.ok().build();
  }

  public static ObjectNode normalize(Json json, JsonNode body) {
    var value = json.object();
    var statuses = value.putArray("statuses");
    var messages = value.putArray("messages");
    var data = body.path("data");
    Iterable<JsonNode> entries = data.isArray() ? data : List.of(data);
    for (var item : entries) {
      String event = body.path("event").asText();
      if (event.equals("messages.update") && item.path("fromMe").asBoolean()) {
        String status = switch (item.path("status").asText()) {
          case "SERVER_ACK", "2" -> "sent";
          case "DELIVERY_ACK", "3" -> "delivered";
          case "READ", "PLAYED", "4", "5" -> "read";
          case "ERROR", "0" -> "failed";
          default -> "";
        };
        String id = item.path("keyId").asText();
        if (!status.isBlank() && !id.isBlank()) {
          var s = statuses.addObject();
          s.put("id", id);
          s.put("status", status);
          if (status.equals("failed")) s.putArray("errors")
            .addObject()
            .put("code", "EVOLUTION_ERROR");
        }
      } else if (
        event.equals("messages.upsert") &&
        !item.path("key").path("fromMe").asBoolean()
      ) {
        var key = item.path("key");
        String from = key.path("remoteJid").asText();
        if (!from.endsWith("@s.whatsapp.net")) from = key
          .path("remoteJidAlt")
          .asText();
        if (!from.matches("[1-9][0-9]{7,14}@s\\.whatsapp\\.net")) continue;
        String text = item
          .path("message")
          .path("conversation")
          .asText(
            item
              .path("message")
              .path("extendedTextMessage")
              .path("text")
              .asText()
          )
          .trim()
          .toUpperCase(Locale.ROOT);
        if (
          !Set.of("STOP", "SAIR", "PARAR", "CANCELAR", "UNSUBSCRIBE").contains(
            text
          ) ||
          key.path("id").asText().isBlank()
        ) continue;
        var m = messages.addObject();
        m.put("id", key.path("id").asText());
        m.put("from", from.split("@")[0]);
        m.put("type", "text");
        m.putObject("text").put("body", text);
      }
    }
    return value;
  }
}
