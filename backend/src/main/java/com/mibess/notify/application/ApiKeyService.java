package com.mibess.notify.application;

import com.mibess.notify.shared.Crypto;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ApiKeyService {

  public record Application(UUID id, UUID workspaceId) {}

  private final JdbcClient db;

  public ApiKeyService(JdbcClient db) {
    this.db = db;
  }

  public Application authenticate(String key) {
    if (
      key == null || !key.matches("mn_[A-Za-z0-9_-]{43}")
    ) throw unauthorized();
    var app = db
      .sql(
        "UPDATE api_keys k SET last_used_at=now() FROM applications a WHERE k.key_hash=:hash AND k.status='ACTIVE' AND a.id=k.application_id AND a.enabled RETURNING k.application_id,k.workspace_id"
      )
      .param("hash", Crypto.hash(key))
      .query((r, n) ->
        new Application(r.getObject(1, UUID.class), r.getObject(2, UUID.class))
      )
      .optional();
    return app.orElseThrow(ApiKeyService::unauthorized);
  }

  private static ResponseStatusException unauthorized() {
    return new ResponseStatusException(
      HttpStatus.UNAUTHORIZED,
      "API key inválida ou revogada"
    );
  }

  public Map<String, Object> create(UUID workspace, UUID app, boolean rotate) {
    if (rotate) db.sql(
      "UPDATE api_keys SET status='REVOKED',revoked_at=now() WHERE workspace_id=:w AND application_id=:a AND status='ACTIVE'"
    )
      .param("w", workspace)
      .param("a", app)
      .update();
    String key = "mn_" + Crypto.token();
    UUID id = UUID.randomUUID();
    db.sql(
      "INSERT INTO api_keys(id,workspace_id,application_id,key_hash,prefix) VALUES(:id,:w,:a,:hash,:prefix)"
    )
      .param("id", id)
      .param("w", workspace)
      .param("a", app)
      .param("hash", Crypto.hash(key))
      .param("prefix", key.substring(0, 12))
      .update();
    return Map.of("id", id, "key", key, "prefix", key.substring(0, 12));
  }
}
