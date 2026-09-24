package com.mibess.notify.workspace;

import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class WorkspaceAccess {

  private final JdbcClient db;

  public WorkspaceAccess(JdbcClient db) {
    this.db = db;
  }

  public UUID id() {
    String email = actor();
    return db
      .sql(
        "SELECT m.workspace_id FROM workspace_memberships m JOIN users u ON u.id=m.user_id WHERE u.email=:email AND u.enabled ORDER BY m.workspace_id LIMIT 1"
      )
      .param("email", email)
      .query(UUID.class)
      .optional()
      .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN));
  }

  public String actor() {
    return SecurityContextHolder.getContext().getAuthentication().getName();
  }
}
