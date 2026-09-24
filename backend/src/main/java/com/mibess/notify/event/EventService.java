package com.mibess.notify.event;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.mibess.notify.application.ApiKeyService.Application;
import com.mibess.notify.shared.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class EventService {

  public record Accepted(UUID eventId, String status, boolean duplicate) {}

  private final JdbcClient db;
  private final Json json;

  public EventService(JdbcClient db, Json json) {
    this.db = db;
    this.json = json;
  }

  @Transactional
  public Accepted accept(Application app, String key, JsonNode payload) {
    if (
      key == null || key.isBlank() || key.length() > 200
    ) throw new IllegalArgumentException(
      "Idempotency-Key obrigatório, até 200 caracteres"
    );
    String hash = Crypto.hash(json.write(canonical(payload)));
    UUID id = UUID.randomUUID();
    String correlation = payload.path("correlationId").asText(id.toString());
    var inserted = db
      .sql(
        "INSERT INTO events(id,workspace_id,application_id,type,correlation_id,idempotency_key,payload_hash,payload) VALUES(:id,:w,:a,:type,:correlation,:key,:hash,CAST(:payload AS jsonb)) ON CONFLICT(application_id,idempotency_key) DO NOTHING RETURNING id"
      )
      .param("id", id)
      .param("w", app.workspaceId())
      .param("a", app.id())
      .param("type", payload.path("type").asText())
      .param("correlation", correlation)
      .param("key", key)
      .param("hash", hash)
      .param("payload", json.write(payload))
      .query(UUID.class)
      .optional();
    if (inserted.isEmpty()) {
      var existing = db
        .sql(
          "SELECT id,payload_hash FROM events WHERE application_id=:a AND idempotency_key=:key"
        )
        .param("a", app.id())
        .param("key", key)
        .query()
        .singleRow();
      if (
        !hash.equals(existing.get("payload_hash"))
      ) throw new ResponseStatusException(
        HttpStatus.CONFLICT,
        "Idempotency-Key já utilizado com outro conteúdo"
      );
      db.sql("UPDATE events SET duplicate_count=duplicate_count+1 WHERE id=:id")
        .param("id", existing.get("id"))
        .update();
      return new Accepted((UUID) existing.get("id"), "ACCEPTED", true);
    }
    db.sql(
      "INSERT INTO outbox(id,topic,aggregate_id) VALUES(:id,'notify.events',:event)"
    )
      .param("id", UUID.randomUUID())
      .param("event", id)
      .update();
    return new Accepted(id, "ACCEPTED", false);
  }

  private JsonNode canonical(JsonNode node) {
    if (node.isObject()) {
      var object = json.object();
      var keys = new TreeSet<String>();
      node.fieldNames().forEachRemaining(keys::add);
      keys.forEach(k -> object.set(k, canonical(node.get(k))));
      return object;
    }
    if (node.isArray()) {
      var array = json.mapper.createArrayNode();
      node.forEach(n -> array.add(canonical(n)));
      return array;
    }
    return node;
  }
}
