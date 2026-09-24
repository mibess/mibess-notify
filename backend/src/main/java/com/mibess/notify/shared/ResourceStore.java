package com.mibess.notify.shared;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Fixed table allowlist: no identifier ever comes directly from a request. */
@Service
public class ResourceStore {

  public enum Kind {
    APPLICATIONS("applications"),
    CONTACTS("contacts"),
    GROUPS("contact_groups"),
    CHANNELS("channel_connections"),
    TEMPLATES("templates"),
    RULES("routing_rules");

    public final String table;

    Kind(String table) {
      this.table = table;
    }

    public static Kind parse(String path) {
      try {
        return valueOf(path.toUpperCase(Locale.ROOT));
      } catch (Exception e) {
        throw new ResponseStatusException(HttpStatus.NOT_FOUND);
      }
    }
  }

  public record Resource(
    UUID id,
    UUID workspaceId,
    String code,
    String name,
    boolean enabled,
    JsonNode spec,
    String createdAt,
    String updatedAt
  ) {}

  private final JdbcClient db;
  private final Json json;

  public ResourceStore(JdbcClient db, Json json) {
    this.db = db;
    this.json = json;
  }

  private Resource row(java.sql.ResultSet r, int n)
    throws java.sql.SQLException {
    return new Resource(
      r.getObject("id", UUID.class),
      r.getObject("workspace_id", UUID.class),
      r.getString("code"),
      r.getString("name"),
      r.getBoolean("enabled"),
      json.read(r.getString("spec")),
      r.getString("created_at"),
      r.getString("updated_at")
    );
  }

  public List<Resource> list(Kind kind, UUID workspace) {
    return db
      .sql(
        "SELECT * FROM " +
          kind.table +
          " WHERE workspace_id=:w ORDER BY created_at,id LIMIT 1000"
      )
      .param("w", workspace)
      .query(this::row)
      .list();
  }

  public Resource get(Kind kind, UUID workspace, UUID id) {
    return db
      .sql("SELECT * FROM " + kind.table + " WHERE workspace_id=:w AND id=:id")
      .param("w", workspace)
      .param("id", id)
      .query(this::row)
      .optional()
      .orElseThrow(() ->
        new ResponseStatusException(
          HttpStatus.NOT_FOUND,
          "Recurso não encontrado"
        )
      );
  }

  public Optional<Resource> contactByPhone(UUID workspace, String phone) {
    return db
      .sql(
        "SELECT * FROM contacts WHERE workspace_id=:w AND spec->>'phone'=:phone ORDER BY created_at LIMIT 1"
      )
      .param("w", workspace)
      .param("phone", phone)
      .query(this::row)
      .optional();
  }

  public Resource save(
    Kind kind,
    UUID workspace,
    UUID id,
    String code,
    String name,
    boolean enabled,
    JsonNode spec
  ) {
    db.sql(
      "INSERT INTO " +
        kind.table +
        "(id,workspace_id,code,name,enabled,spec) VALUES(:id,:w,:code,:name,:enabled,CAST(:spec AS jsonb)) ON CONFLICT(id) DO UPDATE SET code=EXCLUDED.code,name=EXCLUDED.name,enabled=EXCLUDED.enabled,spec=EXCLUDED.spec,updated_at=now() WHERE " +
        kind.table +
        ".workspace_id=EXCLUDED.workspace_id"
    )
      .param("id", id)
      .param("w", workspace)
      .param("code", code)
      .param("name", name)
      .param("enabled", enabled)
      .param("spec", json.write(spec))
      .update();
    return get(kind, workspace, id);
  }

  public Resource publicView(Resource resource) {
    ObjectNode spec = (ObjectNode) resource.spec().deepCopy();
    boolean configured = spec.hasNonNull("encryptedCredentials");
    spec.remove("encryptedCredentials");
    spec.put("credentialsConfigured", configured);
    return new Resource(
      resource.id(),
      resource.workspaceId(),
      resource.code(),
      resource.name(),
      resource.enabled(),
      spec,
      resource.createdAt(),
      resource.updatedAt()
    );
  }
}
