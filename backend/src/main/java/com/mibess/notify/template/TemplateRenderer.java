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

  public static String render(JsonNode spec, JsonNode payload) {
    List<String> values = parameters(spec, payload);
    Map<String, String> replacements = new HashMap<>();
    int index = 0;
    for (JsonNode variable : spec.path("variables"))
      replacements.put(variable.asText(), values.get(index++));
    return renderValues(spec.path("body").asText(), replacements, false);
  }

  public static String renderField(
    JsonNode spec,
    JsonNode payload,
    String field,
    boolean html
  ) {
    List<String> values = parameters(spec, payload);
    Map<String, String> replacements = new HashMap<>();
    int index = 0;
    for (JsonNode variable : spec.path("variables"))
      replacements.put(variable.asText(), values.get(index++));
    return renderValues(spec.path(field).asText(), replacements, html);
  }

  public static String renderValues(
    String body,
    Map<String, String> values,
    boolean html
  ) {
    return PLACEHOLDER.matcher(body).replaceAll(match -> {
      String value = values.get(match.group(1));
      if (value == null) throw new IllegalArgumentException(
        "Variável não declarada: " + match.group(1)
      );
      if (html) value = org.springframework.web.util.HtmlUtils.htmlEscape(
        value
      );
      return Matcher.quoteReplacement(value);
    });
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
    for (String field : List.of("body", "subject", "html"))
      for (String variable : variables(spec.path(field).asText())) {
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
