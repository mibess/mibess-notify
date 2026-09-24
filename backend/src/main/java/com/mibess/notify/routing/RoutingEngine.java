package com.mibess.notify.routing;

import com.fasterxml.jackson.databind.JsonNode;
import com.mibess.notify.contact.ConsentService;
import com.mibess.notify.shared.*;
import com.mibess.notify.shared.ResourceStore.*;
import com.mibess.notify.template.TemplateRenderer;
import java.util.*;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
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

  record Recipient(
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
    int count = 0;
    if (!store.get(Kind.APPLICATIONS, workspace, app).enabled()) {
      finish(eventId, "SKIPPED", "APPLICATION_DISABLED");
      return;
    }
    for (Resource rule : store.list(Kind.RULES, workspace)) {
      var s = rule.spec();
      if (
        !rule.enabled() ||
        !s.path("applicationId").asText().equals(app.toString()) ||
        !s.path("eventType").asText().equals(event.get("type"))
      ) continue;
      if (!Conditions.matches(payload, s.path("conditions"))) continue;
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
      for (Recipient recipient : resolve(workspace, s, payload)) {
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
          .param("priority", s.path("priority").asText("NORMAL"))
          .param("status", state)
          .param("command", json.write(command))
          .param("error", error)
          .update();
        if (inserted > 0) {
          count++;
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
    finish(eventId, count == 0 ? "NO_MATCH" : "PROCESSED", null);
  }

  private List<Recipient> resolve(UUID w, JsonNode rule, JsonNode payload) {
    String type = rule.path("targetType").asText();
    List<Recipient> result = new ArrayList<>();
    if (type.equals("EVENT_RECIPIENT")) {
      var r = payload.path("recipient");
      String phone;
      try {
        phone = ConsentService.phone(r.path("phone").asText());
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException(
          "EVENT_RECIPIENT sem telefone válido"
        );
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
    return result;
  }

  private Recipient contact(Resource c) {
    return new Recipient(
      c.id(),
      c.name(),
      c.spec().path("phone").asText(),
      c.spec()
    );
  }

  private void finish(UUID id, String state, String error) {
    db.sql(
      "UPDATE events SET status=:s,processed_at=now(),error_code=:error WHERE id=:id"
    )
      .param("s", state)
      .param("id", id)
      .param("error", error)
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
