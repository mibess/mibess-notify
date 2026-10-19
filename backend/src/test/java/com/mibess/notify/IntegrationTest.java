package com.mibess.notify;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.mibess.notify.application.ApiKeyService;
import com.mibess.notify.channel.ChannelProvider.DeliveryFailure;
import com.mibess.notify.channel.FakeProvider;
import com.mibess.notify.shared.*;
import com.mibess.notify.shared.ResourceStore.*;
import com.mibess.notify.webhook.WebhookService;
import com.mibess.notify.workspace.Bootstrap;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.*;

@SpringBootTest(
  properties = {
    "notify.fake-provider=true",
    "notify.admin-password=Integration-only-Password-2026",
    "notify.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
    "notify.outbox-delay=100",
    "notify.dispatch-delay=100",
    "notify.retry-delays=1,1",
    "server.servlet.session.cookie.secure=false",
  }
)
@AutoConfigureMockMvc
@Testcontainers
@org.springframework.test.annotation.DirtiesContext(
  classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS
)
class IntegrationTest {

  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
    "postgres:15.5"
  );

  @Container
  static RabbitMQContainer rabbit = new RabbitMQContainer(
    "rabbitmq:4.1-management"
  );

  @DynamicPropertySource
  static void configuration(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", postgres::getJdbcUrl);
    r.add("spring.datasource.username", postgres::getUsername);
    r.add("spring.datasource.password", postgres::getPassword);
    r.add("spring.rabbitmq.host", rabbit::getHost);
    r.add("spring.rabbitmq.port", rabbit::getAmqpPort);
    r.add("spring.rabbitmq.username", rabbit::getAdminUsername);
    r.add("spring.rabbitmq.password", rabbit::getAdminPassword);
    r.add("spring.rabbitmq.virtual-host", () -> "/");
  }

  @Autowired
  MockMvc mvc;

  @Autowired
  ResourceStore store;

  @Autowired
  Json json;

  @Autowired
  JdbcClient db;

  @Autowired
  ApiKeyService keys;

  @Autowired
  WebhookService webhooks;

  @MockitoSpyBean
  FakeProvider fake;

  @org.springframework.test.context.bean.override.mockito.MockitoBean
  com.mibess.notify.media.ImageStorage imageStorage;

  UUID app, channel, contact, group, template;
  String key;

  @Test
  void evolutionWebhookRequiresChannelTokenAndMatchingInstance()
    throws Exception {
    var crypto = new Crypto("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
    var spec = json
      .object()
      .put("provider", "WHATSAPP_EVOLUTION")
      .put("instanceName", "TEST_EVOLUTION");
    spec.put(
      "encryptedCredentials",
      crypto.encrypt(
        "{\"apiKey\":\"test-key\",\"webhookToken\":\"test-webhook-token\"}",
        Bootstrap.WORKSPACE + ":" + channel
      )
    );
    store.save(
      Kind.CHANNELS,
      Bootstrap.WORKSPACE,
      channel,
      "EVO_" + channel.toString().substring(0, 8),
      "Evolution test",
      true,
      spec
    );
    String url = "/webhooks/evolution/whatsapp/" + channel;
    String body =
      "{\"event\":\"messages.update\",\"instance\":\"TEST_EVOLUTION\",\"apikey\":\"must-not-persist\",\"data\":{\"keyId\":\"unknown-evolution-id\",\"fromMe\":true,\"status\":\"DELIVERY_ACK\"}}";
    mvc
      .perform(post(url).contentType("application/json").content(body))
      .andExpect(status().isUnauthorized());
    mvc
      .perform(
        post(url)
          .header("X-Notify-Webhook-Token", "wrong")
          .contentType("application/json")
          .content(body)
      )
      .andExpect(status().isUnauthorized());
    mvc
      .perform(
        post(url)
          .header("X-Notify-Webhook-Token", "test-webhook-token")
          .contentType("application/json")
          .content(body.replace("TEST_EVOLUTION", "OTHER"))
      )
      .andExpect(status().isBadRequest());
    for (int i = 0; i < 2; i++) mvc
      .perform(
        post(url)
          .header("X-Notify-Webhook-Token", "test-webhook-token")
          .contentType("application/json")
          .content(body)
      )
      .andExpect(status().isOk());
    var receipts = db
      .sql(
        "SELECT payload::text FROM webhook_receipts WHERE channel_connection_id=:id"
      )
      .param("id", channel)
      .query(String.class)
      .list();
    assertThat(receipts).hasSize(1);
    assertThat(receipts.getFirst())
      .contains("delivered")
      .doesNotContain("apikey", "must-not-persist");
  }

  @Autowired
  com.mibess.notify.notification.DeliveryWorker deliveryWorker;

  private com.fasterxml.jackson.databind.node.ObjectNode manualInput() {
    return json
      .object()
      .put("requestId", UUID.randomUUID().toString())
      .put("contactId", contact.toString())
      .put("channelConnectionId", channel.toString())
      .put("text", "Olá! Mensagem manual de teste.");
  }

  private com.fasterxml.jackson.databind.node.ObjectNode reviewManual(
    com.fasterxml.jackson.databind.node.ObjectNode input
  ) throws Exception {
    var response = mvc
      .perform(
        post("/api/v1/admin/notifications/manual/preview")
          .with(user("admin@mibess.com.br").roles("ADMIN"))
          .with(csrf())
          .contentType("application/json")
          .content(json.write(input))
      )
      .andExpect(status().isOk())
      .andReturn()
      .getResponse()
      .getContentAsString();
    input.put("previewHash", json.read(response).path("previewHash").asText());
    return input;
  }

  private UUID sendManual(com.fasterxml.jackson.databind.node.ObjectNode input)
    throws Exception {
    var response = mvc
      .perform(
        post("/api/v1/admin/notifications/manual")
          .with(user("admin@mibess.com.br").roles("ADMIN"))
          .with(csrf())
          .contentType("application/json")
          .content(json.write(input))
      )
      .andExpect(status().isAccepted())
      .andReturn()
      .getResponse()
      .getContentAsString();
    return UUID.fromString(json.read(response).path("notificationId").asText());
  }

  @Test
  void manualSendUsesSameQueueAndDeduplicatesWithoutCreatingRoutingResources()
    throws Exception {
    var input = manualInput();
    long before = db
      .sql("SELECT count(*) FROM notifications WHERE source='MANUAL'")
      .query(Long.class)
      .single();
    reviewManual(input);
    assertThat(
      db
        .sql("SELECT count(*) FROM notifications WHERE source='MANUAL'")
        .query(Long.class)
        .single()
    ).isEqualTo(before);
    UUID id = sendManual(input);
    var repeated = mvc
      .perform(
        post("/api/v1/admin/notifications/manual")
          .with(user("admin@mibess.com.br").roles("ADMIN"))
          .with(csrf())
          .contentType("application/json")
          .content(json.write(input))
      )
      .andExpect(status().isOk())
      .andReturn()
      .getResponse()
      .getContentAsString();
    assertThat(json.read(repeated).path("notificationId").asText()).isEqualTo(
      id.toString()
    );
    assertThat(json.read(repeated).path("duplicate").asBoolean()).isTrue();
    await()
      .atMost(Duration.ofSeconds(15))
      .untilAsserted(() ->
        assertThat(
          db
            .sql("SELECT status FROM notifications WHERE id=:id")
            .param("id", id)
            .query(String.class)
            .single()
        ).isEqualTo("SENT")
      );
    var row = db
      .sql("SELECT * FROM notifications WHERE id=:id")
      .param("id", id)
      .query()
      .singleRow();
    assertThat(row.get("source")).isEqualTo("MANUAL");
    assertThat(row.get("event_id")).isNull();
    assertThat(row.get("rule_id")).isNull();
    assertThat(row.get("template_id")).isNull();
    assertThat(row.get("attempt_count")).isEqualTo(1);
    input.put("text", "Different message");
    mvc
      .perform(
        post("/api/v1/admin/notifications/manual")
          .with(user("admin@mibess.com.br").roles("ADMIN"))
          .with(csrf())
          .contentType("application/json")
          .content(json.write(input))
      )
      .andExpect(status().isConflict());
  }

  @Test
  void manualSendRequiresRoleCsrfConsentAndFreshPreview() throws Exception {
    var input = manualInput();
    mvc
      .perform(
        post("/api/v1/admin/notifications/manual/preview")
          .with(user("admin@mibess.com.br").roles("VIEWER"))
          .with(csrf())
          .contentType("application/json")
          .content(json.write(input))
      )
      .andExpect(status().isForbidden());
    mvc
      .perform(
        post("/api/v1/admin/notifications/manual")
          .with(user("admin@mibess.com.br").roles("ADMIN"))
          .contentType("application/json")
          .content(json.write(input))
      )
      .andExpect(status().isForbidden());
    mvc
      .perform(
        post("/api/v1/admin/notifications/manual")
          .with(user("admin@mibess.com.br").roles("ADMIN"))
          .with(csrf())
          .contentType("application/json")
          .content(json.write(input))
      )
      .andExpect(status().isConflict());
    reviewManual(input);
    input.put("text", "Changed after preview");
    mvc
      .perform(
        post("/api/v1/admin/notifications/manual")
          .with(user("admin@mibess.com.br").roles("ADMIN"))
          .with(csrf())
          .contentType("application/json")
          .content(json.write(input))
      )
      .andExpect(status().isConflict());
    db.sql(
      "UPDATE contacts SET spec=spec || '{\"whatsappOptIn\":false}'::jsonb WHERE id=:id"
    )
      .param("id", contact)
      .update();
    mvc
      .perform(
        post("/api/v1/admin/notifications/manual/preview")
          .with(user("admin@mibess.com.br").roles("ADMIN"))
          .with(csrf())
          .contentType("application/json")
          .content(json.write(input))
      )
      .andExpect(status().isBadRequest());
  }

  @Test
  void manualSendRejectsSuppressionForeignWorkspaceAndMetaFreeText()
    throws Exception {
    var input = manualInput();
    input.put("contactId", UUID.randomUUID().toString());
    mvc
      .perform(
        post("/api/v1/admin/notifications/manual/preview")
          .with(user("admin@mibess.com.br").roles("ADMIN"))
          .with(csrf())
          .contentType("application/json")
          .content(json.write(input))
      )
      .andExpect(status().isNotFound());
    input.put("contactId", contact.toString());
    String phone = store
      .get(Kind.CONTACTS, Bootstrap.WORKSPACE, contact)
      .spec()
      .path("phone")
      .asText();
    db.sql(
      "INSERT INTO suppressions(id,workspace_id,address,reason) VALUES(:id,:w,:p,'Manual test') ON CONFLICT DO NOTHING"
    )
      .param("id", UUID.randomUUID())
      .param("w", Bootstrap.WORKSPACE)
      .param("p", phone)
      .update();
    mvc
      .perform(
        post("/api/v1/admin/notifications/manual/preview")
          .with(user("admin@mibess.com.br").roles("ADMIN"))
          .with(csrf())
          .contentType("application/json")
          .content(json.write(input))
      )
      .andExpect(status().isBadRequest());
    db.sql("DELETE FROM suppressions WHERE workspace_id=:w AND address=:p")
      .param("w", Bootstrap.WORKSPACE)
      .param("p", phone)
      .update();
    var crypto = new Crypto("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
    var spec = json
      .object()
      .put("provider", "WHATSAPP_META")
      .put("phoneNumberId", "12345")
      .put(
        "encryptedCredentials",
        crypto.encrypt(
          "{\"accessToken\":\"mock\"}",
          Bootstrap.WORKSPACE + ":" + channel
        )
      );
    store.save(
      Kind.CHANNELS,
      Bootstrap.WORKSPACE,
      channel,
      "META_" + channel.toString().substring(0, 8),
      "Meta",
      true,
      spec
    );
    mvc
      .perform(
        post("/api/v1/admin/notifications/manual/preview")
          .with(user("admin@mibess.com.br").roles("ADMIN"))
          .with(csrf())
          .contentType("application/json")
          .content(json.write(input))
      )
      .andExpect(status().isBadRequest());
  }

  @Test
  @org.springframework.transaction.annotation.Transactional
  void manualWorkerRechecksConsentAndManualRetryKeepsOriginalContent()
    throws Exception {
    var input = reviewManual(manualInput());
    UUID id = sendManual(input);
    db.sql(
      "UPDATE notifications SET status='FAILED',error_code='FAKE_TRANSIENT' WHERE id=:id"
    )
      .param("id", id)
      .update();
    mvc
      .perform(
        post("/api/v1/admin/notifications/" + id + "/retry")
          .with(user("admin@mibess.com.br").roles("ADMIN"))
          .with(csrf())
      )
      .andExpect(status().isOk());
    assertThat(
      json
        .read(
          db
            .sql("SELECT command::text FROM notifications WHERE id=:id")
            .param("id", id)
            .query(String.class)
            .single()
        )
        .path("text")
        .asText()
    ).isEqualTo(input.path("text").asText());
    db.sql("UPDATE contacts SET enabled=false WHERE id=:id")
      .param("id", contact)
      .update();
    db.sql("UPDATE notifications SET status='QUEUED' WHERE id=:id")
      .param("id", id)
      .update();
    deliveryWorker.deliver(id.toString());
    assertThat(
      db
        .sql("SELECT status FROM notifications WHERE id=:id")
        .param("id", id)
        .query(String.class)
        .single()
    ).isEqualTo("CANCELLED");
  }

  @Test
  @org.springframework.transaction.annotation.Transactional
  void manualTemplateRendersParametersAndCancelsIfTemplateChanges()
    throws Exception {
    var input = manualInput();
    input.remove("text");
    input.put("templateId", template.toString());
    input.putObject("variables").put("orderNumber", "$123");
    reviewManual(input);
    UUID id = sendManual(input);
    var command = json.read(
      db
        .sql("SELECT command::text FROM notifications WHERE id=:id")
        .param("id", id)
        .query(String.class)
        .single()
    );
    assertThat(command.path("text").asText()).isEqualTo("Pedido $123");
    assertThat(command.path("parameters").path(0).asText()).isEqualTo("$123");
    db.sql(
      "UPDATE templates SET spec=spec || '{\"body\":\"Alterado\"}'::jsonb WHERE id=:id"
    )
      .param("id", template)
      .update();
    db.sql("UPDATE notifications SET status='QUEUED' WHERE id=:id")
      .param("id", id)
      .update();
    deliveryWorker.deliver(id.toString());
    assertThat(
      db
        .sql("SELECT status FROM notifications WHERE id=:id")
        .param("id", id)
        .query(String.class)
        .single()
    ).isEqualTo("CANCELLED");
  }

  private UUID uploadImage() throws Exception {
    byte[] bytes = com.mibess.notify.media.StandardImage.create(
      "Artgian",
      960,
      504
    );
    var result = mvc
      .perform(
        multipart("/api/v1/admin/images")
          .file(
            new org.springframework.mock.web.MockMultipartFile(
              "file",
              "identity.jpg",
              "image/jpeg",
              bytes
            )
          )
          .with(user("admin@mibess.com.br").roles("ADMIN"))
          .with(csrf())
      )
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.width").value(960))
      .andReturn()
      .getResponse()
      .getContentAsString();
    return UUID.fromString(json.read(result).path("id").asText());
  }

  @Test
  @org.springframework.transaction.annotation.Transactional
  void imageUploadsRequireRoleCsrfRealDimensionsAndWorkspaceOwnership()
    throws Exception {
    byte[] bytes = com.mibess.notify.media.StandardImage.create(
      "Artgian",
      960,
      504
    );
    var file = new org.springframework.mock.web.MockMultipartFile(
      "file",
      "identity.jpg",
      "image/jpeg",
      bytes
    );
    mvc
      .perform(
        multipart("/api/v1/admin/images")
          .file(file)
          .with(user("admin@mibess.com.br").roles("VIEWER"))
          .with(csrf())
      )
      .andExpect(status().isForbidden());
    mvc
      .perform(
        multipart("/api/v1/admin/images")
          .file(file)
          .with(user("admin@mibess.com.br").roles("ADMIN"))
      )
      .andExpect(status().isForbidden());
    mvc
      .perform(
        multipart("/api/v1/admin/images")
          .file(
            new org.springframework.mock.web.MockMultipartFile(
              "file",
              "wrong.jpg",
              "image/jpeg",
              com.mibess.notify.media.StandardImage.create("Artgian", 400, 400)
            )
          )
          .with(user("admin@mibess.com.br").roles("OPERATOR"))
          .with(csrf())
      )
      .andExpect(status().isBadRequest())
      .andExpect(
        jsonPath("$.detail").value(
          org.hamcrest.Matchers.containsString("960 × 504")
        )
      );
    UUID image = uploadImage();
    when(imageStorage.read(anyString())).thenReturn(bytes);
    mvc
      .perform(
        get("/api/v1/admin/images/" + image + "/content").with(
          user("admin@mibess.com.br").roles("VIEWER")
        )
      )
      .andExpect(status().isOk())
      .andExpect(content().bytes(bytes))
      .andExpect(header().string("Cache-Control", "no-store"));
    UUID foreign = UUID.randomUUID();
    db.sql(
      "INSERT INTO workspaces(id,name) VALUES(:id,'Other image workspace')"
    )
      .param("id", foreign)
      .update();
    db.sql("UPDATE notification_images SET workspace_id=:w WHERE id=:id")
      .param("w", foreign)
      .param("id", image)
      .update();
    mvc
      .perform(
        get("/api/v1/admin/images/" + image + "/content").with(
          user("admin@mibess.com.br").roles("ADMIN")
        )
      )
      .andExpect(status().isNotFound());
    var application = store.get(Kind.APPLICATIONS, Bootstrap.WORKSPACE, app);
    mvc
      .perform(
        put("/api/v1/admin/applications/" + app)
          .with(user("admin@mibess.com.br").roles("ADMIN"))
          .with(csrf())
          .contentType("application/json")
          .content(
            json.write(
              Map.of(
                "code",
                application.code(),
                "name",
                application.name(),
                "enabled",
                true,
                "spec",
                Map.of("imageId", image)
              )
            )
          )
      )
      .andExpect(status().isNotFound());
  }

  @Test
  @org.springframework.transaction.annotation.Transactional
  void manualImageIsReviewedAndCannotBeSwappedAfterConfirmation()
    throws Exception {
    UUID image = uploadImage();
    var input = manualInput().put("imageId", image.toString());
    var reviewed = reviewManual(input);
    UUID notification = sendManual(reviewed);
    var command = json.read(
      db
        .sql("SELECT command::text FROM notifications WHERE id=:id")
        .param("id", notification)
        .query(String.class)
        .single()
    );
    assertThat(command.path("imageId").asText()).isEqualTo(image.toString());
    assertThat(command.has("imageUrl")).isFalse();
    reviewed.put("imageId", uploadImage().toString());
    mvc
      .perform(
        post("/api/v1/admin/notifications/manual")
          .with(user("admin@mibess.com.br").roles("ADMIN"))
          .with(csrf())
          .contentType("application/json")
          .content(json.write(reviewed))
      )
      .andExpect(status().isConflict());
  }

  @Test
  @org.springframework.transaction.annotation.Transactional
  void routedNotificationsFreezeTheApplicationsImageAndRetryCanUseNewConfiguration()
    throws Exception {
    UUID image = uploadImage();
    var application = store.get(Kind.APPLICATIONS, Bootstrap.WORKSPACE, app);
    store.save(
      Kind.APPLICATIONS,
      Bootstrap.WORKSPACE,
      app,
      application.code(),
      application.name(),
      true,
      json.object().put("imageId", image.toString())
    );
    UUID event = localEvent("ORDER_PAID", "{\"orderNumber\":\"image-order\"}");
    routingEngine.route(event.toString());
    var row = db
      .sql("SELECT id,command::text FROM notifications WHERE event_id=:id")
      .param("id", event)
      .query()
      .singleRow();
    assertThat(
      json.read(row.get("command").toString()).path("imageId").asText()
    ).isEqualTo(image.toString());
    UUID replacement = uploadImage();
    store.save(
      Kind.APPLICATIONS,
      Bootstrap.WORKSPACE,
      app,
      application.code(),
      application.name(),
      true,
      json.object().put("imageId", replacement.toString())
    );
    db.sql(
      "UPDATE notifications SET status='FAILED',error_code='IMAGE_STORAGE_UNAVAILABLE' WHERE id=:id"
    )
      .param("id", row.get("id"))
      .update();
    mvc
      .perform(
        post("/api/v1/admin/notifications/" + row.get("id") + "/retry")
          .with(user("admin@mibess.com.br").roles("ADMIN"))
          .with(csrf())
      )
      .andExpect(status().isOk());
    assertThat(
      json
        .read(
          db
            .sql("SELECT command::text FROM notifications WHERE id=:id")
            .param("id", row.get("id"))
            .query(String.class)
            .single()
        )
        .path("imageId")
        .asText()
    ).isEqualTo(replacement.toString());
  }

  @BeforeEach
  void fixture() {
    String suffix = UUID.randomUUID().toString().substring(0, 8);
    app = UUID.randomUUID();
    channel = UUID.randomUUID();
    contact = UUID.randomUUID();
    group = UUID.randomUUID();
    template = UUID.randomUUID();
    store.save(
      Kind.APPLICATIONS,
      Bootstrap.WORKSPACE,
      app,
      "TEST_" + suffix,
      "Artgian teste",
      true,
      json.object()
    );
    store.save(
      Kind.CHANNELS,
      Bootstrap.WORKSPACE,
      channel,
      "CHANNEL_" + suffix,
      "Fake",
      true,
      json.tree(Map.of("provider", "FAKE"))
    );
    store.save(
      Kind.CONTACTS,
      Bootstrap.WORKSPACE,
      contact,
      "CONTACT_" + suffix,
      "Angélica teste",
      true,
      json.tree(
        Map.of(
          "phone",
          "+551600000" + String.format("%04d", new Random().nextInt(10000)),
          "type",
          "INTERNAL",
          "whatsappOptIn",
          true,
          "whatsappOptInAt",
          Instant.now().toString(),
          "whatsappOptInSource",
          "Teste automatizado"
        )
      )
    );
    store.save(
      Kind.GROUPS,
      Bootstrap.WORKSPACE,
      group,
      "GROUP_" + suffix,
      "Operações teste",
      true,
      json.tree(Map.of("members", List.of(contact)))
    );
    store.save(
      Kind.TEMPLATES,
      Bootstrap.WORKSPACE,
      template,
      "TEMPLATE_" + suffix,
      "Pedido teste",
      true,
      json.tree(
        Map.of(
          "channelConnectionId",
          channel,
          "providerTemplateName",
          "internal_new_order",
          "language",
          "pt_BR",
          "status",
          "APPROVED",
          "body",
          "Pedido {{orderNumber}}",
          "variables",
          List.of("orderNumber")
        )
      )
    );
    rule("ORDER_PAID", "CONTACT_GROUP", group, List.of());
    key = keys.create(Bootstrap.WORKSPACE, app, false).get("key").toString();
  }

  void rule(String event, String type, UUID target, List<?> conditions) {
    var spec = json.object();
    spec.put("applicationId", app.toString());
    spec.put("eventType", event);
    spec.put("targetType", type);
    if (target != null) spec.put("targetId", target.toString());
    spec.put("channelConnectionId", channel.toString());
    spec.put("templateId", template.toString());
    spec.put("priority", "HIGH");
    spec.set("conditions", json.tree(conditions));
    UUID id = UUID.randomUUID();
    store.save(
      Kind.RULES,
      Bootstrap.WORKSPACE,
      id,
      "RULE_" + id.toString().substring(0, 8),
      "Regra teste",
      true,
      spec
    );
  }

  String event(String idem, String payload) throws Exception {
    return mvc
      .perform(
        post("/api/v1/events")
          .header("X-API-Key", key)
          .header("Idempotency-Key", idem)
          .contentType("application/json")
          .content(payload)
      )
      .andExpect(status().isAccepted())
      .andReturn()
      .getResponse()
      .getContentAsString();
  }

  @Test
  void orderPaidThroughRealPostgresRabbitFakeAndWebhook() throws Exception {
    String payload =
      "{\"type\":\"ORDER_PAID\",\"correlationId\":\"order-1842\",\"data\":{\"orderNumber\":\"1842\"}}";
    String accepted = event("paid-1842", payload);
    UUID event = UUID.fromString(json.read(accepted).path("eventId").asText());
    assertThat(
      json.read(event("paid-1842", payload)).path("duplicate").asBoolean()
    ).isTrue();
    await()
      .atMost(Duration.ofSeconds(30))
      .untilAsserted(() ->
        assertThat(
          db
            .sql("SELECT status FROM notifications WHERE event_id=:e")
            .param("e", event)
            .query(String.class)
            .optional()
        ).contains("SENT")
      );
    var notification = db
      .sql("SELECT * FROM notifications WHERE event_id=:e")
      .param("e", event)
      .query()
      .singleRow();
    assertThat(notification.get("attempt_count")).isEqualTo(1);
    var receipt = json.tree(
      Map.of(
        "statuses",
        List.of(
          Map.of(
            "id",
            notification.get("provider_message_id"),
            "status",
            "delivered",
            "timestamp",
            String.valueOf(Instant.now().getEpochSecond())
          )
        )
      )
    );
    var connection = store.get(Kind.CHANNELS, Bootstrap.WORKSPACE, channel);
    webhooks.accept(connection, receipt, Crypto.hash(json.write(receipt)));
    webhooks.accept(connection, receipt, Crypto.hash(json.write(receipt)));
    await()
      .atMost(Duration.ofSeconds(20))
      .untilAsserted(() ->
        assertThat(
          db
            .sql("SELECT status FROM notifications WHERE event_id=:e")
            .param("e", event)
            .query(String.class)
            .single()
        ).isEqualTo("DELIVERED")
      );
    assertThat(
      db
        .sql("SELECT count(*) FROM notifications WHERE event_id=:e")
        .param("e", event)
        .query(Integer.class)
        .single()
    ).isEqualTo(1);
    mvc
      .perform(
        get("/api/v1/admin/notifications/" + notification.get("id")).with(
          user("admin@mibess.com.br").roles("ADMIN")
        )
      )
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.timeline.length()").value(7))
      .andExpect(jsonPath("$.timeline[0].status").value("EVENT_RECEIVED"))
      .andExpect(jsonPath("$.timeline[6].status").value("DELIVERED"));
    mvc
      .perform(
        post("/api/v1/events")
          .header("X-API-Key", key)
          .header("Idempotency-Key", "paid-1842")
          .contentType("application/json")
          .content(payload.replace("1842", "1843"))
      )
      .andExpect(status().isConflict());
  }

  @Test
  void conditionsSelectEventRecipientAndSuppressOptOut() throws Exception {
    rule(
      "TRACKING_STATUS_CHANGED",
      "EVENT_RECIPIENT",
      null,
      List.of(
        Map.of(
          "field",
          "data.status",
          "operator",
          "EQUALS",
          "value",
          "OUT_FOR_DELIVERY"
        )
      )
    );
    var payload = json.object();
    payload.put("type", "TRACKING_STATUS_CHANGED");
    payload.set(
      "recipient",
      json.tree(
        Map.of(
          "name",
          "Mariana",
          "phone",
          "+5516999999999",
          "whatsappOptIn",
          true,
          "whatsappOptInAt",
          Instant.now().toString(),
          "whatsappOptInSource",
          "Checkout"
        )
      )
    );
    payload.set(
      "data",
      json.tree(Map.of("status", "IN_TRANSIT", "orderNumber", "1842"))
    );
    UUID noMatch = UUID.fromString(
      json
        .read(event("in-transit", json.write(payload)))
        .path("eventId")
        .asText()
    );
    await()
      .atMost(Duration.ofSeconds(20))
      .untilAsserted(() ->
        assertThat(
          db
            .sql("SELECT status FROM events WHERE id=:e")
            .param("e", noMatch)
            .query(String.class)
            .single()
        ).isEqualTo("NO_MATCH")
      );
    payload.set(
      "data",
      json.tree(Map.of("status", "OUT_FOR_DELIVERY", "orderNumber", "1842"))
    );
    UUID matched = UUID.fromString(
      json
        .read(event("out-for-delivery", json.write(payload)))
        .path("eventId")
        .asText()
    );
    await()
      .atMost(Duration.ofSeconds(20))
      .untilAsserted(() ->
        assertThat(
          db
            .sql("SELECT status FROM notifications WHERE event_id=:e")
            .param("e", matched)
            .query(String.class)
            .optional()
        ).contains("SENT")
      );
    db.sql(
      "INSERT INTO suppressions(id,workspace_id,address,reason) VALUES(:id,:w,'+5516999999999','Test opt-out') ON CONFLICT DO NOTHING"
    )
      .param("id", UUID.randomUUID())
      .param("w", Bootstrap.WORKSPACE)
      .update();
    UUID suppressed = UUID.fromString(
      json
        .read(event("suppressed", json.write(payload)))
        .path("eventId")
        .asText()
    );
    await()
      .atMost(Duration.ofSeconds(20))
      .untilAsserted(() ->
        assertThat(
          db
            .sql("SELECT status FROM notifications WHERE event_id=:e")
            .param("e", suppressed)
            .query(String.class)
            .optional()
        ).contains("CANCELLED")
      );
  }

  @Test
  void authRolesCsrfAndRevocationAreEnforced() throws Exception {
    mvc
      .perform(get("/api/v1/admin/applications"))
      .andExpect(status().isUnauthorized());
    mvc
      .perform(
        post("/api/v1/events").contentType("application/json").content("{}")
      )
      .andExpect(status().isUnauthorized());
    mvc
      .perform(
        post("/webhooks/meta/whatsapp")
          .header("X-Hub-Signature-256", "sha256=" + "0".repeat(64))
          .contentType("application/json")
          .content("{\"object\":\"whatsapp_business_account\"}")
      )
      .andExpect(status().isUnauthorized());
    String input =
      "{\"code\":\"NEW_APP\",\"name\":\"New\",\"enabled\":true,\"spec\":{}}";
    mvc
      .perform(
        post("/api/v1/admin/applications")
          .with(user("admin@mibess.com.br").roles("ADMIN"))
          .contentType("application/json")
          .content(input)
      )
      .andExpect(status().isForbidden());
    mvc
      .perform(
        post("/api/v1/admin/applications")
          .with(user("admin@mibess.com.br").roles("VIEWER"))
          .with(csrf())
          .contentType("application/json")
          .content(input)
      )
      .andExpect(status().isForbidden());
    keys.create(Bootstrap.WORKSPACE, app, true);
    assertThatThrownBy(() -> keys.authenticate(key)).isInstanceOf(
      org.springframework.web.server.ResponseStatusException.class
    );
  }

  @Test
  void foreignWorkspaceResourceIsInvisible() throws Exception {
    UUID foreign = UUID.randomUUID(),
      id = UUID.randomUUID();
    db.sql("INSERT INTO workspaces(id,name) VALUES(:id,'Other')")
      .param("id", foreign)
      .update();
    store.save(
      Kind.APPLICATIONS,
      foreign,
      id,
      "OTHER_APP",
      "Other",
      true,
      json.object()
    );
    assertThatThrownBy(() ->
      store.get(Kind.APPLICATIONS, Bootstrap.WORKSPACE, id)
    ).isInstanceOf(
      org.springframework.web.server.ResponseStatusException.class
    );
  }

  @Test
  void transientFailureRetriesAndPreservesAttempts() throws Exception {
    doThrow(new DeliveryFailure("RATE_LIMIT", true, false, 429))
      .doCallRealMethod()
      .when(fake)
      .send(any());
    UUID event = UUID.fromString(
      json
        .read(
          event(
            "retry-once",
            "{\"type\":\"ORDER_PAID\",\"data\":{\"orderNumber\":\"retry\"}}"
          )
        )
        .path("eventId")
        .asText()
    );
    await()
      .atMost(Duration.ofSeconds(20))
      .untilAsserted(() ->
        assertThat(
          db
            .sql("SELECT status FROM notifications WHERE event_id=:e")
            .param("e", event)
            .query(String.class)
            .optional()
        ).contains("SENT")
      );
    assertThat(
      db
        .sql(
          "SELECT count(*) FROM delivery_attempts a JOIN notifications n ON n.id=a.notification_id WHERE n.event_id=:e"
        )
        .param("e", event)
        .query(Integer.class)
        .single()
    ).isEqualTo(2);
  }

  @Test
  void ambiguousFailureRequiresExplicitReconciliation() throws Exception {
    doThrow(new DeliveryFailure("DELIVERY_UNKNOWN", false, true, 0))
      .when(fake)
      .send(any());
    UUID event = UUID.fromString(
      json
        .read(
          event(
            "ambiguous",
            "{\"type\":\"ORDER_PAID\",\"data\":{\"orderNumber\":\"unknown\"}}"
          )
        )
        .path("eventId")
        .asText()
    );
    await()
      .atMost(Duration.ofSeconds(20))
      .untilAsserted(() ->
        assertThat(
          db
            .sql("SELECT status FROM notifications WHERE event_id=:e")
            .param("e", event)
            .query(String.class)
            .optional()
        ).contains("FAILED")
      );
    UUID id = db
      .sql("SELECT id FROM notifications WHERE event_id=:e")
      .param("e", event)
      .query(UUID.class)
      .single();
    mvc
      .perform(
        post("/api/v1/admin/notifications/" + id + "/retry")
          .with(user("admin@mibess.com.br").roles("ADMIN"))
          .with(csrf())
      )
      .andExpect(status().isConflict());
    assertThat(
      db
        .sql("SELECT attempt_count FROM notifications WHERE id=:id")
        .param("id", id)
        .query(Integer.class)
        .single()
    ).isEqualTo(1);
    doCallRealMethod().when(fake).send(any());
    mvc
      .perform(
        post(
          "/api/v1/admin/notifications/" + id + "/retry?acknowledgeUnknown=true"
        )
          .with(user("admin@mibess.com.br").roles("ADMIN"))
          .with(csrf())
      )
      .andExpect(status().isOk());
    await()
      .atMost(Duration.ofSeconds(20))
      .untilAsserted(() ->
        assertThat(
          db
            .sql("SELECT status FROM notifications WHERE id=:id")
            .param("id", id)
            .query(String.class)
            .single()
        ).isEqualTo("SENT")
      );
    assertThat(
      db
        .sql("SELECT attempt_count FROM notifications WHERE id=:id")
        .param("id", id)
        .query(Integer.class)
        .single()
    ).isEqualTo(2);
  }

  @Test
  void concurrentDuplicatesProduceOneEventAndNotification() throws Exception {
    var application = new ApiKeyService.Application(app, Bootstrap.WORKSPACE);
    var payload = json.read(
      "{\"type\":\"ORDER_PAID\",\"data\":{\"orderNumber\":\"race\"}}"
    );
    try (var executor = java.util.concurrent.Executors.newFixedThreadPool(8)) {
      var tasks = new ArrayList<java.util.concurrent.Callable<UUID>>();
      for (int i = 0; i < 16; i++) tasks.add(() ->
        eventService.accept(application, "race", payload).eventId()
      );
      var results = executor.invokeAll(tasks);
      Set<UUID> ids = new HashSet<>();
      for (var result : results) ids.add(result.get());
      assertThat(ids).hasSize(1);
      UUID id = ids.iterator().next();
      await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(() ->
          assertThat(
            db
              .sql("SELECT status FROM notifications WHERE event_id=:e")
              .param("e", id)
              .query(String.class)
              .optional()
          ).contains("SENT")
        );
      assertThat(
        db
          .sql("SELECT duplicate_count FROM events WHERE id=:id")
          .param("id", id)
          .query(Integer.class)
          .single()
      ).isEqualTo(15);
    }
  }

  @Autowired
  com.mibess.notify.routing.RoutingEngine routingEngine;

  private UUID localEvent(String type, String data) {
    var payload = json.object().put("type", type);
    payload.set("data", json.read(data));
    return eventService
      .accept(
        new ApiKeyService.Application(app, Bootstrap.WORKSPACE),
        UUID.randomUUID().toString(),
        payload
      )
      .eventId();
  }

  private Resource currentRule() {
    return store.rulesForApplication(Bootstrap.WORKSPACE, app).getFirst();
  }

  private void diagnose(
    UUID eventId,
    String expectedStatus,
    String expectedCode
  ) {
    routingEngine.route(eventId.toString());
    var row = db
      .sql(
        "SELECT status,error_code,routing_diagnostics::text AS diagnostic FROM events WHERE id=:id"
      )
      .param("id", eventId)
      .query()
      .singleRow();
    assertThat(row.get("status")).isEqualTo(expectedStatus);
    assertThat(row.get("error_code")).isEqualTo(expectedCode);
    assertThat(
      json.read(row.get("diagnostic").toString()).path("reason").asText()
    ).isEqualTo(expectedCode);
  }

  @Test
  @org.springframework.transaction.annotation.Transactional
  void routingExplainsEveryNoMatchReasonAndKeepsMostSpecificFailure()
    throws Exception {
    UUID noRule = localEvent("CRM_SYNC_RECOVERED", "{}");
    diagnose(noRule, "NO_MATCH", "NO_RULE_FOR_EVENT_TYPE");
    var rule = currentRule();
    db.sql("UPDATE routing_rules SET enabled=false WHERE id=:id")
      .param("id", rule.id())
      .update();
    diagnose(localEvent("ORDER_PAID", "{}"), "NO_MATCH", "RULE_DISABLED");
    var spec = (com.fasterxml.jackson.databind.node.ObjectNode) rule
      .spec()
      .deepCopy();
    spec.set(
      "conditions",
      json.tree(
        List.of(
          Map.of(
            "field",
            "data.paymentStatus",
            "operator",
            "EQUALS",
            "value",
            "PAID"
          )
        )
      )
    );
    store.save(
      Kind.RULES,
      Bootstrap.WORKSPACE,
      rule.id(),
      rule.code(),
      rule.name(),
      true,
      spec
    );
    diagnose(localEvent("ORDER_PAID", "{}"), "NO_MATCH", "CONDITIONS_NOT_MET");
    db.sql("UPDATE contact_groups SET enabled=false WHERE id=:id")
      .param("id", group)
      .update();
    UUID noRecipients = localEvent(
      "ORDER_PAID",
      "{\"paymentStatus\":\"PAID\"}"
    );
    diagnose(noRecipients, "NO_MATCH", "NO_ACTIVE_RECIPIENTS");
    // A second disabled rule must not hide a deeper recipient failure.
    rule("ORDER_PAID", "CONTACT_GROUP", group, List.of());
    db.sql(
      "UPDATE routing_rules SET enabled=false WHERE id<>:id AND spec->>'applicationId'=:a"
    )
      .param("id", rule.id())
      .param("a", app.toString())
      .update();
    diagnose(
      localEvent("ORDER_PAID", "{\"paymentStatus\":\"PAID\"}"),
      "NO_MATCH",
      "NO_ACTIVE_RECIPIENTS"
    );
    db.sql("UPDATE contact_groups SET enabled=true WHERE id=:id")
      .param("id", group)
      .update();
    db.sql("UPDATE contacts SET enabled=false WHERE id=:id")
      .param("id", contact)
      .update();
    diagnose(
      localEvent("ORDER_PAID", "{\"paymentStatus\":\"PAID\"}"),
      "NO_MATCH",
      "NO_ACTIVE_RECIPIENTS"
    );
    db.sql("UPDATE applications SET enabled=false WHERE id=:id")
      .param("id", app)
      .update();
    diagnose(localEvent("ORDER_PAID", "{}"), "SKIPPED", "APPLICATION_DISABLED");
    mvc
      .perform(
        get("/api/v1/admin/events/" + noRecipients).with(
          user("admin@mibess.com.br").roles("VIEWER")
        )
      )
      .andExpect(status().isOk())
      .andExpect(
        jsonPath("$.event.routing_diagnostics.rules[0].ruleId").value(
          rule.id().toString()
        )
      )
      .andExpect(
        jsonPath("$.event.routing_diagnostics.rules[0].targetId").value(
          group.toString()
        )
      )
      .andExpect(jsonPath("$.timeline[0].status").value("NO_MATCH"))
      .andExpect(jsonPath("$.event.idempotency_key").doesNotExist());
  }

  @Test
  @org.springframework.transaction.annotation.Transactional
  void reprocessUsesCurrentConfigurationIsAuditedAndNeverDuplicatesNotifications()
    throws Exception {
    UUID id = localEvent(
      "CRM_SYNC_RECOVERED",
      "{\"orderNumber\":\"reprocessed\"}"
    );
    diagnose(id, "NO_MATCH", "NO_RULE_FOR_EVENT_TYPE");
    rule("CRM_SYNC_RECOVERED", "CONTACT_GROUP", group, List.of());
    mvc
      .perform(
        post("/api/v1/admin/events/" + id + "/reprocess")
          .with(user("admin@mibess.com.br").roles("OPERATOR"))
          .with(csrf())
      )
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.event.status").value("PROCESSED"))
      .andExpect(jsonPath("$.timeline[1].status").value("REPROCESS_REQUESTED"))
      .andExpect(jsonPath("$.notifications.length()").value(1));
    mvc
      .perform(
        post("/api/v1/admin/events/" + id + "/reprocess")
          .with(user("admin@mibess.com.br").roles("ADMIN"))
          .with(csrf())
      )
      .andExpect(status().isConflict());
    // Even a previously partially processed event cannot insert the same recipient twice.
    db.sql("UPDATE events SET status='NO_MATCH' WHERE id=:id")
      .param("id", id)
      .update();
    mvc
      .perform(
        post("/api/v1/admin/events/" + id + "/reprocess")
          .with(user("admin@mibess.com.br").roles("ADMIN"))
          .with(csrf())
      )
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.event.status").value("PROCESSED"));
    assertThat(
      db
        .sql("SELECT count(*) FROM notifications WHERE event_id=:id")
        .param("id", id)
        .query(Integer.class)
        .single()
    ).isEqualTo(1);
    assertThat(
      db
        .sql(
          "SELECT count(*) FROM audit_logs WHERE resource_id=:id AND action='EVENT_REPROCESSED'"
        )
        .param("id", id)
        .query(Integer.class)
        .single()
    ).isEqualTo(2);
    db.sql("UPDATE applications SET enabled=false WHERE id=:id")
      .param("id", app)
      .update();
    UUID skipped = localEvent("ORDER_PAID", "{\"orderNumber\":\"skipped\"}");
    diagnose(skipped, "SKIPPED", "APPLICATION_DISABLED");
    db.sql("UPDATE applications SET enabled=true WHERE id=:id")
      .param("id", app)
      .update();
    mvc
      .perform(
        post("/api/v1/admin/events/" + skipped + "/reprocess")
          .with(user("admin@mibess.com.br").roles("ADMIN"))
          .with(csrf())
      )
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.event.status").value("PROCESSED"));
  }

  @Test
  @org.springframework.transaction.annotation.Transactional
  void multiTypeRuleValidatesAndRoutesPrefixesWithSpecificPriorities()
    throws Exception {
    var rule = currentRule();
    var spec = (com.fasterxml.jackson.databind.node.ObjectNode) rule
      .spec()
      .deepCopy();
    spec.remove("eventType");
    spec
      .putArray("eventTypes")
      .add("CRM_SYNC_FAILED")
      .add("CRM_SYNC_RECOVERED")
      .add("CRM_ORDER_*");
    spec.putObject("eventPriorities").put("CRM_SYNC_FAILED", "HIGH");
    spec.put("priority", "NORMAL");
    var input = json
      .object()
      .put("code", rule.code())
      .put("name", rule.name())
      .put("enabled", true);
    input.set("spec", spec);
    mvc
      .perform(
        put("/api/v1/admin/rules/" + rule.id())
          .with(user("admin@mibess.com.br").roles("ADMIN"))
          .with(csrf())
          .contentType("application/json")
          .content(json.write(input))
      )
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.spec.eventType").value("CRM_SYNC_FAILED"));
    for (String type : List.of(
      "CRM_SYNC_FAILED",
      "CRM_SYNC_RECOVERED",
      "CRM_ORDER_PAID"
    )) {
      UUID id = localEvent(type, "{\"orderNumber\":\"multi\"}");
      routingEngine.route(id.toString());
      assertThat(
        db
          .sql("SELECT priority FROM notifications WHERE event_id=:id")
          .param("id", id)
          .query(String.class)
          .single()
      ).isEqualTo(type.equals("CRM_SYNC_FAILED") ? "HIGH" : "NORMAL");
    }
    diagnose(
      localEvent("CRM_NOT_COVERED", "{}"),
      "NO_MATCH",
      "NO_RULE_FOR_EVENT_TYPE"
    );
    spec.putObject("eventPriorities").put("OUTSIDE_TYPE", "HIGH");
    mvc
      .perform(
        put("/api/v1/admin/rules/" + rule.id())
          .with(user("admin@mibess.com.br").roles("ADMIN"))
          .with(csrf())
          .contentType("application/json")
          .content(json.write(input))
      )
      .andExpect(status().isBadRequest());
  }

  @Test
  @org.springframework.transaction.annotation.Transactional
  void simulationUsesLatestEventReturnsProblemsAndCannotSend()
    throws Exception {
    UUID id = localEvent(
      "ORDER_PAID",
      "{\"orderNumber\":\"simulated\",\"optional\":null}"
    );
    var rule = currentRule();
    long before = db
      .sql("SELECT count(*) FROM notifications")
      .query(Long.class)
      .single();
    mvc
      .perform(
        post("/api/v1/admin/rules/" + rule.id() + "/test")
          .with(user("admin@mibess.com.br").roles("OPERATOR"))
          .with(csrf())
      )
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.eventId").value(id.toString()))
      .andExpect(jsonPath("$.renderedMessage").value("Pedido simulated"))
      .andExpect(
        jsonPath("$.recipients[0].contactId").value(contact.toString())
      )
      .andExpect(jsonPath("$.problems.length()").value(0));
    db.sql(
      "UPDATE templates SET spec=spec || '{\"status\":\"DRAFT\"}'::jsonb WHERE id=:id"
    )
      .param("id", template)
      .update();
    db.sql("UPDATE channel_connections SET enabled=false WHERE id=:id")
      .param("id", channel)
      .update();
    db.sql(
      "UPDATE contacts SET spec=spec || '{\"whatsappOptIn\":false}'::jsonb WHERE id=:id"
    )
      .param("id", contact)
      .update();
    String response = mvc
      .perform(
        post("/api/v1/admin/rules/" + rule.id() + "/test")
          .with(user("admin@mibess.com.br").roles("ADMIN"))
          .with(csrf())
          .contentType("application/json")
          .content("{\"data\":{}}")
      )
      .andExpect(status().isOk())
      .andReturn()
      .getResponse()
      .getContentAsString();
    assertThat(response).contains(
      "CHANNEL_DISABLED",
      "TEMPLATE_NOT_APPROVED",
      "CONSENT_REQUIRED",
      "MISSING_TEMPLATE_VARIABLE"
    );
    assertThat(
      db.sql("SELECT count(*) FROM notifications").query(Long.class).single()
    ).isEqualTo(before);
    mvc
      .perform(
        get("/api/v1/admin/applications/" + app + "/event-catalog").with(
          user("admin@mibess.com.br").roles("VIEWER")
        )
      )
      .andExpect(status().isOk())
      .andExpect(jsonPath("$[0].eventType").value("ORDER_PAID"))
      .andExpect(jsonPath("$[0].dataFields[0]").value("optional"))
      .andExpect(jsonPath("$[0].sampleData.orderNumber").value("simulated"));
  }

  @Test
  @org.springframework.transaction.annotation.Transactional
  void integrationHealthDetectsMissingRuleEmptyGroupTemplateAndChannel()
    throws Exception {
    diagnose(
      localEvent("CRM_SYNC_RECOVERED", "{}"),
      "NO_MATCH",
      "NO_RULE_FOR_EVENT_TYPE"
    );
    db.sql(
      "UPDATE contact_groups SET spec='{\"members\":[]}'::jsonb WHERE id=:id"
    )
      .param("id", group)
      .update();
    db.sql(
      "UPDATE templates SET spec=spec || '{\"status\":\"DRAFT\"}'::jsonb WHERE id=:id"
    )
      .param("id", template)
      .update();
    db.sql("UPDATE channel_connections SET enabled=false WHERE id=:id")
      .param("id", channel)
      .update();
    String response = mvc
      .perform(
        get("/api/v1/admin/applications/" + app + "/health").with(
          user("admin@mibess.com.br").roles("VIEWER")
        )
      )
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.noMatch24h").value(1))
      .andReturn()
      .getResponse()
      .getContentAsString();
    assertThat(response).contains(
      "EVENT_TYPE_WITHOUT_RULE",
      "ACTIVE_GROUP_MEMBERS",
      "TEMPLATE_APPROVED",
      "CHANNEL_CONNECTION",
      "ERROR"
    );
    mvc
      .perform(
        get("/api/v1/admin/dashboard?applicationId=" + app).with(
          user("admin@mibess.com.br").roles("VIEWER")
        )
      )
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.NO_MATCH").value(1));
  }

  @Test
  @org.springframework.transaction.annotation.Transactional
  void integrationMutationsEnforceRolesCsrfAndWorkspace() throws Exception {
    UUID id = localEvent("CRM_TEST", "{}");
    diagnose(id, "NO_MATCH", "NO_RULE_FOR_EVENT_TYPE");
    for (String url : List.of(
      "/api/v1/admin/events/" + id + "/reprocess",
      "/api/v1/admin/rules/" + currentRule().id() + "/test"
    )) {
      mvc
        .perform(post(url).with(user("admin@mibess.com.br").roles("ADMIN")))
        .andExpect(status().isForbidden());
      mvc
        .perform(
          post(url)
            .with(user("admin@mibess.com.br").roles("VIEWER"))
            .with(csrf())
        )
        .andExpect(status().isForbidden());
      mvc.perform(post(url).with(csrf())).andExpect(status().isUnauthorized());
    }
    UUID foreign = UUID.randomUUID(),
      foreignApp = UUID.randomUUID();
    db.sql("INSERT INTO workspaces(id,name) VALUES(:id,'Other')")
      .param("id", foreign)
      .update();
    store.save(
      Kind.APPLICATIONS,
      foreign,
      foreignApp,
      "FOREIGN",
      "Foreign",
      true,
      json.object()
    );
    UUID foreignEvent = eventService
      .accept(
        new ApiKeyService.Application(foreignApp, foreign),
        "foreign",
        json.read("{\"type\":\"FOREIGN_EVENT\",\"data\":{}}")
      )
      .eventId();
    for (String url : List.of(
      "/api/v1/admin/events/" + foreignEvent,
      "/api/v1/admin/applications/" + foreignApp + "/event-catalog",
      "/api/v1/admin/applications/" + foreignApp + "/health"
    ))
      mvc
        .perform(get(url).with(user("admin@mibess.com.br").roles("ADMIN")))
        .andExpect(status().isNotFound());
    mvc
      .perform(
        post("/api/v1/admin/events/" + foreignEvent + "/reprocess")
          .with(user("admin@mibess.com.br").roles("ADMIN"))
          .with(csrf())
      )
      .andExpect(status().isNotFound());
  }

  @Test
  void concurrentReprocessingLocksEventAndCreatesExactlyOneNotification()
    throws Exception {
    UUID id = localEvent("CRM_REPROCESS_RACE", "{\"orderNumber\":\"race\"}");
    await()
      .atMost(Duration.ofSeconds(20))
      .untilAsserted(() ->
        assertThat(
          db
            .sql("SELECT status FROM events WHERE id=:id")
            .param("id", id)
            .query(String.class)
            .single()
        ).isEqualTo("NO_MATCH")
      );
    rule("CRM_REPROCESS_RACE", "CONTACT_GROUP", group, List.of());
    try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      var start = new java.util.concurrent.CountDownLatch(1);
      List<java.util.concurrent.Callable<Integer>> tasks = new ArrayList<>();
      for (int i = 0; i < 2; i++) tasks.add(() -> {
        start.await();
        return mvc
          .perform(
            post("/api/v1/admin/events/" + id + "/reprocess")
              .with(user("admin@mibess.com.br").roles("OPERATOR"))
              .with(csrf())
          )
          .andReturn()
          .getResponse()
          .getStatus();
      });
      var first = executor.submit(tasks.get(0));
      var second = executor.submit(tasks.get(1));
      start.countDown();
      assertThat(List.of(first.get(), second.get())).containsExactlyInAnyOrder(
        200,
        409
      );
    }
    assertThat(
      db
        .sql("SELECT count(*) FROM notifications WHERE event_id=:id")
        .param("id", id)
        .query(Integer.class)
        .single()
    ).isEqualTo(1);
    assertThat(
      db
        .sql(
          "SELECT count(*) FROM audit_logs WHERE resource_id=:id AND action='EVENT_REPROCESSED'"
        )
        .param("id", id)
        .query(Integer.class)
        .single()
    ).isEqualTo(1);
    await()
      .atMost(Duration.ofSeconds(20))
      .untilAsserted(() ->
        assertThat(
          db
            .sql("SELECT status FROM notifications WHERE event_id=:id")
            .param("id", id)
            .query(String.class)
            .single()
        ).isEqualTo("SENT")
      );
  }

  @Test
  @org.springframework.transaction.annotation.Transactional
  void manualRequestLookupOnlyReturnsTheCurrentAuthorsWorkspaceRequest()
    throws Exception {
    var input = reviewManual(manualInput());
    UUID notification = sendManual(input);
    String path =
      "/api/v1/admin/notifications/manual/requests/" +
      input.path("requestId").asText();
    mvc
      .perform(get(path).with(user("admin@mibess.com.br").roles("OPERATOR")))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.notificationId").value(notification.toString()));
    mvc
      .perform(get(path).with(user("admin@mibess.com.br").roles("VIEWER")))
      .andExpect(status().isForbidden());
    db.sql(
      "UPDATE notifications SET created_by='other-author@example.test' WHERE id=:id"
    )
      .param("id", notification)
      .update();
    mvc
      .perform(get(path).with(user("admin@mibess.com.br").roles("ADMIN")))
      .andExpect(status().isNotFound());
  }

  @Test
  @org.springframework.transaction.annotation.Transactional
  void catalogAndSimulationChooseLastReceivedPayloadForEachMatchingType()
    throws Exception {
    UUID older = localEvent(
      "ORDER_PAID",
      "{\"orderNumber\":\"old\",\"oldField\":1}"
    );
    db.sql("UPDATE events SET created_at=now()-interval '1 hour' WHERE id=:id")
      .param("id", older)
      .update();
    UUID latest = localEvent(
      "ORDER_PAID",
      "{\"orderNumber\":\"new\",\"customer\":{\"name\":\"Ana\"}}"
    );
    mvc
      .perform(
        get("/api/v1/admin/applications/" + app + "/event-catalog").with(
          user("admin@mibess.com.br").roles("VIEWER")
        )
      )
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.length()").value(1))
      .andExpect(jsonPath("$[0].eventId").value(latest.toString()))
      .andExpect(
        jsonPath("$[0].dataFields").value(
          org.hamcrest.Matchers.hasItem("customer.name")
        )
      )
      .andExpect(jsonPath("$[0].sampleData.oldField").doesNotExist());
    mvc
      .perform(
        post("/api/v1/admin/rules/" + currentRule().id() + "/test")
          .with(user("admin@mibess.com.br").roles("OPERATOR"))
          .with(csrf())
      )
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.eventId").value(latest.toString()))
      .andExpect(jsonPath("$.renderedMessage").value("Pedido new"));
  }

  @Autowired
  com.mibess.notify.event.EventService eventService;
}
