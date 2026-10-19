package com.mibess.notify.routing;

import com.fasterxml.jackson.databind.JsonNode;
import com.mibess.notify.audit.AuditLog;
import com.mibess.notify.channel.ProviderRegistry;
import com.mibess.notify.contact.ConsentService;
import com.mibess.notify.shared.*;
import com.mibess.notify.shared.ResourceStore.*;
import com.mibess.notify.template.TemplateRenderer;
import com.mibess.notify.workspace.WorkspaceAccess;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Workspace-scoped integration diagnostics. Simulation never creates a delivery. */
@RestController
@RequestMapping("/api/v1/admin")
public class IntegrationController {

  private final JdbcClient db;
  private final Json json;
  private final ResourceStore store;
  private final WorkspaceAccess access;
  private final AuditLog audit;
  private final RoutingEngine routing;
  private final ConsentService consent;
  private final ProviderRegistry providers;

  public IntegrationController(
    JdbcClient db,
    Json json,
    ResourceStore store,
    WorkspaceAccess access,
    AuditLog audit,
    RoutingEngine routing,
    ConsentService consent,
    ProviderRegistry providers
  ) {
    this.db = db;
    this.json = json;
    this.store = store;
    this.access = access;
    this.audit = audit;
    this.routing = routing;
    this.consent = consent;
    this.providers = providers;
  }

  private Map<String, Object> eventRow(UUID id, boolean lock) {
    return db
      .sql(
        "SELECT * FROM events WHERE id=:id AND workspace_id=:w" +
          (lock ? " FOR UPDATE" : "")
      )
      .param("id", id)
      .param("w", access.id())
      .query()
      .listOfRows()
      .stream()
      .findFirst()
      .orElseThrow(() ->
        new ResponseStatusException(
          HttpStatus.NOT_FOUND,
          "Evento não encontrado"
        )
      );
  }

  @GetMapping("/events/{id}")
  public Map<String, Object> event(@PathVariable UUID id) {
    var row = new LinkedHashMap<>(eventRow(id, false));
    row.remove("idempotency_key");
    row.remove("payload_hash");
    row.put("payload", json.read(row.get("payload").toString()));
    row.put(
      "routing_diagnostics",
      json.read(row.get("routing_diagnostics").toString())
    );
    var timeline = db
      .sql("SELECT * FROM event_timeline WHERE event_id=:id ORDER BY id")
      .param("id", id)
      .query()
      .listOfRows()
      .stream()
      .map(entry -> {
        var item = new LinkedHashMap<>(entry);
        item.put("details", json.read(item.get("details").toString()));
        return item;
      })
      .toList();
    var notifications = db
      .sql(
        "SELECT id,status,error_code,recipient_name,recipient_address,created_at FROM notifications WHERE event_id=:id AND workspace_id=:w ORDER BY created_at,id"
      )
      .param("id", id)
      .param("w", access.id())
      .query()
      .listOfRows();
    return Map.of(
      "event",
      row,
      "timeline",
      timeline,
      "notifications",
      notifications
    );
  }

  @PostMapping("/events/{id}/reprocess")
  @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
  @Transactional
  public Map<String, Object> reprocess(@PathVariable UUID id) {
    var row = eventRow(id, true);
    if (
      !Set.of("NO_MATCH", "SKIPPED").contains(row.get("status"))
    ) throw new ResponseStatusException(
      HttpStatus.CONFLICT,
      "Somente eventos NO_MATCH ou SKIPPED podem ser reprocessados"
    );
    db.sql(
      "INSERT INTO event_timeline(event_id,status,description,actor,details) VALUES(:id,'REPROCESS_REQUESTED','Reprocessamento solicitado com a configuração atual',:actor,CAST(:details AS jsonb))"
    )
      .param("id", id)
      .param("actor", access.actor())
      .param(
        "details",
        json.write(
          Map.of(
            "previousStatus",
            row.get("status"),
            "previousDiagnostics",
            json.read(row.get("routing_diagnostics").toString())
          )
        )
      )
      .update();
    db.sql("UPDATE events SET status='ACCEPTED',error_code=NULL WHERE id=:id")
      .param("id", id)
      .update();
    routing.route(id.toString());
    audit.record(access.id(), access.actor(), "EVENT_REPROCESSED", id);
    return event(id);
  }

  @GetMapping("/applications/{id}/event-catalog")
  public List<Map<String, Object>> catalog(@PathVariable UUID id) {
    store.get(Kind.APPLICATIONS, access.id(), id);
    return catalogFor(access.id(), id);
  }

  private List<Map<String, Object>> catalogFor(UUID workspace, UUID app) {
    return db
      .sql(
        "SELECT DISTINCT ON(type) id,type,created_at,payload FROM events WHERE workspace_id=:w AND application_id=:a ORDER BY type,created_at DESC,id DESC"
      )
      .param("w", workspace)
      .param("a", app)
      .query()
      .listOfRows()
      .stream()
      .map(row -> {
        var data = json.read(row.get("payload").toString()).path("data");
        var fields = new TreeSet<String>();
        dataFields(data, "", fields);
        return Map.<String, Object>of(
          "eventType",
          row.get("type"),
          "lastReceivedAt",
          row.get("created_at"),
          "eventId",
          row.get("id"),
          "dataFields",
          fields,
          "sampleData",
          data
        );
      })
      .toList();
  }

  private void dataFields(JsonNode data, String prefix, Set<String> result) {
    data.fields().forEachRemaining(entry -> {
      String name = prefix + entry.getKey();
      result.add(name);
      if (entry.getValue().isObject()) dataFields(
        entry.getValue(),
        name + ".",
        result
      );
    });
  }

  public record TestInput(
    String eventType,
    JsonNode data,
    JsonNode recipient
  ) {}

  public record Problem(
    String code,
    String message,
    String resourceKind,
    UUID resourceId
  ) {}

  public record TestRecipient(
    UUID contactId,
    String name,
    String address,
    List<String> problems
  ) {}

  @PostMapping("/rules/{id}/test")
  @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
  public Map<String, Object> test(
    @PathVariable UUID id,
    @RequestBody(required = false) TestInput input
  ) {
    UUID workspace = access.id();
    var rule = store.get(Kind.RULES, workspace, id);
    var spec = rule.spec();
    UUID application = UUID.fromString(spec.path("applicationId").asText());
    String type = input == null ? null : input.eventType();
    if (
      type != null &&
      (!EventTypes.validExact(type) || !EventTypes.matches(spec, type))
    ) throw new IllegalArgumentException(
      "Escolha um tipo de evento coberto pela regra"
    );
    var latest = latestEvent(workspace, application, spec, type);
    JsonNode payload = latest.isEmpty()
      ? json.object()
      : json.read(latest.get().get("payload").toString());
    if (type == null) type = latest.isEmpty()
      ? spec.path("eventType").asText()
      : latest.get().get("type").toString();
    var example = json.object().put("type", type);
    JsonNode data =
      input != null && input.data() != null
        ? input.data()
        : payload.path("data");
    if (data.isMissingNode()) data = json.object();
    if (
      !data.isObject() || json.write(data).length() > 65536
    ) throw new IllegalArgumentException("data deve ser objeto de até 64 KB");
    JsonNode recipient =
      input != null && input.recipient() != null
        ? input.recipient()
        : payload.path("recipient");
    if (
      !recipient.isMissingNode() &&
      !recipient.isNull() &&
      (!recipient.isObject() || json.write(recipient).length() > 65536)
    ) throw new IllegalArgumentException(
      "recipient deve ser objeto de até 64 KB"
    );
    example.set("data", data);
    if (!recipient.isMissingNode()) example.set("recipient", recipient);
    var channel = store.get(
      Kind.CHANNELS,
      workspace,
      UUID.fromString(spec.path("channelConnectionId").asText())
    );
    var template = store.get(
      Kind.TEMPLATES,
      workspace,
      UUID.fromString(spec.path("templateId").asText())
    );
    List<Problem> problems = new ArrayList<>();
    if (
      !store.get(Kind.APPLICATIONS, workspace, application).enabled()
    ) problems.add(
      new Problem(
        "APPLICATION_DISABLED",
        "Aplicação desativada",
        "applications",
        application
      )
    );
    if (!rule.enabled()) problems.add(
      new Problem("RULE_DISABLED", "Regra desativada", "rules", id)
    );
    boolean matches = Conditions.matches(example, spec.path("conditions"));
    if (!matches) problems.add(
      new Problem(
        "CONDITIONS_NOT_MET",
        "O exemplo não atende às condições",
        "rules",
        id
      )
    );
    if (!channel.enabled()) problems.add(
      new Problem(
        "CHANNEL_DISABLED",
        "Canal desativado",
        "channels",
        channel.id()
      )
    );
    else if (!configurationValid(channel)) problems.add(
      new Problem(
        "CHANNEL_NOT_CONFIGURED",
        "Canal sem configuração válida",
        "channels",
        channel.id()
      )
    );
    if (!template.enabled()) problems.add(
      new Problem(
        "TEMPLATE_DISABLED",
        "Template desativado",
        "templates",
        template.id()
      )
    );
    if (
      !template.spec().path("status").asText().equals("APPROVED")
    ) problems.add(
      new Problem(
        "TEMPLATE_NOT_APPROVED",
        "Template não aprovado",
        "templates",
        template.id()
      )
    );
    var resolved = routing.resolve(workspace, spec, example);
    if (resolved.isEmpty()) problems.add(
      new Problem(
        "NO_ACTIVE_RECIPIENTS",
        "Nenhum destinatário ativo; verifique grupo, contato ou recipient.phone do exemplo",
        targetKind(spec),
        targetId(spec)
      )
    );
    List<TestRecipient> recipients = new ArrayList<>();
    for (var r : resolved) {
      List<String> errors = new ArrayList<>();
      if (!ConsentService.validOptIn(r.consent())) errors.add(
        "CONSENT_REQUIRED"
      );
      if (consent.suppressed(workspace, r.address())) errors.add(
        "RECIPIENT_SUPPRESSED"
      );
      recipients.add(
        new TestRecipient(r.contactId(), r.name(), r.address(), errors)
      );
      for (String error : errors)
        problems.add(
          new Problem(
            error,
            error.equals("CONSENT_REQUIRED")
              ? "Destinatário sem consentimento válido"
              : "Destinatário bloqueado para envios",
            "contacts",
            r.contactId()
          )
        );
    }
    String message = "";
    Map<String, String> variables = new LinkedHashMap<>();
    try {
      var values = TemplateRenderer.parameters(template.spec(), example);
      int index = 0;
      for (var variable : template.spec().path("variables"))
        variables.put(variable.asText(), values.get(index++));
      message = TemplateRenderer.render(template.spec(), example);
      if (message.isBlank() || message.length() > 4096) problems.add(
        new Problem(
          "INVALID_MESSAGE",
          "Mensagem deve ter entre 1 e 4096 caracteres",
          "templates",
          template.id()
        )
      );
    } catch (IllegalArgumentException e) {
      problems.add(
        new Problem(
          "MISSING_TEMPLATE_VARIABLE",
          e.getMessage(),
          "templates",
          template.id()
        )
      );
    }
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("ruleId", id);
    result.put("eventId", latest.map(row -> row.get("id")).orElse(null));
    result.put("eventType", type);
    result.put("data", data);
    result.put("recipient", example.path("recipient"));
    result.put("recipients", recipients);
    result.put("renderedMessage", message);
    result.put("problems", problems);
    result.put("channelConnectionId", channel.id());
    result.put("templateId", template.id());
    result.put("variables", variables);
    result.put(
      "matched",
      matches &&
        rule.enabled() &&
        store.get(Kind.APPLICATIONS, workspace, application).enabled() &&
        !resolved.isEmpty()
    );
    return result;
  }

  private Optional<Map<String, Object>> latestEvent(
    UUID workspace,
    UUID app,
    JsonNode spec,
    String type
  ) {
    // One latest event per type avoids an arbitrary historical scan cap.
    return db
      .sql(
        "SELECT DISTINCT ON(type) id,type,created_at,payload FROM events WHERE workspace_id=:w AND application_id=:a ORDER BY type,created_at DESC,id DESC"
      )
      .param("w", workspace)
      .param("a", app)
      .query()
      .listOfRows()
      .stream()
      .filter(row ->
        type == null
          ? EventTypes.matches(spec, row.get("type").toString())
          : type.equals(row.get("type"))
      )
      .max(
        Comparator.comparing(row ->
          ((java.sql.Timestamp) row.get("created_at")).toInstant()
        )
      );
  }

  private boolean configurationValid(Resource channel) {
    try {
      return providers
        .get(channel.spec().path("provider").asText())
        .validateConfiguration(channel);
    } catch (RuntimeException e) {
      return false;
    }
  }

  private String targetKind(JsonNode spec) {
    return spec.path("targetType").asText().equals("CONTACT_GROUP")
      ? "groups"
      : "contacts";
  }

  private UUID targetId(JsonNode spec) {
    return spec.path("targetType").asText().equals("EVENT_RECIPIENT")
      ? null
      : UUID.fromString(spec.path("targetId").asText());
  }

  private Map<String, Object> check(
    String code,
    String status,
    String message,
    String kind,
    UUID id
  ) {
    Map<String, Object> item = new LinkedHashMap<>();
    item.put("code", code);
    item.put("status", status);
    item.put("message", message);
    item.put("resourceKind", kind);
    item.put("resourceId", id);
    return item;
  }

  @GetMapping("/applications/{id}/health")
  public Map<String, Object> health(@PathVariable UUID id) {
    UUID workspace = access.id();
    var application = store.get(Kind.APPLICATIONS, workspace, id);
    List<Map<String, Object>> checks = new ArrayList<>();
    checks.add(
      check(
        "APPLICATION_ENABLED",
        application.enabled() ? "OK" : "ERROR",
        application.enabled() ? "Aplicação ativa" : "Aplicação desativada",
        "applications",
        id
      )
    );
    long activeKeys = db
      .sql(
        "SELECT count(*) FROM api_keys WHERE workspace_id=:w AND application_id=:a AND status='ACTIVE'"
      )
      .param("w", workspace)
      .param("a", id)
      .query(Long.class)
      .single();
    checks.add(
      check(
        "ACTIVE_API_KEY",
        activeKeys > 0 ? "OK" : "ERROR",
        activeKeys > 0 ? "Chave de API ativa" : "Crie uma chave de API",
        "applications",
        id
      )
    );
    var catalog = catalogFor(workspace, id);
    Object lastEventAt = catalog
      .stream()
      .map(row -> (java.sql.Timestamp) row.get("lastReceivedAt"))
      .max(Comparator.naturalOrder())
      .orElse(null);
    checks.add(
      check(
        "LAST_EVENT",
        lastEventAt == null ? "WARNING" : "OK",
        lastEventAt == null
          ? "Nenhum evento recebido; publique um teste"
          : "Último evento recebido: " + lastEventAt,
        "events",
        null
      )
    );
    var rules = store.rulesForApplication(workspace, id);
    for (var entry : catalog) {
      String type = entry.get("eventType").toString();
      if (
        rules
          .stream()
          .noneMatch(
            rule -> rule.enabled() && EventTypes.matches(rule.spec(), type)
          )
      ) {
        var item = check(
          "EVENT_TYPE_WITHOUT_RULE",
          "ERROR",
          "Sem regra ativa para " + type,
          "rules",
          null
        );
        item.put("eventType", type);
        checks.add(item);
      }
    }
    if (
      catalog.isEmpty() ||
      checks
        .stream()
        .noneMatch(item -> item.get("code").equals("EVENT_TYPE_WITHOUT_RULE"))
    ) checks.add(
      check(
        "EVENT_TYPES_COVERED",
        catalog.isEmpty() ? "UNKNOWN" : "OK",
        catalog.isEmpty()
          ? "Aguardando tipos recebidos para verificar regras"
          : "Tipos recebidos com regra ativa",
        "rules",
        null
      )
    );
    Set<UUID> groups = new HashSet<>(),
      contacts = new HashSet<>(),
      templates = new HashSet<>(),
      channels = new HashSet<>();
    for (var rule : rules) {
      if (!rule.enabled()) continue;
      var spec = rule.spec();
      if (spec.path("targetType").asText().equals("CONTACT_GROUP")) groups.add(
        targetId(spec)
      );
      if (spec.path("targetType").asText().equals("CONTACT")) contacts.add(
        targetId(spec)
      );
      templates.add(UUID.fromString(spec.path("templateId").asText()));
      channels.add(UUID.fromString(spec.path("channelConnectionId").asText()));
    }
    for (UUID groupId : groups) {
      var group = store.get(Kind.GROUPS, workspace, groupId);
      int active = 0;
      for (var member : group.spec().path("members")) {
        UUID contactId = UUID.fromString(member.asText());
        contacts.add(contactId);
        if (store.get(Kind.CONTACTS, workspace, contactId).enabled()) active++;
      }
      boolean ready = group.enabled() && active > 0;
      checks.add(
        check(
          "ACTIVE_GROUP_MEMBERS",
          ready ? "OK" : "ERROR",
          ready
            ? group.name() + ": destinatários ativos"
            : group.name() + ": grupo desativado ou sem membros ativos",
          "groups",
          groupId
        )
      );
    }
    for (UUID contactId : contacts) {
      var contact = store.get(Kind.CONTACTS, workspace, contactId);
      boolean ready =
        contact.enabled() &&
        ConsentService.validOptIn(contact.spec()) &&
        !consent.suppressed(workspace, contact.spec().path("phone").asText());
      if (!ready) checks.add(
        check(
          "CONTACT_NOT_READY",
          "ERROR",
          contact.name() + ": verifique ativação, consentimento e bloqueios",
          "contacts",
          contactId
        )
      );
    }
    for (UUID templateId : templates) {
      var template = store.get(Kind.TEMPLATES, workspace, templateId);
      boolean ready =
        template.enabled() &&
        template.spec().path("status").asText().equals("APPROVED");
      checks.add(
        check(
          "TEMPLATE_APPROVED",
          ready ? "OK" : "ERROR",
          template.name() +
            (ready ? ": aprovado" : ": desativado ou não aprovado"),
          "templates",
          templateId
        )
      );
    }
    for (UUID channelId : channels) {
      var channel = store.get(Kind.CHANNELS, workspace, channelId);
      String state = "NOT_CONFIGURED";
      if (channel.enabled() && configurationValid(channel)) {
        try {
          state = providers
            .get(channel.spec().path("provider").asText())
            .healthCheck(channel);
        } catch (RuntimeException e) {
          state = "UNKNOWN";
        }
      }
      String status =
        !channel.enabled() ||
        Set.of("NOT_CONFIGURED", "DISCONNECTED").contains(state)
          ? "ERROR"
          : state.equals("FAKE_READY")
            ? "OK"
            : "UNKNOWN";
      String detail = !channel.enabled()
        ? "canal desativado"
        : switch (state) {
            case "NOT_CONFIGURED" -> "configuração incompleta";
            case "DISCONNECTED" -> "provider informa conexão fechada";
            case "OPEN_UNVERIFIED" -> "provider informa conexão aberta; faça um teste para confirmar a entrega";
            case "FAKE_READY" -> "provider de homologação pronto";
            default -> "conexão não verificada; confira o provider e envie um teste";
          };
      checks.add(
        check(
          "CHANNEL_CONNECTION",
          status,
          channel.name() + ": " + detail,
          "channels",
          channelId
        )
      );
    }
    long noMatch = db
      .sql(
        "SELECT count(*) FROM events WHERE workspace_id=:w AND application_id=:a AND status='NO_MATCH' AND created_at>=:since"
      )
      .param("w", workspace)
      .param("a", id)
      .param(
        "since",
        java.sql.Timestamp.from(Instant.now().minusSeconds(86400))
      )
      .query(Long.class)
      .single();
    var recent = check(
      "NO_MATCH_24H",
      noMatch > 0 ? "ERROR" : "OK",
      noMatch + " evento(s) sem correspondência nas últimas 24 h",
      "events",
      null
    );
    recent.put("count", noMatch);
    checks.add(recent);
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("applicationId", id);
    result.put("checks", checks);
    result.put("noMatch24h", noMatch);
    result.put("lastEventAt", lastEventAt);
    return result;
  }
}
