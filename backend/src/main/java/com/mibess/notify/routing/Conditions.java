package com.mibess.notify.routing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import java.math.BigDecimal;
import java.util.*;

public final class Conditions {

  public static final Set<String> OPERATORS = Set.of(
    "EQUALS",
    "NOT_EQUALS",
    "EXISTS",
    "NOT_EXISTS",
    "GREATER_THAN",
    "LESS_THAN",
    "CONTAINS",
    "IN"
  );

  private Conditions() {}

  public static JsonNode field(JsonNode payload, String path) {
    if (
      path == null ||
      !path.matches("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+)*") ||
      path.length() > 150
    ) return MissingNode.getInstance();
    JsonNode value = payload;
    for (String part : path.split("\\.")) value = value.path(part);
    return value;
  }

  public static boolean matches(JsonNode payload, JsonNode conditions) {
    if (conditions == null || conditions.isMissingNode()) return true;
    if (
      !conditions.isArray() || conditions.size() > 30
    ) throw new IllegalArgumentException(
      "Condições devem ser uma lista de até 30 itens"
    );
    for (JsonNode c : conditions) {
      JsonNode actual = field(payload, c.path("field").asText()),
        expected = c.path("value");
      String op = c.path("operator").asText();
      boolean exists = !actual.isMissingNode() && !actual.isNull();
      boolean match = switch (op) {
        case "EXISTS" -> exists;
        case "NOT_EXISTS" -> !exists;
        case "EQUALS" -> exists && equal(actual, expected);
        case "NOT_EQUALS" -> exists && !equal(actual, expected);
        case "GREATER_THAN" -> actual.isNumber() &&
          expected.isNumber() &&
          actual.decimalValue().compareTo(expected.decimalValue()) > 0;
        case "LESS_THAN" -> actual.isNumber() &&
          expected.isNumber() &&
          actual.decimalValue().compareTo(expected.decimalValue()) < 0;
        case "CONTAINS" -> actual.isTextual() && expected.isTextual()
          ? actual.asText().contains(expected.asText())
          : actual.isArray() && contains(actual, expected);
        case "IN" -> exists && expected.isArray() && contains(expected, actual);
        default -> throw new IllegalArgumentException(
          "Operador de condição inválido"
        );
      };
      if (!match) return false;
    }
    return true;
  }

  private static boolean contains(JsonNode list, JsonNode value) {
    for (JsonNode n : list) if (equal(n, value)) return true;
    return false;
  }

  private static boolean equal(JsonNode a, JsonNode b) {
    return a.isNumber() && b.isNumber()
      ? a.decimalValue().compareTo(b.decimalValue()) == 0
      : a.equals(b);
  }
}
