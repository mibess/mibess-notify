package com.mibess.notify.event;

import java.time.Instant;
import java.util.UUID;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import com.fasterxml.jackson.databind.JsonNode;

@Entity @Table(name="events")
public class EventEntity {
    @Id public UUID id;
    public UUID workspaceId;
    public UUID applicationId;
    public String type;
    public String correlationId;
    public String idempotencyKey;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(columnDefinition="char(64)") public String payloadHash;
    @JdbcTypeCode(SqlTypes.JSON) public JsonNode payload;
    public String status;
    public String errorCode;
    public int duplicateCount;
    public Instant createdAt;
    public Instant processedAt;
}
