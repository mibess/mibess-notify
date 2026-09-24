package com.mibess.notify.notification;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mibess.notify.audit.AuditLog;
import com.mibess.notify.channel.ProviderRegistry;
import com.mibess.notify.contact.ConsentService;
import com.mibess.notify.shared.*;
import com.mibess.notify.shared.ResourceStore.*;
import com.mibess.notify.workspace.WorkspaceAccess;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/admin/notifications/manual")
@PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
public class ManualNotificationController {

  public record Input(
    @NotNull UUID requestId,
    @NotNull UUID contactId,
    @NotNull UUID channelConnectionId,
    UUID templateId,
    @Size(max = 4096) String text,
    @Size(max = 50)
    Map<@Size(max = 150) String, @NotBlank @Size(max = 4096) String> variables,
    @Size(max = 64) String previewHash
  ) {}

  public record Preview(
    String previewHash,
    String recipientName,
    String recipientAddress,
    String channelName,
    String provider,
    String text,
    String templateName
  ) {}

  public record Accepted(
    UUID notificationId,
    String status,
    boolean duplicate
  ) {}

  private record Prepared(
    Resource contact,
    Resource channel,
    ObjectNode command,
    Preview preview
  ) {}

  private static final Pattern VARIABLE = Pattern.compile(
    "\\{\\{\\s*([a-zA-Z][a-zA-Z0-9_.]*)\\s*}}"
  );
  private final JdbcClient db;
  private final ResourceStore store;
  private final WorkspaceAccess access;
  private final ConsentService consent;
  private final ProviderRegistry providers;
  private final Json json;
  private final AuditLog audit;

  public ManualNotificationController(
    JdbcClient db,
    ResourceStore store,
    WorkspaceAccess access,
    ConsentService consent,
    ProviderRegistry providers,
    Json json,
    AuditLog audit
  ) {
    this.db = db;
    this.store = store;
    this.access = access;
    this.consent = consent;
    this.providers = providers;
    this.json = json;
    this.audit = audit;
  }

  @PostMapping("/preview")
  public Preview preview(@Valid @RequestBody Input input) {
    return prepare(access.id(), input).preview();
  }

  @PostMapping
  @Transactional
  public ResponseEntity<Accepted> send(@Valid @RequestBody Input input) {
    UUID workspace = access.id();
    String hash = requestHash(input);
    var existing = find(workspace, input.requestId());
    if (!existing.isEmpty()) return ResponseEntity.ok(
      duplicate(existing.getFirst(), hash)
    );
    var prepared = prepare(workspace, input);
    if (
      !prepared.preview().previewHash().equals(input.previewHash())
    ) throw new ResponseStatusException(
      HttpStatus.CONFLICT,
      "A mensagem ou o cadastro mudou. Revise a prévia antes de enviar."
    );
    UUID id = UUID.randomUUID();
    int count = db
      .sql(
        """
        INSERT INTO notifications(id,workspace_id,channel_connection_id,template_id,contact_id,
          channel,recipient_type,recipient_name,recipient_address,priority,status,command,
          source,manual_request_id,manual_request_hash,created_by)
        VALUES(:id,:w,:channel,:template,:contact,'WHATSAPP','CONTACT',:name,:phone,'NORMAL','PENDING',CAST(:cmd AS jsonb),
          'MANUAL',:request,:hash,:actor)
        ON CONFLICT(workspace_id,manual_request_id) WHERE manual_request_id IS NOT NULL DO NOTHING
        """
      )
      .param("id", id)
      .param("w", workspace)
      .param("channel", input.channelConnectionId())
      .param("template", input.templateId())
      .param("contact", input.contactId())
      .param("name", prepared.contact().name())
      .param("phone", prepared.preview().recipientAddress())
      .param("cmd", json.write(prepared.command()))
      .param("request", input.requestId())
      .param("hash", hash)
      .param("actor", access.actor())
      .update();
    if (count == 0) return ResponseEntity.ok(
      duplicate(find(workspace, input.requestId()).getFirst(), hash)
    );
    db.sql(
      "INSERT INTO notification_timeline(notification_id,status,description) VALUES(:id,'MANUAL_CREATED','Envio manual confirmado no painel'),(:id,'PENDING','Aguardando processamento na fila')"
    )
      .param("id", id)
      .update();
    audit.record(workspace, access.actor(), "MANUAL_NOTIFICATION_CREATED", id);
    return ResponseEntity.status(HttpStatus.ACCEPTED).body(
      new Accepted(id, "PENDING", false)
    );
  }

  private List<Map<String, Object>> find(UUID workspace, UUID request) {
    return db
      .sql(
        "SELECT id,status,manual_request_hash FROM notifications WHERE workspace_id=:w AND manual_request_id=:request"
      )
      .param("w", workspace)
      .param("request", request)
      .query()
      .listOfRows();
  }

  private Accepted duplicate(Map<String, Object> row, String hash) {
    if (
      !hash.equals(row.get("manual_request_hash"))
    ) throw new ResponseStatusException(
      HttpStatus.CONFLICT,
      "Este envio já foi registrado com outro conteúdo. Inicie uma nova mensagem."
    );
    return new Accepted(
      (UUID) row.get("id"),
      row.get("status").toString(),
      true
    );
  }

  private String requestHash(Input input) {
    var body = json.object();
    body.put("contactId", input.contactId().toString());
    body.put("channelId", input.channelConnectionId().toString());
    body.put(
      "templateId",
      input.templateId() == null ? "" : input.templateId().toString()
    );
    body.put("text", input.text() == null ? "" : input.text());
    body.set(
      "variables",
      json.tree(
        new TreeMap<>(input.variables() == null ? Map.of() : input.variables())
      )
    );
    return Crypto.hash(json.write(body));
  }

  private Prepared prepare(UUID workspace, Input input) {
    var contact = store.get(Kind.CONTACTS, workspace, input.contactId());
    var channel = store.get(
      Kind.CHANNELS,
      workspace,
      input.channelConnectionId()
    );
    String phone = ConsentService.phone(contact.spec().path("phone").asText());
    if (!contact.enabled()) throw new IllegalArgumentException(
      "Ative o contato antes de enviar."
    );
    if (
      !ConsentService.validOptIn(contact.spec())
    ) throw new IllegalArgumentException(
      "O contato precisa de consentimento válido, com data e origem, para receber WhatsApp."
    );
    if (
      consent.suppressed(workspace, phone)
    ) throw new IllegalArgumentException(
      "Este contato está na lista de bloqueio de envios."
    );
    if (!channel.enabled()) throw new IllegalArgumentException(
      "Selecione um remetente ativo."
    );
    String provider = channel.spec().path("provider").asText();
    if (
      !Set.of("WHATSAPP_EVOLUTION", "WHATSAPP_META", "FAKE").contains(
        provider
      ) ||
      !providers.get(provider).validateConfiguration(channel)
    ) throw new IllegalArgumentException(
      "O remetente ainda não está configurado para envio."
    );
    var command = json.object();
    command.put("provider", provider);
    command.put("channelFingerprint", Crypto.hash(json.write(channel.spec())));
    command.set("consent", contact.spec());
    command.put("correlationId", "manual-" + input.requestId());
    String text,
      templateName = "";
    var variables =
      input.variables() == null ? Map.<String, String>of() : input.variables();
    if (input.templateId() == null) {
      if (provider.equals("WHATSAPP_META")) throw new IllegalArgumentException(
        "Para Meta Cloud API, selecione um template aprovado."
      );
      if (!variables.isEmpty()) throw new IllegalArgumentException(
        "Mensagem livre não aceita variáveis separadas."
      );
      text = input.text() == null ? "" : input.text();
    } else {
      if (
        input.text() != null && !input.text().isBlank()
      ) throw new IllegalArgumentException(
        "Use o corpo do template ou uma mensagem livre, não ambos."
      );
      var template = store.get(Kind.TEMPLATES, workspace, input.templateId());
      var spec = template.spec();
      if (
        !template.enabled() ||
        !spec.path("status").asText().equals("APPROVED") ||
        !spec
          .path("channelConnectionId")
          .asText()
          .equals(channel.id().toString())
      ) throw new IllegalArgumentException(
        "Selecione um template aprovado e ativo deste remetente."
      );
      var parameters = command.putArray("parameters");
      Set<String> declared = new HashSet<>();
      for (var name : spec.path("variables")) {
        String key = name.asText(),
          value = variables.get(key);
        if (
          value == null || value.isBlank()
        ) throw new IllegalArgumentException("Preencha a variável: " + key);
        declared.add(key);
        parameters.add(value);
      }
      if (
        !declared.equals(variables.keySet())
      ) throw new IllegalArgumentException(
        "Variáveis diferentes das definidas no template."
      );
      for (String key : com.mibess.notify.template.TemplateRenderer.variables(
        spec.path("body").asText()
      ))
        if (!declared.contains(key)) throw new IllegalArgumentException(
          "O template tem uma variável não declarada: " + key
        );
      text = VARIABLE.matcher(spec.path("body").asText()).replaceAll(match ->
        Matcher.quoteReplacement(variables.get(match.group(1)))
      );
      templateName = spec.path("providerTemplateName").asText();
      command.put("templateName", templateName);
      command.put("language", spec.path("language").asText());
      command.put("templateFingerprint", Crypto.hash(json.write(spec)));
    }
    if (
      text.isBlank() || text.length() > 4096
    ) throw new IllegalArgumentException(
      "Escreva uma mensagem de 1 a 4096 caracteres."
    );
    command.put("text", text);
    String previewHash = Crypto.hash(
      json.write(command) +
        ":" +
        phone +
        ":" +
        contact.name() +
        ":" +
        channel.name()
    );
    return new Prepared(
      contact,
      channel,
      command,
      new Preview(
        previewHash,
        contact.name(),
        phone,
        channel.name(),
        provider,
        text,
        templateName
      )
    );
  }
}
