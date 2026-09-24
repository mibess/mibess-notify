package com.mibess.notify.application;

import java.util.*;
import com.mibess.notify.shared.ResourceStore;
import com.mibess.notify.shared.ResourceStore.Kind;
import com.mibess.notify.workspace.WorkspaceAccess;
import com.mibess.notify.audit.AuditLog;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/applications/{applicationId}/keys")
@PreAuthorize("hasRole('ADMIN')")
public class ApiKeyController {
    private final JdbcClient db;private final ApiKeyService keys;private final ResourceStore store;private final WorkspaceAccess access;private final AuditLog audit;
    public ApiKeyController(JdbcClient db,ApiKeyService keys,ResourceStore store,WorkspaceAccess access,AuditLog audit){this.db=db;this.keys=keys;this.store=store;this.access=access;this.audit=audit;}
    @GetMapping public List<Map<String,Object>> list(@PathVariable UUID applicationId) {store.get(Kind.APPLICATIONS,access.id(),applicationId);return db.sql("SELECT id,prefix,status,created_at,last_used_at,revoked_at FROM api_keys WHERE workspace_id=:w AND application_id=:a ORDER BY created_at DESC").param("w",access.id()).param("a",applicationId).query().listOfRows();}
    @PostMapping @Transactional public Map<String,Object> create(@PathVariable UUID applicationId,@RequestParam(defaultValue="false") boolean rotate) {store.get(Kind.APPLICATIONS,access.id(),applicationId);var result=keys.create(access.id(),applicationId,rotate);audit.record(access.id(),access.actor(),rotate?"API_KEY_ROTATED":"API_KEY_CREATED",applicationId);return result;}
    @DeleteMapping("/{id}") @Transactional public void revoke(@PathVariable UUID applicationId,@PathVariable UUID id) {db.sql("UPDATE api_keys SET status='REVOKED',revoked_at=now() WHERE id=:id AND workspace_id=:w AND application_id=:a").param("id",id).param("w",access.id()).param("a",applicationId).update();audit.record(access.id(),access.actor(),"API_KEY_REVOKED",id);}
}
