package com.mibess.notify.contact;

import java.util.*;
import java.time.Instant;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import com.fasterxml.jackson.databind.JsonNode;

@Service
public class ConsentService {
    private final JdbcClient db;
    public ConsentService(JdbcClient db) { this.db=db; }
    public static String phone(String raw) {
        String normalized=raw==null?"":raw.replaceAll("[ ()\\-.]","");
        if(!normalized.matches("\\+[1-9][0-9]{7,14}")) throw new IllegalArgumentException("Informe telefone internacional E.164, por exemplo +5516999999999");
        return normalized;
    }
    public static boolean validOptIn(JsonNode spec) {
        if(!spec.path("whatsappOptIn").asBoolean(false) || !spec.path("whatsappOptOutAt").asText("").isBlank() || spec.path("whatsappOptInSource").asText("").isBlank()) return false;
        try { return !Instant.parse(spec.path("whatsappOptInAt").asText()).isAfter(Instant.now().plusSeconds(60)); } catch(Exception e) { return false; }
    }
    public boolean suppressed(UUID workspace,String address) {
        return db.sql("SELECT EXISTS(SELECT 1 FROM suppressions WHERE workspace_id=:w AND address=:a AND channel='WHATSAPP')").param("w",workspace).param("a",address).query(Boolean.class).single();
    }
}
