package com.mibess.notify.auth;

import com.mibess.notify.audit.AuditLog;
import com.mibess.notify.workspace.WorkspaceAccess;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/users")
@PreAuthorize("hasRole('ADMIN')")
public class UserController {

  private final JdbcClient db;
  private final WorkspaceAccess access;
  private final PasswordEncoder encoder;
  private final AuditLog audit;

  public UserController(
    JdbcClient db,
    WorkspaceAccess access,
    PasswordEncoder encoder,
    AuditLog audit
  ) {
    this.db = db;
    this.access = access;
    this.encoder = encoder;
    this.audit = audit;
  }

  @GetMapping
  public List<Map<String, Object>> list() {
    return db
      .sql(
        "SELECT u.id,u.email,u.enabled,m.role FROM users u JOIN workspace_memberships m ON m.user_id=u.id WHERE m.workspace_id=:w ORDER BY u.email"
      )
      .param("w", access.id())
      .query()
      .listOfRows();
  }

  public record Input(
    @Email @NotBlank String email,
    @NotBlank @Size(min = 14, max = 100) String password,
    @Pattern(regexp = "ADMIN|OPERATOR|VIEWER") @NotNull String role
  ) {}

  @PostMapping
  @Transactional
  public void create(@Valid @RequestBody Input input) {
    UUID id = UUID.randomUUID();
    db.sql("INSERT INTO users(id,email,password_hash) VALUES(:id,:e,:p)")
      .param("id", id)
      .param("e", input.email().toLowerCase())
      .param("p", encoder.encode(input.password()))
      .update();
    db.sql(
      "INSERT INTO workspace_memberships(user_id,workspace_id,role) VALUES(:id,:w,:r)"
    )
      .param("id", id)
      .param("w", access.id())
      .param("r", input.role())
      .update();
    audit.record(access.id(), access.actor(), "USER_CREATED", id);
  }
}
