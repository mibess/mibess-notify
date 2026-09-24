package com.mibess.notify.notification;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.nio.charset.StandardCharsets;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class OutboxPublisher {
    private final JdbcClient db;private final RabbitTemplate rabbit;private final TransactionTemplate tx;
    public OutboxPublisher(JdbcClient db,RabbitTemplate rabbit,TransactionTemplate tx) {this.db=db;this.rabbit=rabbit;this.tx=tx;}
    @Scheduled(fixedDelayString="${notify.outbox-delay:1000}") public void publish() {
        tx.executeWithoutResult(status->{
            var rows=db.sql("SELECT * FROM outbox WHERE published_at IS NULL ORDER BY created_at LIMIT 50 FOR UPDATE SKIP LOCKED").query().listOfRows();
            for(var row:rows) {
                var correlation=new CorrelationData(row.get("id").toString());
                MessageProperties p=new MessageProperties();p.setDeliveryMode(MessageDeliveryMode.PERSISTENT);p.setContentType("text/plain");p.setMessageId(row.get("id").toString());p.setPriority(((Number)row.get("priority")).intValue());
                try {
                    rabbit.send("notify",row.get("topic").toString(),new Message(row.get("aggregate_id").toString().getBytes(StandardCharsets.UTF_8),p),correlation);
                    var confirm=correlation.getFuture().get(5,TimeUnit.SECONDS);
                    if(!confirm.isAck()||correlation.getReturned()!=null)break;
                    db.sql("UPDATE outbox SET published_at=now() WHERE id=:id").param("id",row.get("id")).update();
                }catch(Exception e){break;}
            }
        });
    }
}
