package com.mibess.notify.media;

import com.mibess.notify.audit.AuditLog;
import com.mibess.notify.workspace.WorkspaceAccess;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.io.IOException;
import java.util.UUID;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/admin/images")
public class ImageController {

  private final NotificationImages images;
  private final WorkspaceAccess access;
  private final AuditLog audit;

  public ImageController(
    NotificationImages images,
    WorkspaceAccess access,
    AuditLog audit
  ) {
    this.images = images;
    this.access = access;
    this.audit = audit;
  }

  @GetMapping("/policy")
  public NotificationImages.Policy policy() {
    return images.policy();
  }

  @GetMapping("/{id}")
  public NotificationImages.Image get(@PathVariable UUID id) {
    return images.get(access.id(), id);
  }

  @GetMapping("/{id}/content")
  public ResponseEntity<byte[]> content(@PathVariable UUID id) {
    var image = images.get(access.id(), id);
    return ResponseEntity.ok()
      .contentType(MediaType.parseMediaType(image.contentType()))
      .cacheControl(CacheControl.noStore())
      .body(images.content(access.id(), id));
  }

  @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
  public NotificationImages.Image upload(
    @RequestParam("file") MultipartFile file
  ) throws IOException {
    var result = images.upload(
      access.id(),
      file.getBytes(),
      file.getContentType()
    );
    audit.record(access.id(), access.actor(), "IMAGE_UPLOADED", result.id());
    return result;
  }

  public record Standard(@NotBlank @Size(max = 150) String name) {}

  @PostMapping("/standard")
  @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
  public NotificationImages.Image standard(@Valid @RequestBody Standard input) {
    var result = images.standard(access.id(), input.name().strip());
    audit.record(
      access.id(),
      access.actor(),
      "STANDARD_IMAGE_CREATED",
      result.id()
    );
    return result;
  }
}
