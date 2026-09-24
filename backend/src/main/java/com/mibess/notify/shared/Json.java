package com.mibess.notify.shared;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.springframework.stereotype.Component;

@Component
public class Json {

  public final ObjectMapper mapper;

  public Json(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  public JsonNode read(String value) {
    try {
      return mapper.readTree(value);
    } catch (Exception e) {
      throw new IllegalArgumentException("JSON inválido");
    }
  }

  public String write(Object value) {
    try {
      return mapper.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalArgumentException("JSON inválido");
    }
  }

  public ObjectNode object() {
    return mapper.createObjectNode();
  }

  public JsonNode tree(Object value) {
    return mapper.valueToTree(value);
  }
}
