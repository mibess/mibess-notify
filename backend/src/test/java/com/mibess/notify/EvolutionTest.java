package com.mibess.notify;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mibess.notify.channel.ChannelProvider.*;
import com.mibess.notify.shared.*;
import com.mibess.notify.shared.ResourceStore.Resource;
import com.mibess.notify.template.TemplateRenderer;
import com.mibess.notify.webhook.EvolutionWebhookController;
import com.mibess.notify.whatsapp.WhatsAppEvolutionProvider;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class EvolutionTest {

  final Json json = new Json(new ObjectMapper());
  final Crypto crypto = new Crypto(
    "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
  );

  @Test
  void rendersValuesLiterallyWithoutRecursiveSubstitution() {
    var spec = json.read(
      "{\"body\":\"Olá {{name}}, {{name}}: {{total}}\",\"variables\":[\"name\",\"total\"]}"
    );
    var payload = json.read(
      "{\"data\":{\"name\":\"$1 {{total}}\",\"total\":12}}"
    );
    assertThat(TemplateRenderer.render(spec, payload)).isEqualTo(
      "Olá $1 {{total}}, $1 {{total}}: 12"
    );
    assertThatThrownBy(() ->
      TemplateRenderer.render(spec, json.read("{}"))
    ).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void sendsExactTextAndTreatsAmbiguousFailuresWithoutRetry() throws Exception {
    var received = new AtomicReference<String>();
    var key = new AtomicReference<String>();
    var response = new AtomicReference<>("{\"key\":{\"id\":\"evo-123\"}}");
    var status = new java.util.concurrent.atomic.AtomicInteger(201);
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/message/sendText/MIBESS_NOTIFY", exchange -> {
      received.set(
        new String(
          exchange.getRequestBody().readAllBytes(),
          StandardCharsets.UTF_8
        )
      );
      key.set(exchange.getRequestHeaders().getFirst("apikey"));
      var bytes = response.get().getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(status.get(), bytes.length);
      exchange.getResponseBody().write(bytes);
      exchange.close();
    });
    server.start();
    try {
      UUID id = UUID.randomUUID(),
        w = UUID.randomUUID();
      var spec = json.object().put("instanceName", "MIBESS_NOTIFY");
      spec.put(
        "encryptedCredentials",
        crypto.encrypt(
          "{\"apiKey\":\"instance-only-secret\",\"webhookToken\":\"separate-secret\"}",
          w + ":" + id
        )
      );
      var channel = new Resource(
        id,
        w,
        "MIBESS_NOTIFY",
        "Mibess",
        true,
        spec,
        "",
        ""
      );
      var provider = new WhatsAppEvolutionProvider(
        json,
        crypto,
        "http://127.0.0.1:" + server.getAddress().getPort()
      );
      var command = new SendCommand(
        "+5516993754887",
        json.object().put("text", "Olá $1\nPedido pronto"),
        channel
      );
      assertThat(provider.send(command).messageId()).isEqualTo("evo-123");
      assertThat(key.get()).isEqualTo("instance-only-secret");
      assertThat(json.read(received.get()).path("number").asText()).isEqualTo(
        "5516993754887"
      );
      assertThat(json.read(received.get()).path("text").asText()).isEqualTo(
        "Olá $1\nPedido pronto"
      );
      response.set("{}");
      assertThatThrownBy(() -> provider.send(command)).isInstanceOfSatisfying(
        DeliveryFailure.class,
        e -> {
          assertThat(e.ambiguous).isTrue();
          assertThat(e.retryable).isFalse();
        }
      );
      status.set(503);
      assertThatThrownBy(() -> provider.send(command)).isInstanceOfSatisfying(
        DeliveryFailure.class,
        e -> {
          assertThat(e.ambiguous).isTrue();
          assertThat(e.retryable).isFalse();
        }
      );
      status.set(429);
      assertThatThrownBy(() -> provider.send(command)).isInstanceOfSatisfying(
        DeliveryFailure.class,
        e -> {
          assertThat(e.ambiguous).isFalse();
          assertThat(e.retryable).isTrue();
        }
      );
    } finally {
      server.stop(0);
    }
  }

  @Test
  void normalizesOnlyDeliveryStatusesAndOptOutWithoutSecretsOrChatHistory() {
    var status = json.read(
      "{\"event\":\"messages.update\",\"apikey\":\"never-store\",\"data\":{\"keyId\":\"wa-1\",\"fromMe\":true,\"status\":\"DELIVERY_ACK\"}}"
    );
    var normalized = EvolutionWebhookController.normalize(json, status);
    assertThat(
      normalized.path("statuses").path(0).path("status").asText()
    ).isEqualTo("delivered");
    assertThat(normalized.toString()).doesNotContain("never-store");
    var inbound = json.read(
      "{\"event\":\"messages.upsert\",\"data\":{\"key\":{\"id\":\"in-1\",\"fromMe\":false,\"remoteJid\":\"5516993754887@s.whatsapp.net\"},\"message\":{\"conversation\":\"parar\"}}}"
    );
    assertThat(
      EvolutionWebhookController.normalize(json, inbound)
        .path("messages")
        .path(0)
        .path("text")
        .path("body")
        .asText()
    ).isEqualTo("PARAR");
    (
      (com.fasterxml.jackson.databind.node.ObjectNode) inbound
        .path("data")
        .path("message")
    ).put("conversation", "private conversation");
    assertThat(
      EvolutionWebhookController.normalize(json, inbound).path("messages")
    ).isEmpty();
  }
}
