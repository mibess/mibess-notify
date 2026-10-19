package com.mibess.notify.routing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;

/** Exact legacy types and an optional terminal prefix wildcard; never regular expressions. */
public final class EventTypes {

  private EventTypes() {}

  public static List<String> patterns(JsonNode spec) {
    if (
      spec.path("eventTypes").isArray() && !spec.path("eventTypes").isEmpty()
    ) {
      List<String> result = new ArrayList<>();
      spec.path("eventTypes").forEach(value -> result.add(value.asText()));
      return result;
    }
    return List.of(spec.path("eventType").asText());
  }

  public static boolean matches(JsonNode spec, String type) {
    return patterns(spec)
      .stream()
      .anyMatch(pattern ->
        pattern.endsWith("*")
          ? type.startsWith(pattern.substring(0, pattern.length() - 1))
          : pattern.equals(type)
      );
  }

  public static void validateAndNormalize(ObjectNode spec) {
    if (spec.has("eventTypes")) {
      var types = spec.path("eventTypes");
      if (
        !types.isArray() || types.isEmpty() || types.size() > 50
      ) throw new IllegalArgumentException("Informe de 1 a 50 tipos de evento");
      Set<String> distinct = new HashSet<>();
      for (var value : types) {
        if (
          !value.isTextual() ||
          !validPattern(value.asText()) ||
          !distinct.add(value.asText())
        ) throw new IllegalArgumentException(
          "Tipos devem ser únicos, em UPPER_SNAKE_CASE, com curinga apenas no final do prefixo (CRM_*)"
        );
      }
      // Keep one explicit exact type readable by the previous application image.
      String fallback = spec.path("eventType").asText();
      if (fallback.isBlank()) fallback = patterns(spec)
        .stream()
        .filter(t -> !t.endsWith("*"))
        .findFirst()
        .orElse("");
      if (
        !validExact(fallback) || !matches(spec, fallback)
      ) throw new IllegalArgumentException(
        "Informe eventType exato coberto pela lista para compatibilidade de rollback"
      );
      spec.put("eventType", fallback);
    } else if (!validExact(spec.path("eventType").asText())) {
      throw new IllegalArgumentException(
        "Informe eventType em UPPER_SNAKE_CASE"
      );
    }
  }

  public static boolean validExact(String value) {
    return value != null && value.matches("[A-Z][A-Z0-9_]{1,99}");
  }

  private static boolean validPattern(String value) {
    return (
      validExact(value) ||
      (value.length() <= 100 && value.matches("[A-Z][A-Z0-9_]*_\\*"))
    );
  }
}
