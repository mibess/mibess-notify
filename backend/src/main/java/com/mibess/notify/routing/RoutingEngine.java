package com.mibess.notify.routing;

import com.fasterxml.jackson.databind.JsonNode;
import com.mibess.notify.contact.ConsentService;
import com.mibess.notify.shared.*;
import com.mibess.notify.shared.ResourceStore.*;
import com.mibess.notify.template.TemplateRenderer;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RoutingEngine {

  private final JdbcClient db;
  private final Json json;
  private final ResourceStore store;
  private final ConsentService consent;

  public RoutingEngine(
    JdbcClient db,
    Json json,
    ResourceStore store,
    ConsentService consent
  ) {
    this.db = db;
    this.json = json;
    this.store = store;
    this.consent = consent;
  }

  public record Recipient(
    UUID contactId,
    String name,
    String address,
    JsonNode consent
  ) {}

  @Transactional
  public void route(String message) {
    UUID eventId = UUID.fromString(message);
    var event = db
      .sql("SELECT * FROM events WHERE id=:id FOR UPDATE")
      .param("id", eventId)
      .query()
      .singleRow();
    if (!event.get("status").equals("ACCEPTED")) return;
    UUID workspace = (UUID) event.get("workspace_id"),
      app = (UUID) event.get("application_id");
    JsonNode payload = json.read(event.get("payload").toString());
    var diagnostics = json.object();
    diagnostics.put("evaluatedAt", Instant.now().toString());
    diagnostics.put("applicationId", app.toString());
    diagnostics.put("eventType", event.get("type").toString());
    var evaluated = diagnostics.putArray("rules");
    if (!store.get(Kind.APPLICATIONS, workspace, app).enabled()) {
      finish(eventId, "SKIPPED", "APPLICATION_DISABLED", diagnostics);
      return;
    }
    String reason = "NO_RULE_FOR_EVENT_TYPE";
    int specificity = 0;
    for (Resource rule : store.rulesForApplication(workspace, app)) {
      var s = rule.spec();
      var diagnostic = evaluated.addObject();
      diagnostic.put("ruleId", rule.id().toString());
      diagnostic.put("ruleCode", rule.code());
      diagnostic.put("targetType", s.path("targetType").asText());
      diagnostic.put("targetId", s.path("targetId").asText());
      diagnostic.put("recipientCount", 0);
      if (!EventTypes.matches(s, event.get("type").toString())) {
        diagnostic.put("code", "EVENT_TYPE_NOT_MATCHED");
        continue;
      }
      if (!rule.enabled()) {
        diagnostic.put("code", "RULE_DISABLED");
        if (specificity < 1) {
          reason = "RULE_DISABLED";
          specificity = 1;
        }
        continue;
      }
      if (!Conditions.matches(payload, s.path("conditions"))) {
        diagnostic.put("code", "CONDITIONS_NOT_MET");
        if (specificity < 2) {
          reason = "CONDITIONS_NOT_MET";
          specificity = 2;
        }
        continue;
      }
      var recipients = resolve(workspace, s, payload);
      diagnostic.put("recipientCount", recipients.size());
      if (recipients.isEmpty()) {
        diagnostic.put("code", "NO_ACTIVE_RECIPIENTS");
        reason = "NO_ACTIVE_RECIPIENTS";
        specificity = 3;
        continue;
      }
      diagnostic.put("code", "MATCHED");
      var channel = store.get(
        Kind.CHANNELS,
        workspace,
        UUID.fromString(s.path("channelConnectionId").asText())
      );
      var template = store.get(
        Kind.TEMPLATES,
        workspace,
        UUID.fromString(s.path("templateId").asText())
      );
      for (Recipient recipient : recipients) {
        String state = "PENDING",
          error = null;
        List<String> parameters = List.of();
        String text = "";
        if (!channel.enabled() || !template.enabled()) {
          state = "CANCELLED";
          error = "CONFIGURATION_DISABLED";
        } else if (
          !template.spec().path("status").asText().equals("APPROVED")
        ) {
          state = "FAILED";
          error = "TEMPLATE_NOT_APPROVED";
        } else if (
          !ConsentService.validOptIn(recipient.consent()) ||
          consent.suppressed(workspace, recipient.address())
        ) {
          state = "CANCELLED";
          error = "CONSENT_REQUIRED_OR_SUPPRESSED";
        } else {
          try {
            parameters = TemplateRenderer.parameters(template.spec(), payload);
            text = TemplateRenderer.render(template.spec(), payload);
          } catch (IllegalArgumentException e) {
            state = "FAILED";
            error = "MISSING_TEMPLATE_VARIABLE";
          }
        }
        var command = json.object();
        command.put(
          "templateName",
          template.spec().path("providerTemplateName").asText()
        );
        command.put("language", template.spec().path("language").asText());
        command.set("parameters", json.tree(parameters));
        command.put("text", text);
        command.set("consent", recipient.consent());
        command.put("correlationId", event.get("correlation_id").toString());
        UUID id = UUID.randomUUID();
        int inserted = db
          .sql(
            "INSERT INTO notifications(id,workspace_id,application_id,event_id,rule_id,channel_connection_id,template_id,contact_id,channel,recipient_type,recipient_name,recipient_address,priority,status,command,error_code) VALUES(:id,:w,:a,:e,:r,:c,:t,:contact,'WHATSAPP',:type,:name,:address,:priority,:status,CAST(:command AS jsonb),:error) ON CONFLICT(event_id,rule_id,recipient_address) DO NOTHING"
          )
          .param("id", id)
          .param("w", workspace)
          .param("a", app)
          .param("e", eventId)
          .param("r", rule.id())
          .param("c", channel.id())
          .param("t", template.id())
          .param("contact", recipient.contactId())
          .param("type", s.path("targetType").asText())
          .param("name", recipient.name())
          .param("address", recipient.address())
          .param(
            "priority",
            s
              .path("eventPriorities")
              .path(event.get("type").toString())
              .asText(s.path("priority").asText("NORMAL"))
          )
          .param("status", state)
          .param("command", json.write(command))
          .param("error", error)
          .update();
        if (inserted > 0) {
          timeline(
            id,
            "EVENT_RECEIVED",
            "Evento recebido",
            event.get("created_at")
          );
          timeline(
            id,
            "RULE_MATCHED",
            "Regra " + rule.code() + " aplicada",
            null
          );
          timeline(
            id,
            state,
            error == null ? "Notificação criada" : error,
            null
          );
        }
      }
    }
    // Count persisted rows, including conflicts from a previous attempt.
    long count = db
      .sql("SELECT count(*) FROM notifications WHERE event_id=:id")
      .param("id", eventId)
      .query(Long.class)
      .single();
    finish(
      eventId,
      count == 0 ? "NO_MATCH" : "PROCESSED",
      count == 0 ? reason : null,
      diagnostics
    );
  }

  public List<Recipient> resolve(UUID w, JsonNode rule, JsonNode payload) {
    String type = rule.path("targetType").asText();
    List<Recipient> result = new ArrayList<>();
    if (type.equals("EVENT_RECIPIENT")) {
      var r = payload.path("recipient");
      String phone;
      try {
        phone = ConsentService.phone(r.path("phone").asText());
      } catch (IllegalArgumentException e) {
        return List.of();
      }
      var existing = store.contactByPhone(w, phone);
      if (existing.isPresent()) {
        if (existing.get().enabled()) result.add(contact(existing.get()));
      } else result.add(
        new Recipient(null, r.path("name").asText("Destinatário"), phone, r)
      );
    } else if (type.equals("CONTACT")) {
      var c = store.get(
        Kind.CONTACTS,
        w,
        UUID.fromString(rule.path("targetId").asText())
      );
      if (c.enabled()) result.add(contact(c));
    } else {
      var group = store.get(
        Kind.GROUPS,
        w,
        UUID.fromString(rule.path("targetId").asText())
      );
      if (group.enabled()) for (JsonNode member : group
        .spec()
        .path("members")) {
        var c = store.get(Kind.CONTACTS, w, UUID.fromString(member.asText()));
        if (c.enabled()) result.add(contact(c));
      }
    }
    return result
      .stream()
      .collect(
        java.util.stream.Collectors.toMap(
          Recipient::address,
          r -> r,
          (first, ignored) -> first,
          LinkedHashMap::new
        )
      )
      .values()
      .stream()
      .toList();
  }

  private Recipient contact(Resource c) {
    return new Recipient(
      c.id(),
      c.name(),
      c.spec().path("phone").asText(),
      c.spec()
    );
  }

  private void finish(
    UUID id,
    String state,
    String error,
    com.fasterxml.jackson.databind.node.ObjectNode diagnostics
  ) {
    if (error == null) diagnostics.putNull("reason");
    else diagnostics.put("reason", error);
    db.sql(
      "UPDATE events SET status=:s,processed_at=now(),error_code=:error,routing_diagnostics=CAST(:diagnostics AS jsonb) WHERE id=:id"
    )
      .param("s", state)
      .param("id", id)
      .param("error", error)
      .param("diagnostics", json.write(diagnostics))
      .update();
    db.sql(
      "INSERT INTO event_timeline(event_id,status,description,details) VALUES(:id,:s,:d,CAST(:details AS jsonb))"
    )
      .param("id", id)
      .param("s", state)
      .param("d", error == null ? "Roteamento concluído" : error)
      .param("details", json.write(diagnostics))
      .update();
  }

  private void timeline(UUID id, String status, String description, Object at) {
    db.sql(
      "INSERT INTO notification_timeline(notification_id,status,description,created_at) VALUES(:id,:s,:d,COALESCE(:at,now()))"
    )
      .param("id", id)
      .param("s", status)
      .param("d", description)
      .param("at", at)
      .update();
  }
}
