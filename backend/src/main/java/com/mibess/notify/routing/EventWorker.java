package com.mibess.notify.routing;

import java.util.UUID;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class EventWorker {

  private final RoutingEngine engine;
  private final JdbcClient db;

  public EventWorker(RoutingEngine engine, JdbcClient db) {
    this.engine = engine;
    this.db = db;
  }

  @RabbitListener(queues = "notify.events")
  public void consume(String id) {
    try {
      engine.route(id);
    } catch (IllegalArgumentException | ResponseStatusException e) {
      db.sql(
        "UPDATE events SET status='FAILED',error_code='ROUTING_CONFIGURATION_OR_RECIPIENT_INVALID',processed_at=now() WHERE id=:id AND status='ACCEPTED'"
      )
        .param("id", UUID.fromString(id))
        .update();
      throw new AmqpRejectAndDontRequeueException(
        "Evento rejeitado; consultar status no painel"
      );
    }
  }
}
