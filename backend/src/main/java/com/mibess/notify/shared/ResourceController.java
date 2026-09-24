package com.mibess.notify.shared;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.mibess.notify.audit.AuditLog;
import com.mibess.notify.contact.ConsentService;
import com.mibess.notify.routing.Conditions;
import com.mibess.notify.shared.ResourceStore.*;
import com.mibess.notify.workspace.WorkspaceAccess;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin")
public class ResourceController {

  private final ResourceStore store;
  private final WorkspaceAccess access;
  private final AuditLog audit;
  private final Crypto crypto;
  private final Json json;
  private final boolean fake;
  private final JdbcClient db;

  public ResourceController(
    ResourceStore store,
    WorkspaceAccess access,
    AuditLog audit,
    Crypto crypto,
    Json json,
    JdbcClient db,
    @Value("${notify.fake-provider}") boolean fake
  ) {
    this.store = store;
    this.access = access;
    this.audit = audit;
    this.crypto = crypto;
    this.json = json;
    this.db = db;
    this.fake = fake;
  }

  public record Input(
    @NotBlank @Pattern(regexp = "[A-Za-z][A-Za-z0-9_]{1,79}") String code,
    @NotBlank @Size(max = 150) String name,
    boolean enabled,
    @NotNull JsonNode spec
  ) {}

  @GetMapping("/{kind:applications|contacts|groups|channels|templates|rules}")
  public List<Resource> list(@PathVariable String kind) {
    return store
      .list(Kind.parse(kind), access.id())
      .stream()
      .map(store::publicView)
      .toList();
  }

  @PostMapping("/{kind:applications|contacts|groups|channels|templates|rules}")
  @PreAuthorize("hasRole('ADMIN')")
  @Transactional
  public Resource create(
    @PathVariable String kind,
    @Valid @RequestBody Input input
  ) {
    return save(Kind.parse(kind), UUID.randomUUID(), input, false);
  }

  @PutMapping(
    "/{kind:applications|contacts|groups|channels|templates|rules}/{id}"
  )
  @PreAuthorize("hasRole('ADMIN')")
  @Transactional
  public Resource update(
    @PathVariable String kind,
    @PathVariable UUID id,
    @Valid @RequestBody Input input
  ) {
    return save(Kind.parse(kind), id, input, true);
  }

  private Resource save(Kind kind, UUID id, Input input, boolean existing) {
    UUID w = access.id();
    Resource old = existing ? store.get(kind, w, id) : null;
    if (
      !input.spec().isObject() || json.write(input.spec()).length() > 20000
    ) throw new IllegalArgumentException("Configuração inválida");
    ObjectNode spec = input.spec().deepCopy();
    spec.remove(List.of("credentialsConfigured", "encryptedCredentials"));
    Set<String> allowed = switch (kind) {
      case APPLICATIONS -> Set.of("description");
      case CONTACTS -> Set.of(
        "externalId",
        "phone",
        "email",
        "type",
        "whatsappOptIn",
        "whatsappOptInAt",
        "whatsappOptInSource",
        "whatsappOptOutAt"
      );
      case GROUPS -> Set.of("members");
      case CHANNELS -> Set.of(
        "provider",
        "phoneNumberId",
        "businessAccountId",
        "messagingAccountId",
        "graphApiVersion",
        "coexistence",
        "credentials"
      );
      case TEMPLATES -> Set.of(
        "channelConnectionId",
        "providerTemplateName",
        "providerTemplateId",
        "language",
        "category",
        "status",
        "body",
        "variables"
      );
      case RULES -> Set.of(
        "applicationId",
        "eventType",
        "targetType",
        "targetId",
        "channelConnectionId",
        "templateId",
        "priority",
        "conditions"
      );
    };
    var fieldNames = spec.fieldNames();
    while (fieldNames.hasNext())
      if (
        !allowed.contains(fieldNames.next())
      ) throw new IllegalArgumentException(
        "Configuração contém campo não permitido"
      );
    switch (kind) {
      case APPLICATIONS -> {
      }
      case CONTACTS -> {
        spec.put("phone", ConsentService.phone(spec.path("phone").asText()));
        oneOf(spec, "type", Set.of("INTERNAL", "CUSTOMER", "OTHER"));
        if (
          spec.path("whatsappOptIn").asBoolean() &&
          !ConsentService.validOptIn(spec)
        ) throw new IllegalArgumentException(
          "Opt-in exige data válida, fonte e ausência de opt-out"
        );
      }
      case GROUPS -> {
        if (!spec.path("members").isArray()) throw new IllegalArgumentException(
          "Informe os membros do grupo"
        );
        for (JsonNode member : spec.path("members"))
          reference(Kind.CONTACTS, w, member.asText());
      }
      case CHANNELS -> {
        oneOf(
          spec,
          "provider",
          fake ? Set.of("WHATSAPP_META", "FAKE") : Set.of("WHATSAPP_META")
        );
        if (
          spec.path("provider").asText().equals("WHATSAPP_META") &&
          input.enabled() &&
          !spec.path("phoneNumberId").asText().matches("[0-9]+")
        ) throw new IllegalArgumentException("Phone Number ID obrigatório");
        JsonNode credentials = spec.remove("credentials");
        if (
          credentials != null &&
          credentials.isObject() &&
          !credentials.isEmpty()
        ) {
          for (String key : List.of("accessToken", "appSecret", "verifyToken"))
            if (
              credentials.path(key).asText().isBlank()
            ) throw new IllegalArgumentException(
              "Credencial obrigatória: " + key
            );
          spec.put(
            "encryptedCredentials",
            crypto.encrypt(json.write(credentials), w + ":" + id)
          );
        } else if (
          old != null && old.spec().has("encryptedCredentials")
        ) spec.set(
          "encryptedCredentials",
          old.spec().get("encryptedCredentials")
        );
        if (
          input.enabled() &&
          spec.path("provider").asText().equals("WHATSAPP_META") &&
          !spec.has("encryptedCredentials")
        ) throw new IllegalArgumentException(
          "Configure as credenciais antes de ativar"
        );
        if (
          spec.has("graphApiVersion") &&
          !spec.path("graphApiVersion").asText().matches("v[0-9]{2}\\.0")
        ) throw new IllegalArgumentException("Versão Graph API inválida");
      }
      case TEMPLATES -> {
        reference(Kind.CHANNELS, w, spec.path("channelConnectionId").asText());
        required(spec, "providerTemplateName");
        required(spec, "language");
        oneOf(
          spec,
          "status",
          Set.of("DRAFT", "PENDING", "APPROVED", "REJECTED", "PAUSED")
        );
        oneOf(
          spec,
          "category",
          Set.of("UTILITY", "MARKETING", "AUTHENTICATION")
        );
        if (
          !spec.path("variables").isArray()
        ) throw new IllegalArgumentException(
          "Informe variáveis em ordem de parâmetros Meta"
        );
      }
      case RULES -> {
        reference(Kind.APPLICATIONS, w, spec.path("applicationId").asText());
        required(spec, "eventType");
        var channel = reference(
          Kind.CHANNELS,
          w,
          spec.path("channelConnectionId").asText()
        );
        var template = reference(
          Kind.TEMPLATES,
          w,
          spec.path("templateId").asText()
        );
        if (
          !template
            .spec()
            .path("channelConnectionId")
            .asText()
            .equals(channel.id().toString())
        ) throw new IllegalArgumentException(
          "Template pertence a outra identidade remetente"
        );
        oneOf(
          spec,
          "targetType",
          Set.of("CONTACT", "CONTACT_GROUP", "EVENT_RECIPIENT")
        );
        oneOf(spec, "priority", Set.of("LOW", "NORMAL", "HIGH", "CRITICAL"));
        if (spec.path("targetType").asText().equals("CONTACT")) reference(
          Kind.CONTACTS,
          w,
          spec.path("targetId").asText()
        );
        if (spec.path("targetType").asText().equals("CONTACT_GROUP")) reference(
          Kind.GROUPS,
          w,
          spec.path("targetId").asText()
        );
        if (
          !spec.path("conditions").isArray()
        ) throw new IllegalArgumentException("Condições devem ser uma lista");
        for (JsonNode c : spec.path("conditions")) {
          if (
            !Conditions.OPERATORS.contains(c.path("operator").asText()) ||
            !c
              .path("field")
              .asText()
              .matches("(data|recipient)\\.[A-Za-z0-9_.]+")
          ) throw new IllegalArgumentException("Condição inválida");
        }
      }
    }
    var saved = store.save(
      kind,
      w,
      id,
      input.code(),
      input.name(),
      input.enabled(),
      spec
    );
    audit.record(
      w,
      access.actor(),
      kind.name() + "_" + (existing ? "UPDATED" : "CREATED"),
      id
    );
    return store.publicView(saved);
  }

  private Resource reference(Kind kind, UUID w, String id) {
    try {
      return store.get(kind, w, UUID.fromString(id));
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("Referência inválida: " + kind);
    }
  }

  private static void required(JsonNode n, String field) {
    if (n.path(field).asText().isBlank()) throw new IllegalArgumentException(
      "Campo obrigatório: " + field
    );
  }

  private static void oneOf(JsonNode n, String field, Set<String> values) {
    if (
      !values.contains(n.path(field).asText())
    ) throw new IllegalArgumentException("Valor inválido: " + field);
  }

  @GetMapping("/suppressions")
  public List<Map<String, Object>> suppressions() {
    return db
      .sql(
        "SELECT * FROM suppressions WHERE workspace_id=:w ORDER BY created_at DESC LIMIT 1000"
      )
      .param("w", access.id())
      .query()
      .listOfRows();
  }

  public record Suppression(
    @NotBlank String address,
    @NotBlank @Size(max = 200) String reason
  ) {}

  @PostMapping("/suppressions")
  @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
  @Transactional
  public void suppress(@Valid @RequestBody Suppression input) {
    var id = UUID.randomUUID();
    db.sql(
      "INSERT INTO suppressions(id,workspace_id,address,reason) VALUES(:id,:w,:a,:r) ON CONFLICT DO NOTHING"
    )
      .param("id", id)
      .param("w", access.id())
      .param("a", ConsentService.phone(input.address()))
      .param("r", input.reason())
      .update();
    audit.record(access.id(), access.actor(), "RECIPIENT_SUPPRESSED", id);
  }
}
