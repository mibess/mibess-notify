package com.mibess.notify.event;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "events")
public class EventEntity {

  @Id
  public UUID id;

  public UUID workspaceId;
  public UUID applicationId;
  public String type;
  public String correlationId;
  public String idempotencyKey;

  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(columnDefinition = "char(64)")
  public String payloadHash;

  @JdbcTypeCode(SqlTypes.JSON)
  public JsonNode payload;

  public String status;
  public String errorCode;
  public int duplicateCount;
  public Instant createdAt;
  public Instant processedAt;
}
