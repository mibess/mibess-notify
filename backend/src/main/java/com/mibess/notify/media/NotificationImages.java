package com.mibess.notify.media;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mibess.notify.channel.ChannelProvider.DeliveryFailure;
import com.mibess.notify.shared.ResourceStore.Resource;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class NotificationImages {

  public record Policy(
    boolean configured,
    int width,
    int height,
    int maxBytes,
    List<String> contentTypes
  ) {}

  public record Image(
    UUID id,
    String contentType,
    int width,
    int height,
    int byteSize,
    String previewUrl
  ) {}

  private final JdbcClient db;
  private final ImageStorage storage;
  private final String prefix;
  private final int width, height, maxBytes;

  public NotificationImages(
    JdbcClient db,
    ImageStorage storage,
    @Value("${notify.images.prefix:mibess-notify/dev}") String prefix,
    @Value("${notify.images.width:960}") int width,
    @Value("${notify.images.height:504}") int height,
    @Value("${notify.images.max-bytes:500000}") int maxBytes
  ) {
    if (
      !prefix.matches("[A-Za-z0-9][A-Za-z0-9/_-]{0,150}") ||
      width < 100 ||
      width > 4096 ||
      height < 100 ||
      height > 4096 ||
      maxBytes < 1000 ||
      maxBytes > 5_000_000
    ) throw new IllegalArgumentException("Política de imagens inválida");
    this.db = db;
    this.storage = storage;
    this.prefix = prefix.replaceAll("/+$", "");
    this.width = width;
    this.height = height;
    this.maxBytes = maxBytes;
  }

  public Policy policy() {
    return new Policy(
      storage.configured(),
      width,
      height,
      maxBytes,
      List.of("image/jpeg", "image/png")
    );
  }

  public Image upload(UUID workspace, byte[] bytes, String type) {
    var valid = ImageRules.validate(bytes, type, width, height, maxBytes);
    UUID id = UUID.randomUUID();
    String key =
      prefix +
      "/" +
      workspace +
      "/" +
      id +
      (valid.contentType().equals("image/png") ? ".png" : ".jpg");
    storage.put(key, bytes, valid.contentType());
    db.sql(
      "INSERT INTO notification_images(id,workspace_id,object_key,content_type,byte_size,width,height) VALUES(:id,:w,:key,:type,:size,:width,:height)"
    )
      .param("id", id)
      .param("w", workspace)
      .param("key", key)
      .param("type", valid.contentType())
      .param("size", bytes.length)
      .param("width", width)
      .param("height", height)
      .update();
    return get(workspace, id);
  }

  public Image standard(UUID workspace, String name) {
    return upload(
      workspace,
      StandardImage.create(name, width, height),
      "image/jpeg"
    );
  }

  private Map<String, Object> row(UUID workspace, UUID id) {
    return db
      .sql("SELECT * FROM notification_images WHERE workspace_id=:w AND id=:id")
      .param("w", workspace)
      .param("id", id)
      .query()
      .listOfRows()
      .stream()
      .findFirst()
      .orElseThrow(() ->
        new ResponseStatusException(
          HttpStatus.NOT_FOUND,
          "Imagem não encontrada neste workspace."
        )
      );
  }

  public Image get(UUID workspace, UUID id) {
    var row = row(workspace, id);
    return new Image(
      id,
      row.get("content_type").toString(),
      ((Number) row.get("width")).intValue(),
      ((Number) row.get("height")).intValue(),
      ((Number) row.get("byte_size")).intValue(),
      "/api/v1/admin/images/" + id + "/content"
    );
  }

  public byte[] content(UUID workspace, UUID id) {
    return storage.read(row(workspace, id).get("object_key").toString());
  }

  public void validateReference(UUID workspace, JsonNode spec) {
    if (!spec.path("imageId").asText().isBlank()) get(
      workspace,
      UUID.fromString(spec.path("imageId").asText())
    );
  }

  /** Resolve identity at creation; the immutable image ID, never an expiring URL, is persisted. */
  public void attach(
    ObjectNode command,
    UUID workspace,
    Resource template,
    Resource application,
    String provider,
    UUID override
  ) {
    JsonNode spec = template == null ? null : template.spec();
    boolean meta = provider.equals("WHATSAPP_META");
    boolean header =
      spec != null && spec.path("headerType").asText("NONE").equals("IMAGE");
    if (meta && !header) {
      if (override != null) throw new IllegalArgumentException(
        "Este template Meta não possui cabeçalho de imagem aprovado."
      );
      return;
    }
    String image = override == null ? "" : override.toString();
    if (
      override == null &&
      spec != null &&
      !spec.path("imageDisabled").asBoolean()
    ) {
      image = spec.path("imageId").asText();
      if (image.isBlank() && application != null) image = application
        .spec()
        .path("imageId")
        .asText();
    }
    if (meta && image.isBlank()) throw new IllegalArgumentException(
      "O template Meta exige uma imagem para o cabeçalho."
    );
    if (image.isBlank()) return;
    get(workspace, UUID.fromString(image));
    if (
      !meta && command.path("text").asText().length() > 1024
    ) throw new IllegalArgumentException(
      "Mensagens com imagem aceitam até 1024 caracteres na legenda."
    );
    command.put("imageId", image);
  }

  /** Renew on every delivery attempt; no signed URLs are retained in history or logs. */
  public JsonNode forDelivery(UUID workspace, JsonNode command) {
    if (command.path("imageId").asText().isBlank()) return command;
    try {
      var row = row(
        workspace,
        UUID.fromString(command.path("imageId").asText())
      );
      ObjectNode copy = command.deepCopy();
      copy.put(
        "imageUrl",
        storage.deliveryUrl(row.get("object_key").toString())
      );
      copy.put("imageContentType", row.get("content_type").toString());
      return copy;
    } catch (Exception e) {
      throw new DeliveryFailure("IMAGE_STORAGE_UNAVAILABLE", true, false, 0);
    }
  }
}
