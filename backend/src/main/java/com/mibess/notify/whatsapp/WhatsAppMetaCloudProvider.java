package com.mibess.notify.whatsapp;

import com.fasterxml.jackson.databind.JsonNode;
import com.mibess.notify.channel.ChannelProvider;
import com.mibess.notify.shared.*;
import com.mibess.notify.shared.ResourceStore.Resource;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class WhatsAppMetaCloudProvider implements ChannelProvider {

  private final Json json;
  private final Crypto crypto;
  private final String graphVersion;
  private final HttpClient http = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(10))
    .followRedirects(HttpClient.Redirect.NEVER)
    .build();

  public WhatsAppMetaCloudProvider(
    Json json,
    Crypto crypto,
    @Value("${notify.graph-version}") String graphVersion
  ) {
    this.json = json;
    this.crypto = crypto;
    this.graphVersion = graphVersion;
  }

  public String code() {
    return "WHATSAPP_META";
  }

  public boolean validateConfiguration(Resource c) {
    return (
      c.spec().path("phoneNumberId").asText().matches("[0-9]+") &&
      c.spec().hasNonNull("encryptedCredentials")
    );
  }

  public JsonNode credentials(Resource c) {
    return json.read(
      crypto.decrypt(
        c.spec().path("encryptedCredentials").asText(),
        c.workspaceId() + ":" + c.id()
      )
    );
  }

  public Result send(SendCommand command) {
    var c = command.connection();
    if (!validateConfiguration(c)) throw new DeliveryFailure(
      "META_NOT_CONFIGURED",
      false,
      false,
      0
    );
    String version = c.spec().path("graphApiVersion").asText(graphVersion);
    if (!version.matches("v[0-9]{2}\\.0")) throw new DeliveryFailure(
      "META_VERSION_INVALID",
      false,
      false,
      0
    );
    List<Map<String, String>> parameters = new ArrayList<>();
    for (JsonNode parameter : command.message().path("parameters"))
      parameters.add(Map.of("type", "text", "text", parameter.asText()));
    var template = json.object();
    template.put("name", command.message().path("templateName").asText());
    template.set(
      "language",
      json.tree(Map.of("code", command.message().path("language").asText()))
    );
    if (!parameters.isEmpty()) template.set(
      "components",
      json.tree(List.of(Map.of("type", "body", "parameters", parameters)))
    );
    var body = json.object();
    body.put("messaging_product", "whatsapp");
    body.put("recipient_type", "individual");
    body.put("to", command.recipient().replace("+", ""));
    body.put("type", "template");
    body.set("template", template);
    try {
      var request = HttpRequest.newBuilder(
        URI.create(
          "https://graph.facebook.com/" +
            version +
            "/" +
            c.spec().path("phoneNumberId").asText() +
            "/messages"
        )
      )
        .timeout(Duration.ofSeconds(30))
        .header(
          "Authorization",
          "Bearer " + credentials(c).path("accessToken").asText()
        )
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(json.write(body)))
        .build();
      var response = http.send(request, HttpResponse.BodyHandlers.ofString());
      JsonNode result;
      try {
        result = json.read(response.body());
      } catch (IllegalArgumentException e) {
        throw new DeliveryFailure(
          "META_INVALID_RESPONSE",
          false,
          true,
          response.statusCode()
        );
      }
      if (response.statusCode() >= 200 && response.statusCode() < 300) {
        String id = result.path("messages").path(0).path("id").asText();
        if (id.isBlank()) throw new DeliveryFailure(
          "META_MISSING_MESSAGE_ID",
          false,
          true,
          response.statusCode()
        );
        return new Result(id, response.statusCode());
      }
      var error = result.path("error");
      int code = error.path("code").asInt();
      boolean retry =
        response.statusCode() == 429 ||
        error.path("is_transient").asBoolean() ||
        Set.of(4, 80007, 130429, 131000, 131016, 131048, 131056).contains(code);
      // Unstructured gateway/server errors may have accepted the message: do not blindly resend.
      boolean ambiguous = response.statusCode() >= 500 && error.isMissingNode();
      throw new DeliveryFailure(
        "META_" + code,
        retry && !ambiguous,
        ambiguous,
        response.statusCode()
      );
    } catch (java.net.http.HttpConnectTimeoutException e) {
      throw new DeliveryFailure("META_CONNECT_TIMEOUT", true, false, 0);
    } catch (java.io.IOException e) {
      throw new DeliveryFailure("META_DELIVERY_UNKNOWN", false, true, 0);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new DeliveryFailure("META_DELIVERY_UNKNOWN", false, true, 0);
    }
  }
}
