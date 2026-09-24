package com.mibess.notify.shared;

import com.mibess.notify.audit.AuditLog;
import com.mibess.notify.workspace.WorkspaceAccess;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/admin")
public class QueryController {

  private final JdbcClient db;
  private final WorkspaceAccess access;
  private final Json json;
  private final AuditLog audit;

  public QueryController(
    JdbcClient db,
    WorkspaceAccess access,
    Json json,
    AuditLog audit
  ) {
    this.db = db;
    this.access = access;
    this.json = json;
    this.audit = audit;
  }

  private String table(String kind) {
    return switch (kind) {
      case "events" -> "events";
      case "notifications" -> "notifications";
      case "webhooks" -> "webhook_receipts";
      case "audit" -> "audit_logs";
      default -> throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    };
  }

  @GetMapping("/{kind:events|notifications|webhooks|audit}")
  public Map<String, Object> list(
    @PathVariable String kind,
    @RequestParam(defaultValue = "0") int page,
    @RequestParam(defaultValue = "50") int size,
    @RequestParam(required = false) String status,
    @RequestParam(required = false) UUID applicationId,
    @RequestParam(required = false) UUID channelConnectionId,
    @RequestParam(required = false) Instant from,
    @RequestParam(required = false) Instant to
  ) {
    size = Math.clamp(size, 1, 100);
    page = Math.clamp(page, 0, 100000);
    String table = table(kind);
    String date = kind.equals("webhooks") ? "received_at" : "created_at";
    String where = " WHERE workspace_id=:w";
    Map<String, Object> p = new HashMap<>();
    p.put("w", access.id());
    if (status != null && !status.isBlank() && !kind.equals("audit")) {
      where += " AND status=:status";
      p.put("status", status);
    }
    if (
      applicationId != null && Set.of("events", "notifications").contains(kind)
    ) {
      where += " AND application_id=:app";
      p.put("app", applicationId);
    }
    if (
      channelConnectionId != null &&
      Set.of("notifications", "webhooks").contains(kind)
    ) {
      where += " AND channel_connection_id=:channel";
      p.put("channel", channelConnectionId);
    }
    if (from != null) {
      where += " AND " + date + ">=:from";
      p.put("from", java.sql.Timestamp.from(from));
    }
    if (to != null) {
      where += " AND " + date + "<:to";
      p.put("to", java.sql.Timestamp.from(to));
    }
    long total = db
      .sql("SELECT count(*) FROM " + table + where)
      .params(p)
      .query(Long.class)
      .single();
    var rows = db
      .sql(
        "SELECT * FROM " +
          table +
          where +
          " ORDER BY " +
          date +
          " DESC,id DESC LIMIT " +
          size +
          " OFFSET " +
          page * size
      )
      .params(p)
      .query()
      .listOfRows()
      .stream()
      .map(this::safe)
      .toList();
    return Map.of("items", rows, "total", total, "page", page, "size", size);
  }

  private Map<String, Object> safe(Map<String, Object> row) {
    var result = new LinkedHashMap<>(row);
    result.remove("payload_hash");
    result.remove("idempotency_key");
    result.remove("command");
    if (result.containsKey("payload")) result.put(
      "payload",
      json.read(result.get("payload").toString())
    );
    return result;
  }

  @GetMapping("/notifications/{id}")
  public Map<String, Object> notification(@PathVariable UUID id) {
    var n = db
      .sql("SELECT * FROM notifications WHERE id=:id AND workspace_id=:w")
      .param("id", id)
      .param("w", access.id())
      .query()
      .listOfRows()
      .stream()
      .findFirst()
      .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    return Map.of(
      "notification",
      safe(n),
      "timeline",
      db
        .sql(
          "SELECT * FROM notification_timeline WHERE notification_id=:id ORDER BY id"
        )
        .param("id", id)
        .query()
        .listOfRows(),
      "attempts",
      db
        .sql(
          "SELECT * FROM delivery_attempts WHERE notification_id=:id ORDER BY attempt_number"
        )
        .param("id", id)
        .query()
        .listOfRows()
    );
  }

  @PostMapping("/notifications/{id}/retry")
  @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
  @Transactional
  public void retry(
    @PathVariable UUID id,
    @RequestParam(defaultValue = "false") boolean acknowledgeUnknown
  ) {
    var n = db
      .sql(
        "SELECT status,error_code,template_id,event_id,command FROM notifications WHERE id=:id AND workspace_id=:w FOR UPDATE"
      )
      .param("id", id)
      .param("w", access.id())
      .query()
      .listOfRows()
      .stream()
      .findFirst()
      .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    if (!n.get("status").equals("FAILED")) throw new IllegalArgumentException(
      "Apenas notificações FAILED podem ser reenviadas"
    );
    if (
      "DELIVERY_UNKNOWN".equals(n.get("error_code")) && !acknowledgeUnknown
    ) throw new ResponseStatusException(
      HttpStatus.CONFLICT,
      "Resultado incerto. Confirme a reconciliação no provider antes de reenviar."
    );
    var spec = json.read(
      db
        .sql(
          "SELECT spec::text FROM templates WHERE id=:id AND workspace_id=:w AND enabled"
        )
        .param("id", n.get("template_id"))
        .param("w", access.id())
        .query(String.class)
        .optional()
        .orElseThrow(() -> new IllegalArgumentException("Template desativado"))
    );
    if (
      !spec.path("status").asText().equals("APPROVED")
    ) throw new IllegalArgumentException("Template precisa estar aprovado");
    var payload = json.read(
      db
        .sql(
          "SELECT payload::text FROM events WHERE id=:id AND workspace_id=:w"
        )
        .param("id", n.get("event_id"))
        .param("w", access.id())
        .query(String.class)
        .single()
    );
    var command = (com.fasterxml.jackson.databind.node.ObjectNode) json.read(
      n.get("command").toString()
    );
    command.set(
      "parameters",
      json.tree(
        com.mibess.notify.template.TemplateRenderer.parameters(spec, payload)
      )
    );
    command.put(
      "text",
      com.mibess.notify.template.TemplateRenderer.render(spec, payload)
    );
    command.put("templateName", spec.path("providerTemplateName").asText());
    command.put("language", spec.path("language").asText());
    db.sql(
      "UPDATE notifications SET status='PENDING',available_at=now(),retry_count=0,error_code=NULL,command=CAST(:command AS jsonb) WHERE id=:id"
    )
      .param("command", json.write(command))
      .param("id", id)
      .update();
    db.sql(
      "INSERT INTO notification_timeline(notification_id,status,description) VALUES(:id,'RETRY_REQUESTED','Reenvio solicitado por operador')"
    )
      .param("id", id)
      .update();
    audit.record(access.id(), access.actor(), "NOTIFICATION_RETRY", id);
  }

  @GetMapping("/dashboard")
  public Map<String, Object> dashboard(
    @RequestParam(required = false) UUID applicationId,
    @RequestParam(required = false) UUID channelConnectionId,
    @RequestParam(required = false) Instant from,
    @RequestParam(required = false) Instant to
  ) {
    Instant start =
        from == null
          ? LocalDate.now(ZoneId.of("America/Sao_Paulo"))
              .atStartOfDay(ZoneId.of("America/Sao_Paulo"))
              .toInstant()
          : from,
      end = to == null ? Instant.now().plusSeconds(1) : to;
    Map<String, Object> p = new HashMap<>();
    p.put("w", access.id());
    p.put("from", java.sql.Timestamp.from(start));
    p.put("to", java.sql.Timestamp.from(end));
    String where =
      " WHERE workspace_id=:w AND created_at>=:from AND created_at<:to";
    if (applicationId != null) {
      where += " AND application_id=:app";
      p.put("app", applicationId);
    }
    long events = db
      .sql("SELECT count(*) FROM events" + where)
      .params(p)
      .query(Long.class)
      .single();
    if (channelConnectionId != null) {
      where += " AND channel_connection_id=:channel";
      p.put("channel", channelConnectionId);
    }
    var counts = db
      .sql(
        "SELECT status,count(*) AS count FROM notifications" +
          where +
          " GROUP BY status"
      )
      .params(p)
      .query()
      .listOfRows();
    var result = new LinkedHashMap<String, Object>();
    result.put("events", events);
    long total = 0,
      delivered = 0,
      accepted = 0;
    for (var row : counts) {
      String s = row.get("status").toString();
      long c = ((Number) row.get("count")).longValue();
      result.put(s, c);
      total += c;
      if (Set.of("DELIVERED", "READ").contains(s)) delivered += c;
      if (Set.of("SENT", "DELIVERED", "READ").contains(s)) accepted += c;
    }
    result.put("notifications", total);
    result.put(
      "deliveryRate",
      accepted == 0 ? 0 : Math.round((1000.0 * delivered) / accepted) / 10.0
    );
    result.put(
      "daily",
      db
        .sql(
          "SELECT (created_at AT TIME ZONE 'America/Sao_Paulo')::date AS day,count(*) AS total,count(*) FILTER (WHERE status IN ('DELIVERED','READ')) AS delivered,count(*) FILTER (WHERE status='FAILED') AS failed FROM notifications" +
            where +
            " GROUP BY day ORDER BY day"
        )
        .params(p)
        .query()
        .listOfRows()
    );
    return result;
  }
}
