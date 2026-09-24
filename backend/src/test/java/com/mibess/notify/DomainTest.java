package com.mibess.notify;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mibess.notify.contact.ConsentService;
import com.mibess.notify.notification.*;
import com.mibess.notify.routing.Conditions;
import com.mibess.notify.shared.*;
import com.mibess.notify.template.TemplateRenderer;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class DomainTest {

  final Json json = new Json(new ObjectMapper());

  @Test
  void conditionsAreTypedAndConjunctive() {
    var payload = json.read(
      "{\"data\":{\"status\":\"OUT_FOR_DELIVERY\",\"amount\":189.90,\"tags\":[\"vip\"]}}"
    );
    assertThat(
      Conditions.matches(
        payload,
        json.read(
          "[{\"field\":\"data.status\",\"operator\":\"EQUALS\",\"value\":\"OUT_FOR_DELIVERY\"},{\"field\":\"data.amount\",\"operator\":\"GREATER_THAN\",\"value\":100}]"
        )
      )
    ).isTrue();
    for (String operator : Conditions.OPERATORS) {
      String field =
        operator.equals("GREATER_THAN") || operator.equals("LESS_THAN")
          ? "data.amount"
          : "data.status";
      var c = json.object();
      c.put("field", field);
      c.put("operator", operator);
      c.set(
        "value",
        operator.equals("IN")
          ? json.tree(List.of("OUT_FOR_DELIVERY"))
          : operator.equals("GREATER_THAN")
            ? json.tree(100)
            : operator.equals("LESS_THAN")
              ? json.tree(200)
              : json.tree("OUT_FOR_DELIVERY")
      );
      assertThatCode(() ->
        Conditions.matches(payload, json.tree(List.of(c)))
      ).doesNotThrowAnyException();
    }
    assertThat(
      Conditions.matches(
        payload,
        json.read(
          "[{\"field\":\"data.amount\",\"operator\":\"EQUALS\",\"value\":189.9}]"
        )
      )
    ).isTrue();
    assertThat(
      Conditions.matches(
        payload,
        json.read(
          "[{\"field\":\"data.missing\",\"operator\":\"NOT_EQUALS\",\"value\":1}]"
        )
      )
    ).isFalse();
    assertThatThrownBy(() ->
      Conditions.matches(
        payload,
        json.read("[{\"field\":\"data.amount\",\"operator\":\"eval\"}]")
      )
    ).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void variablesMustExistAndKeepMetaOrder() {
    var spec = json.read(
      "{\"body\":\"Oi {{customerName}}, pedido {{orderNumber}}\",\"variables\":[\"customerName\",\"orderNumber\"]}"
    );
    assertThat(
      TemplateRenderer.parameters(
        spec,
        json.read(
          "{\"data\":{\"orderNumber\":\"1842\",\"customerName\":\"Mariana\"}}"
        )
      )
    ).containsExactly("Mariana", "1842");
    assertThatThrownBy(() ->
      TemplateRenderer.parameters(
        spec,
        json.read("{\"data\":{\"customerName\":\"Mariana\"}}")
      )
    ).hasMessageContaining("orderNumber");
  }

  @Test
  void encryptionAuthenticatesWorkspaceAndCiphertext() {
    var crypto = new Crypto(Base64.getEncoder().encodeToString(new byte[32]));
    String encrypted = crypto.encrypt("sensitive-value", "workspace:channel");
    assertThat(encrypted).doesNotContain("sensitive-value");
    assertThat(crypto.decrypt(encrypted, "workspace:channel")).isEqualTo(
      "sensitive-value"
    );
    assertThatThrownBy(() ->
      crypto.decrypt(encrypted, "other:channel")
    ).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void signatureUsesExactBytes() throws Exception {
    byte[] body = "{\"id\":1}".getBytes(StandardCharsets.UTF_8);
    var mac = Mac.getInstance("HmacSHA256");
    mac.init(
      new SecretKeySpec(
        "test-secret".getBytes(StandardCharsets.UTF_8),
        "HmacSHA256"
      )
    );
    String signature = "sha256=" + HexFormat.of().formatHex(mac.doFinal(body));
    assertThat(Crypto.signature(body, "test-secret", signature)).isTrue();
    assertThat(
      Crypto.signature("{}".getBytes(), "test-secret", signature)
    ).isFalse();
    assertThat(Crypto.signature(body, "test-secret", null)).isFalse();
  }

  @Test
  void retryStopsAndDeliveryDoesNotRegress() {
    var retry = new RetryPolicy("60,300,900,3600");
    assertThat(retry.delay(0)).contains(Duration.ofMinutes(1));
    assertThat(retry.delay(4)).isEmpty();
    assertThat(
      NotificationStatus.SENT.accepts(NotificationStatus.DELIVERED)
    ).isTrue();
    assertThat(
      NotificationStatus.DELIVERED.accepts(NotificationStatus.SENT)
    ).isFalse();
    assertThat(
      NotificationStatus.READ.accepts(NotificationStatus.FAILED)
    ).isFalse();
    assertThat(
      NotificationStatus.CANCELLED.accepts(NotificationStatus.READ)
    ).isFalse();
  }

  @Test
  void consentRequiresEvidenceAndRejectsRevocation() {
    var c = json.object();
    c.put("whatsappOptIn", true);
    assertThat(ConsentService.validOptIn(c)).isFalse();
    c.put("whatsappOptInAt", Instant.now().toString());
    c.put("whatsappOptInSource", "test");
    assertThat(ConsentService.validOptIn(c)).isTrue();
    c.put("whatsappOptOutAt", Instant.now().toString());
    assertThat(ConsentService.validOptIn(c)).isFalse();
    assertThat(ConsentService.phone("+55 (16) 99999-9999")).isEqualTo(
      "+5516999999999"
    );
    assertThatThrownBy(() -> ConsentService.phone("16999999999")).isInstanceOf(
      IllegalArgumentException.class
    );
  }
}
