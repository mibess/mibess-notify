package com.mibess.notify.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.mibess.notify.notification.NotificationStatus;
import com.mibess.notify.shared.*;
import com.mibess.notify.shared.ResourceStore.*;
import java.time.Instant;
import java.util.*;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class WebhookService {

  private final JdbcClient db;
  private final Json json;
  private final TransactionTemplate tx;

  public WebhookService(JdbcClient db, Json json, TransactionTemplate tx) {
    this.db = db;
    this.json = json;
    this.tx = tx;
  }

  @Transactional
  public void accept(Resource connection, JsonNode value, String fingerprint) {
    UUID id = UUID.randomUUID();
    int count = db
      .sql(
        "INSERT INTO webhook_receipts(id,workspace_id,channel_connection_id,fingerprint,payload) VALUES(:id,:w,:c,:f,CAST(:payload AS jsonb)) ON CONFLICT(fingerprint) DO NOTHING"
      )
      .param("id", id)
      .param("w", connection.workspaceId())
      .param("c", connection.id())
      .param("f", fingerprint)
      .param("payload", json.write(value))
      .update();
    if (count > 0) db.sql(
      "INSERT INTO outbox(id,topic,aggregate_id) VALUES(:o,'notify.webhooks',:id)"
    )
      .param("o", UUID.randomUUID())
      .param("id", id)
      .update();
  }

  @RabbitListener(queues = "notify.webhooks")
  @Transactional
  public void process(String message) {
    processId(UUID.fromString(message));
  }

  private void processId(UUID id) {
    var receipt = db
      .sql("SELECT * FROM webhook_receipts WHERE id=:id FOR UPDATE")
      .param("id", id)
      .query()
      .singleRow();
    if (receipt.get("status").equals("PROCESSED")) return;
    JsonNode value = json.read(receipt.get("payload").toString());
    UUID channel = (UUID) receipt.get("channel_connection_id"),
      workspace = (UUID) receipt.get("workspace_id");
    boolean deferred = false;
    for (JsonNode status : value.path("statuses")) {
      String providerId = status.path("id").asText(),
        next = status.path("status").asText().toUpperCase(Locale.ROOT);
      if (
        !Set.of("SENT", "DELIVERED", "READ", "FAILED").contains(next)
      ) continue;
      var rows = db
        .sql(
          "SELECT id,status FROM notifications WHERE workspace_id=:w AND channel_connection_id=:c AND provider_message_id=:p FOR UPDATE"
        )
        .param("w", workspace)
        .param("c", channel)
        .param("p", providerId)
        .query()
        .listOfRows();
      if (rows.isEmpty()) {
        deferred = true;
        continue;
      }
      var notification = rows.getFirst();
      UUID notificationId = (UUID) notification.get("id");
      if (
        !NotificationStatus.valueOf(
          notification.get("status").toString()
        ).accepts(NotificationStatus.valueOf(next))
      ) continue;
      String column = switch (next) {
        case "DELIVERED" -> "delivered_at";
        case "READ" -> "read_at";
        case "FAILED" -> "failed_at";
        default -> "sent_at";
      };
      Instant at = Instant.now();
      try {
        at = Instant.ofEpochSecond(
          Long.parseLong(status.path("timestamp").asText())
        );
      } catch (Exception ignored) {}
      db.sql(
        "UPDATE notifications SET status=:s," +
          column +
          "=COALESCE(" +
          column +
          ",:at),error_code=:error WHERE id=:id"
      )
        .param("s", next)
        .param("at", java.sql.Timestamp.from(at))
        .param(
          "error",
          next.equals("FAILED")
            ? "META_WEBHOOK_" +
                status.path("errors").path(0).path("code").asText("UNKNOWN")
            : null
        )
        .param("id", notificationId)
        .update();
      db.sql(
        "INSERT INTO notification_timeline(notification_id,status,description,created_at) VALUES(:id,:s,:d,:at)"
      )
        .param("id", notificationId)
        .param("s", next)
        .param("d", "Confirmação do provider: " + next)
        .param("at", java.sql.Timestamp.from(at))
        .update();
    }
    for (JsonNode inbound : value.path("messages")) {
      String providerId = inbound.path("id").asText();
      if (providerId.isBlank()) continue;
      String from = inbound
        .path("from")
        .asText(inbound.path("from_user_id").asText());
      db.sql(
        "INSERT INTO inbound_messages(id,workspace_id,channel_connection_id,provider_message_id,sender_id,message_type) VALUES(:id,:w,:c,:p,:from,:type) ON CONFLICT(provider_message_id) DO NOTHING"
      )
        .param("id", UUID.randomUUID())
        .param("w", workspace)
        .param("c", channel)
        .param("p", providerId)
        .param("from", from)
        .param("type", inbound.path("type").asText("unknown"))
        .update();
      String text = inbound
        .path("text")
        .path("body")
        .asText()
        .trim()
        .toUpperCase(Locale.ROOT);
      if (
        Set.of("STOP", "SAIR", "PARAR", "CANCELAR", "UNSUBSCRIBE").contains(
          text
        ) &&
        from.matches("[1-9][0-9]{7,14}")
      ) {
        db.sql(
          "INSERT INTO suppressions(id,workspace_id,address,reason) VALUES(:id,:w,:a,'Opt-out recebido via WhatsApp') ON CONFLICT DO NOTHING"
        )
          .param("id", UUID.randomUUID())
          .param("w", workspace)
          .param("a", "+" + from)
          .update();
        db.sql(
          "UPDATE contacts SET spec=spec || jsonb_build_object('whatsappOptIn',false,'whatsappOptOutAt',now()),updated_at=now() WHERE workspace_id=:w AND spec->>'phone'=:p"
        )
          .param("w", workspace)
          .param("p", "+" + from)
          .update();
      }
    }
    db.sql(
      "UPDATE webhook_receipts SET status=:s,processed_at=CASE WHEN :done THEN now() ELSE NULL END WHERE id=:id"
    )
      .param("s", deferred ? "DEFERRED" : "PROCESSED")
      .param("done", !deferred)
      .param("id", id)
      .update();
  }

  @Scheduled(fixedDelay = 10000)
  public void reconcile() {
    var pending = db
      .sql(
        "SELECT id FROM webhook_receipts WHERE status='DEFERRED' AND received_at>now()-interval '24 hours' ORDER BY received_at LIMIT 100"
      )
      .query(UUID.class)
      .list();
    for (UUID id : pending) tx.executeWithoutResult(s -> processId(id));
    db.sql(
      "UPDATE webhook_receipts SET status='UNMATCHED',error_code='MESSAGE_NOT_FOUND' WHERE status='DEFERRED' AND received_at<=now()-interval '24 hours'"
    ).update();
    // Raw payloads may contain inbound content. Retain only essential metadata after processing.
    db.sql(
      "UPDATE webhook_receipts SET payload='{}'::jsonb WHERE status IN ('PROCESSED','UNMATCHED') AND received_at<now()-interval '24 hours' AND payload<>'{}'::jsonb"
    ).update();
  }
}
