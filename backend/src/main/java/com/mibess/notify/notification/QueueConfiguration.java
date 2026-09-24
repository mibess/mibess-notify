package com.mibess.notify.notification;

import java.util.*;
import org.springframework.amqp.core.*;
import org.springframework.context.annotation.*;

@Configuration
public class QueueConfiguration {

  @Bean
  public Declarables queues() {
    List<Declarable> items = new ArrayList<>();
    items.add(new DirectExchange("notify", true, false));
    items.add(new DirectExchange("notify.dead", true, false));
    items.add(QueueBuilder.durable("notify.dead-letter").build());
    items.add(
      new Binding(
        "notify.dead-letter",
        Binding.DestinationType.QUEUE,
        "notify.dead",
        "dead",
        null
      )
    );
    for (String name : List.of(
      "notify.events",
      "notify.deliveries",
      "notify.webhooks"
    )) {
      items.add(
        QueueBuilder.durable(name)
          .maxPriority(4)
          .deadLetterExchange("notify.dead")
          .deadLetterRoutingKey("dead")
          .build()
      );
      items.add(
        new Binding(name, Binding.DestinationType.QUEUE, "notify", name, null)
      );
    }
    return new Declarables(items);
  }
}
