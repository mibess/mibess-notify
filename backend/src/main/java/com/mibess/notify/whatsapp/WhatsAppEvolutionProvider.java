package com.mibess.notify.whatsapp;

import com.fasterxml.jackson.databind.JsonNode;
import com.mibess.notify.channel.ChannelProvider;
import com.mibess.notify.shared.*;
import com.mibess.notify.shared.ResourceStore.Resource;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class WhatsAppEvolutionProvider implements ChannelProvider {

  private final Json json;
  private final Crypto crypto;
  private final String baseUrl;
  private final HttpClient http = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(10))
    .followRedirects(HttpClient.Redirect.NEVER)
    .build();

  public WhatsAppEvolutionProvider(
    Json json,
    Crypto crypto,
    @Value("${notify.evolution-url:}") String baseUrl
  ) {
    this.json = json;
    this.crypto = crypto;
    // Endpoint is operator-controlled, never taken from channel input (SSRF/credential exfiltration).
    this.baseUrl = baseUrl.replaceAll("/+$", "");
    if (!baseUrl.isBlank()) {
      URI uri = URI.create(baseUrl);
      if (
        !java.util.Set.of("http", "https").contains(uri.getScheme()) ||
        uri.getHost() == null ||
        uri.getUserInfo() != null ||
        uri.getQuery() != null ||
        uri.getFragment() != null
      ) throw new IllegalArgumentException("EVOLUTION_API_URL inválida");
    }
  }

  public String code() {
    return "WHATSAPP_EVOLUTION";
  }

  public JsonNode credentials(Resource c) {
    return json.read(
      crypto.decrypt(
        c.spec().path("encryptedCredentials").asText(),
        c.workspaceId() + ":" + c.id()
      )
    );
  }

  public boolean validateConfiguration(Resource c) {
    if (
      baseUrl.isBlank() ||
      !c.spec().path("instanceName").asText().matches("[A-Za-z0-9_-]{1,100}") ||
      !c.spec().hasNonNull("encryptedCredentials")
    ) return false;
    var keys = credentials(c);
    return (
      !keys.path("apiKey").asText().isBlank() &&
      !keys.path("webhookToken").asText().isBlank()
    );
  }

  public Result send(SendCommand command) {
    var c = command.connection();
    if (!validateConfiguration(c)) throw new DeliveryFailure(
      "EVOLUTION_NOT_CONFIGURED",
      false,
      false,
      0
    );
    String text = command.message().path("text").asText();
    if (text.isBlank() || text.length() > 4096) throw new DeliveryFailure(
      "EVOLUTION_INVALID_TEXT",
      false,
      false,
      0
    );
    try {
      var request = HttpRequest.newBuilder(
        URI.create(
          baseUrl +
            "/message/sendText/" +
            c.spec().path("instanceName").asText()
        )
      )
        .timeout(Duration.ofSeconds(30))
        .header("apikey", credentials(c).path("apiKey").asText())
        .header("Content-Type", "application/json")
        .POST(
          HttpRequest.BodyPublishers.ofString(
            json.write(
              Map.of(
                "number",
                command.recipient().replace("+", ""),
                "text",
                text,
                "linkPreview",
                false
              )
            )
          )
        )
        .build();
      var response = http.send(request, HttpResponse.BodyHandlers.ofString());
      int status = response.statusCode();
      if (status < 200 || status >= 300) {
        // A server failure or redirect may follow acceptance; never automatically duplicate a send.
        throw new DeliveryFailure(
          "EVOLUTION_HTTP_" + status,
          status == 429,
          status >= 500 || (status >= 300 && status < 400),
          status
        );
      }
      JsonNode result;
      try {
        result = json.read(response.body());
      } catch (IllegalArgumentException e) {
        throw new DeliveryFailure(
          "EVOLUTION_INVALID_RESPONSE",
          false,
          true,
          status
        );
      }
      String id = result == null ? "" : result.path("key").path("id").asText();
      if (id.isBlank()) throw new DeliveryFailure(
        "EVOLUTION_MISSING_MESSAGE_ID",
        false,
        true,
        status
      );
      return new Result(id, status);
    } catch (HttpConnectTimeoutException e) {
      throw new DeliveryFailure("EVOLUTION_CONNECT_TIMEOUT", true, false, 0);
    } catch (java.io.IOException e) {
      throw new DeliveryFailure("EVOLUTION_DELIVERY_UNKNOWN", false, true, 0);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new DeliveryFailure("EVOLUTION_DELIVERY_UNKNOWN", false, true, 0);
    }
  }
}
