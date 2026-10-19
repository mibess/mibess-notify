package com.mibess.notify;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mibess.notify.media.*;
import com.mibess.notify.shared.*;
import com.mibess.notify.shared.ResourceStore.Resource;
import com.mibess.notify.whatsapp.WhatsAppMetaCloudProvider;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.server.ResponseStatusException;

class NotificationImagesTest {

  final Json json = new Json(new ObjectMapper());
  final UUID workspace = UUID.randomUUID(),
    imageId = UUID.randomUUID();
  final ImageStorage storage = mock(ImageStorage.class);
  final JdbcClient db = mock(JdbcClient.class, RETURNS_DEEP_STUBS);
  final NotificationImages images = spy(
    new NotificationImages(db, storage, "notify/test", 960, 504, 500000)
  );

  Resource resource(com.fasterxml.jackson.databind.JsonNode spec) {
    return new Resource(
      UUID.randomUUID(),
      workspace,
      "TEST",
      "Teste",
      true,
      spec,
      "",
      ""
    );
  }

  void existingImage() {
    doReturn(
      new NotificationImages.Image(
        imageId,
        "image/jpeg",
        960,
        504,
        1234,
        "/preview"
      )
    )
      .when(images)
      .get(workspace, imageId);
  }

  @Test
  void universalArtworkIsLightAndObeysTheSameUploadValidation() {
    byte[] bytes = StandardImage.create("ARTGIAN CRM", 960, 504);
    assertThat(bytes.length).isLessThan(100000);
    assertThat(
      ImageRules.validate(bytes, "image/jpeg", 960, 504, 500000).contentType()
    ).isEqualTo("image/jpeg");
    assertThatThrownBy(() ->
      ImageRules.validate(bytes, "image/png", 960, 504, 500000)
    ).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() ->
      ImageRules.validate(bytes, "image/jpeg", 1200, 630, 500000)
    ).hasMessageContaining("1200 × 630");
    assertThatThrownBy(() ->
      ImageRules.validate(new byte[500001], "image/jpeg", 960, 504, 500000)
    ).hasMessageContaining("500 KB");
    assertThatThrownBy(() ->
      ImageRules.validate("<svg/>".getBytes(), "image/png", 960, 504, 500000)
    ).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void resolvesApplicationIdentityAndAllowsTemplateOverrideOrTextOnly() {
    existingImage();
    var app = resource(json.object().put("imageId", imageId.toString()));
    var template = resource(json.object());
    var command = json.object().put("text", "Atualização");
    images.attach(
      command,
      workspace,
      template,
      app,
      "WHATSAPP_EVOLUTION",
      null
    );
    assertThat(command.path("imageId").asText()).isEqualTo(imageId.toString());
    command.remove("imageId");
    images.attach(
      command,
      workspace,
      resource(json.object().put("imageDisabled", true)),
      app,
      "WHATSAPP_EVOLUTION",
      null
    );
    assertThat(command.has("imageId")).isFalse();
    images.attach(
      command,
      workspace,
      resource(json.object().put("imageId", imageId.toString())),
      null,
      "WHATSAPP_EVOLUTION",
      null
    );
    assertThat(command.path("imageId").asText()).isEqualTo(imageId.toString());
  }

  @Test
  void metaTextTemplatesDoNotAccidentallyAcquireAnImageHeader() {
    existingImage();
    var app = resource(json.object().put("imageId", imageId.toString()));
    var command = json.object().put("text", "Atualização");
    images.attach(
      command,
      workspace,
      resource(json.object()),
      app,
      "WHATSAPP_META",
      null
    );
    assertThat(command.has("imageId")).isFalse();
    assertThatThrownBy(() ->
      images.attach(
        command,
        workspace,
        resource(json.object()),
        app,
        "WHATSAPP_META",
        imageId
      )
    ).hasMessageContaining("não possui cabeçalho");
    assertThatThrownBy(() ->
      images.attach(
        command,
        workspace,
        resource(json.object().put("headerType", "IMAGE")),
        null,
        "WHATSAPP_META",
        null
      )
    ).hasMessageContaining("exige uma imagem");
    images.attach(
      command,
      workspace,
      resource(json.object().put("headerType", "IMAGE")),
      app,
      "WHATSAPP_META",
      null
    );
    assertThat(command.path("imageId").asText()).isEqualTo(imageId.toString());
  }

  @Test
  void rejectsOversizedCaptionsAndImagesOutsideTheWorkspace() {
    existingImage();
    var template = resource(json.object().put("imageId", imageId.toString()));
    assertThatThrownBy(() ->
      images.attach(
        json.object().put("text", "a".repeat(1025)),
        workspace,
        template,
        null,
        "WHATSAPP_EVOLUTION",
        null
      )
    ).hasMessageContaining("1024");
    doThrow(
      new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND)
    )
      .when(images)
      .get(workspace, imageId);
    assertThatThrownBy(() ->
      images.attach(
        json.object().put("text", "ok"),
        workspace,
        template,
        null,
        "WHATSAPP_EVOLUTION",
        null
      )
    ).isInstanceOf(ResponseStatusException.class);
  }

  @Test
  void renewsSignedUrlsForEachAttemptWithoutMutatingThePersistedCommand() {
    when(
      db
        .sql(anyString())
        .param(eq("w"), any())
        .param(eq("id"), any())
        .query()
        .listOfRows()
    ).thenReturn(
      List.of(
        Map.of(
          "object_key",
          "notify/test/image.jpg",
          "content_type",
          "image/jpeg"
        )
      )
    );
    when(storage.deliveryUrl("notify/test/image.jpg")).thenReturn(
      "https://example.test/first",
      "https://example.test/renewed"
    );
    var command = json
      .object()
      .put("text", "ok")
      .put("imageId", imageId.toString());
    assertThat(
      images.forDelivery(workspace, command).path("imageUrl").asText()
    ).endsWith("first");
    assertThat(
      images.forDelivery(workspace, command).path("imageUrl").asText()
    ).endsWith("renewed");
    assertThat(command.has("imageUrl")).isFalse();
  }

  @Test
  void metaHeaderAndBodyHaveTheOfficialComponentShape() {
    var provider = new WhatsAppMetaCloudProvider(
      json,
      new Crypto("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="),
      "v26.0"
    );
    var message = json
      .object()
      .put("templateName", "aviso_com_imagem")
      .put("language", "pt_BR")
      .put("imageUrl", "https://example.test/image.jpg");
    message.putArray("parameters").add("Pedido confirmado");
    var template = provider.templatePayload(message);
    assertThat(
      template.path("components").path(0).path("type").asText()
    ).isEqualTo("header");
    assertThat(
      template
        .path("components")
        .path(0)
        .path("parameters")
        .path(0)
        .path("image")
        .path("link")
        .asText()
    ).isEqualTo("https://example.test/image.jpg");
    assertThat(
      template
        .path("components")
        .path(1)
        .path("parameters")
        .path(0)
        .path("text")
        .asText()
    ).isEqualTo("Pedido confirmado");
  }
}
