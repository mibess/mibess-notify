package com.mibess.notify.template;

import com.fasterxml.jackson.databind.JsonNode;
import com.mibess.notify.routing.Conditions;
import java.util.*;
import java.util.regex.*;

public final class TemplateRenderer {

  private static final Pattern PLACEHOLDER = Pattern.compile(
    "\\{\\{\\s*([a-zA-Z][a-zA-Z0-9_.]*)\\s*}}"
  );

  private TemplateRenderer() {}

  public static List<String> variables(String body) {
    var names = new LinkedHashSet<String>();
    var m = PLACEHOLDER.matcher(body);
    while (m.find()) names.add(m.group(1));
    return List.copyOf(names);
  }

  public static List<String> parameters(JsonNode spec, JsonNode payload) {
    List<String> result = new ArrayList<>();
    for (JsonNode variable : spec.path("variables")) {
      String path = variable.asText();
      var value = Conditions.field(
        payload,
        path.contains(".") ? path : "data." + path
      );
      if (
        value.isMissingNode() ||
        value.isNull() ||
        !value.isValueNode() ||
        value.asText().isBlank()
      ) throw new IllegalArgumentException("Variável ausente: " + path);
      result.add(value.asText());
    }
    for (String variable : variables(spec.path("body").asText())) {
      boolean found = false;
      for (JsonNode declared : spec.path("variables"))
        if (declared.asText().equals(variable)) found = true;
      if (!found) throw new IllegalArgumentException(
        "Variável não declarada: " + variable
      );
    }
    return result;
  }
}
