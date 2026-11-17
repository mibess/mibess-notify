package com.mibess.notify.channel;

import com.fasterxml.jackson.databind.JsonNode;
import com.mibess.notify.contact.ConsentService;
import com.mibess.notify.shared.ResourceStore.Resource;
import jakarta.mail.internet.InternetAddress;
import java.util.Locale;

/** Address and permission semantics belong to the delivery channel, not the event. */
public final class DeliveryAddress {

  private DeliveryAddress() {}

  public static String channel(Resource connection) {
    return connection.spec().path("provider").asText().equals("EMAIL_SMTP")
      ? "EMAIL"
      : "WHATSAPP";
  }

  public static String email(String raw) {
    if (
      raw != null && (raw.indexOf('\r') >= 0 || raw.indexOf('\n') >= 0)
    ) throw new IllegalArgumentException("E-mail contém quebra de linha.");
    String value = raw == null ? "" : raw.trim();
    if (
      value.length() > 254 ||
      !value.matches(
        "[^\\s@<>,;:\\\"\\[\\]]+@[^\\s@<>,;:\\\"\\[\\]]+\\.[^\\s@<>,;:\\\"\\[\\]]+"
      )
    ) throw new IllegalArgumentException(
      "Informe um único e-mail válido, sem nome ou cabeçalhos."
    );
    try {
      InternetAddress address = new InternetAddress(value, true);
      address.validate();
      if (
        address.isGroup() ||
        address.getPersonal() != null ||
        !value.equals(address.getAddress())
      ) throw new IllegalArgumentException();
    } catch (Exception e) {
      throw new IllegalArgumentException("Informe um único e-mail válido.");
    }
    return value.toLowerCase(Locale.ROOT);
  }

  public static String address(JsonNode spec, String channel) {
    return channel.equals("EMAIL")
      ? email(spec.path("email").asText())
      : ConsentService.phone(spec.path("phone").asText());
  }

  public static boolean permitted(JsonNode spec, String channel) {
    return channel.equals("EMAIL") || ConsentService.validOptIn(spec);
  }

  public static String header(String value, String field, int max) {
    if (
      value == null ||
      value.isBlank() ||
      value.length() > max ||
      value.indexOf('\r') >= 0 ||
      value.indexOf('\n') >= 0
    ) throw new IllegalArgumentException("Campo inválido: " + field);
    return value;
  }
}
