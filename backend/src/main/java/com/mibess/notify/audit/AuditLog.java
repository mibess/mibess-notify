package com.mibess.notify.audit;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class AuditLog {

  private final JdbcClient db;

  public AuditLog(JdbcClient db) {
    this.db = db;
  }

  public void record(
    UUID workspace,
    String actor,
    String action,
    UUID resource
  ) {
    db.sql(
      "INSERT INTO audit_logs(workspace_id,actor,action,resource_id) VALUES(:w,:a,:action,:id)"
    )
      .param("w", workspace)
      .param("a", actor)
      .param("action", action)
      .param("id", resource)
      .update();
  }
}
