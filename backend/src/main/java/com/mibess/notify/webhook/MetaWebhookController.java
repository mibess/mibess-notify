package com.mibess.notify.webhook;

import java.util.*;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import com.mibess.notify.shared.*;
import com.mibess.notify.shared.ResourceStore.*;
import com.mibess.notify.whatsapp.WhatsAppMetaCloudProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/webhooks/meta/whatsapp")
public class MetaWebhookController {
    private final JdbcClient db;private final ResourceStore store;private final WhatsAppMetaCloudProvider meta;private final WebhookService webhooks;private final Json json;
    public MetaWebhookController(JdbcClient db,ResourceStore store,WhatsAppMetaCloudProvider meta,WebhookService webhooks,Json json){this.db=db;this.store=store;this.meta=meta;this.webhooks=webhooks;this.json=json;}
    private List<Resource> connections() {
        return db.sql("SELECT id,workspace_id FROM channel_connections WHERE enabled AND spec->>'provider'='WHATSAPP_META'").query((r,n)->store.get(Kind.CHANNELS,r.getObject("workspace_id",UUID.class),r.getObject("id",UUID.class))).list();
    }
    @GetMapping(produces=MediaType.TEXT_PLAIN_VALUE) public String verify(@RequestParam("hub.mode") String mode,@RequestParam("hub.verify_token") String token,@RequestParam("hub.challenge") String challenge) {
        if(mode.equals("subscribe") && token.length()<=500 && challenge.length()<=1000)for(var c:connections())if(MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8),meta.credentials(c).path("verifyToken").asText().getBytes(StandardCharsets.UTF_8)))return challenge;
        throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Verificação inválida");
    }
    @PostMapping public ResponseEntity<Void> receive(@RequestHeader(value="X-Hub-Signature-256",required=false) String signature,@RequestBody byte[] bytes) {
        if(bytes.length>262144)throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE);
        var body=json.read(new String(bytes,StandardCharsets.UTF_8));
        if(!body.path("object").asText().equals("whatsapp_business_account"))throw new IllegalArgumentException("Objeto de webhook inválido");
        List<Resource> signed=connections().stream().filter(c->Crypto.signature(bytes,meta.credentials(c).path("appSecret").asText(),signature)).toList();
        if(signed.isEmpty())throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Assinatura inválida");
        for(var entry:body.path("entry"))for(var change:entry.path("changes")) {
            var value=change.path("value");String phoneId=value.path("metadata").path("phone_number_id").asText();
            for(var c:signed)if(c.spec().path("phoneNumberId").asText().equals(phoneId))webhooks.accept(c,value,Crypto.hash(c.id()+":"+json.write(value)));
        }
        return ResponseEntity.ok().build();
    }
}
