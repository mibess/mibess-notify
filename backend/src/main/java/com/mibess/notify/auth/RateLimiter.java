package com.mibess.notify.auth;

import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class RateLimiter {

  private final JdbcClient db;

  public RateLimiter(JdbcClient db) {
    this.db = db;
  }

  public void check(String subject, int limit) {
    int count = db
      .sql(
        "INSERT INTO rate_limits(subject,window_start,count) VALUES(:s,:w,1) ON CONFLICT(subject,window_start) DO UPDATE SET count=rate_limits.count+1 RETURNING count"
      )
      .param("s", subject)
      .param("w", Instant.now().getEpochSecond() / 60)
      .query(Integer.class)
      .single();
    if (count > limit) throw new ResponseStatusException(
      HttpStatus.TOO_MANY_REQUESTS,
      "Limite por minuto excedido. Tente novamente em 60 segundos."
    );
  }

  @Scheduled(fixedDelay = 3600000)
  public void cleanup() {
    db.sql("DELETE FROM rate_limits WHERE window_start<:w")
      .param("w", Instant.now().getEpochSecond() / 60 - 60)
      .update();
  }
}
