package com.mibess.notify.webhook;

import java.util.*;
import java.time.Instant;
import com.mibess.notify.shared.*;
import com.mibess.notify.shared.ResourceStore.Kind;
import com.mibess.notify.workspace.WorkspaceAccess;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@ConditionalOnProperty(name="notify.fake-provider",havingValue="true")
public class FakeWebhookController {
    private final JdbcClient db;private final ResourceStore store;private final WorkspaceAccess access;private final Json json;private final WebhookService webhooks;
    public FakeWebhookController(JdbcClient db,ResourceStore store,WorkspaceAccess access,Json json,WebhookService webhooks){this.db=db;this.store=store;this.access=access;this.json=json;this.webhooks=webhooks;}
    @PostMapping("/api/v1/admin/notifications/{id}/simulate-delivery") @PreAuthorize("hasRole('ADMIN')")
    public void simulate(@PathVariable UUID id) {
        var n=db.sql("SELECT channel_connection_id,provider_message_id FROM notifications WHERE id=:id AND workspace_id=:w AND status='SENT'").param("id",id).param("w",access.id()).query().listOfRows().stream().findFirst().orElseThrow(()->new IllegalArgumentException("Notificação deve estar SENT"));
        var c=store.get(Kind.CHANNELS,access.id(),(UUID)n.get("channel_connection_id"));if(!c.spec().path("provider").asText().equals("FAKE"))throw new IllegalArgumentException("Simulação exige provider fake");
        var value=json.tree(Map.of("statuses",List.of(Map.of("id",n.get("provider_message_id"),"status","delivered","timestamp",String.valueOf(Instant.now().getEpochSecond())))));
        webhooks.accept(c,value,Crypto.hash(json.write(value)));
    }
}
