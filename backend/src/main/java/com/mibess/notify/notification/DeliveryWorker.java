package com.mibess.notify.notification;

import java.util.*;
import java.time.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.mibess.notify.shared.*;
import com.mibess.notify.shared.ResourceStore.*;
import com.mibess.notify.contact.ConsentService;
import com.mibess.notify.channel.*;
import com.mibess.notify.channel.ChannelProvider.*;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.slf4j.*;

@Service
public class DeliveryWorker {
    private final JdbcClient db;private final Json json;private final ResourceStore store;private final ConsentService consent;private final ProviderRegistry providers;private final RetryPolicy retry;private final TransactionTemplate tx;
    private static final Logger LOG=LoggerFactory.getLogger(DeliveryWorker.class);
    public DeliveryWorker(JdbcClient db,Json json,ResourceStore store,ConsentService consent,ProviderRegistry providers,RetryPolicy retry,TransactionTemplate tx){this.db=db;this.json=json;this.store=store;this.consent=consent;this.providers=providers;this.retry=retry;this.tx=tx;}
    @Scheduled(fixedDelayString="${notify.dispatch-delay:1000}") public void enqueue() {
        tx.executeWithoutResult(status->{
            var rows=db.sql("SELECT id,priority FROM notifications WHERE status='PENDING' AND available_at<=now() ORDER BY available_at LIMIT 100 FOR UPDATE SKIP LOCKED").query().listOfRows();
            for(var row:rows){UUID id=(UUID)row.get("id");db.sql("UPDATE notifications SET status='QUEUED',queued_at=now() WHERE id=:id").param("id",id).update();timeline(id,"QUEUED","Entrou na fila");db.sql("INSERT INTO outbox(id,topic,aggregate_id,priority) VALUES(:o,'notify.deliveries',:id,:p)").param("o",UUID.randomUUID()).param("id",id).param("p",priority(row.get("priority").toString())).update();}
            // No automatic resend after a worker crash: the provider may already have accepted it.
            var stale=db.sql("UPDATE notifications SET status='FAILED',failed_at=now(),error_code='DELIVERY_UNKNOWN' WHERE status='PROCESSING' AND locked_at<now()-interval '5 minutes' RETURNING id").query(UUID.class).list();
            for(UUID id:stale){timeline(id,"FAILED","Resultado incerto após interrupção; reconciliar antes de reenviar");db.sql("UPDATE delivery_attempts SET status='UNKNOWN',finished_at=now(),error_code='DELIVERY_UNKNOWN' WHERE notification_id=:id AND status='PROCESSING'").param("id",id).update();}
        });
    }
    @RabbitListener(queues="notify.deliveries") public void deliver(String message) {
        UUID id=UUID.fromString(message);
        Map<String,Object> row=tx.execute(status->{
            var rows=db.sql("SELECT * FROM notifications WHERE id=:id AND status='QUEUED' FOR UPDATE").param("id",id).query().listOfRows();if(rows.isEmpty())return null;var n=rows.getFirst();
            UUID w=(UUID)n.get("workspace_id");JsonNode cmd=json.read(n.get("command").toString());var channel=store.get(Kind.CHANNELS,w,(UUID)n.get("channel_connection_id"));
            var template=store.get(Kind.TEMPLATES,w,(UUID)n.get("template_id"));
            JsonNode optIn=cmd.path("consent");boolean enabled=channel.enabled()&&template.enabled()&&template.spec().path("status").asText().equals("APPROVED")&&store.get(Kind.APPLICATIONS,w,(UUID)n.get("application_id")).enabled()&&store.get(Kind.RULES,w,(UUID)n.get("rule_id")).enabled();
            if(n.get("contact_id")!=null){var contact=store.get(Kind.CONTACTS,w,(UUID)n.get("contact_id"));optIn=contact.spec();enabled&=contact.enabled()&&contact.spec().path("phone").asText().equals(n.get("recipient_address"));}
            if(!enabled||!ConsentService.validOptIn(optIn)||consent.suppressed(w,n.get("recipient_address").toString())) {
                db.sql("UPDATE notifications SET status='CANCELLED',error_code='CONSENT_OR_CONFIGURATION_CHANGED' WHERE id=:id").param("id",id).update();timeline(id,"CANCELLED","Consentimento ou configuração indisponível");return null;
            }
            int attempt=((Number)n.get("attempt_count")).intValue()+1;
            db.sql("UPDATE notifications SET status='PROCESSING',locked_at=now(),attempt_count=:attempt WHERE id=:id").param("id",id).param("attempt",attempt).update();
            db.sql("INSERT INTO delivery_attempts(id,notification_id,attempt_number,status) VALUES(:a,:id,:attempt,'PROCESSING')").param("a",UUID.randomUUID()).param("id",id).param("attempt",attempt).update();timeline(id,"PROCESSING","Tentativa "+attempt+" iniciada");return n;
        });
        if(row==null)return;
        JsonNode command=json.read(row.get("command").toString());
        try(var ignored=MDC.putCloseable("correlationId",command.path("correlationId").asText())) {
            try {
                var connection=store.get(Kind.CHANNELS,(UUID)row.get("workspace_id"),(UUID)row.get("channel_connection_id"));
                var result=providers.get(connection.spec().path("provider").asText()).send(new SendCommand(row.get("recipient_address").toString(),command,connection));
                tx.executeWithoutResult(status->{
                    db.sql("UPDATE notifications SET status='SENT',provider_message_id=:provider,sent_at=now(),error_code=NULL,locked_at=NULL WHERE id=:id AND status='PROCESSING'").param("provider",result.messageId()).param("id",id).update();
                    db.sql("UPDATE delivery_attempts SET status='SENT',finished_at=now(),provider_response_code=:code,provider_message_id=:p WHERE notification_id=:id AND attempt_number=(SELECT attempt_count FROM notifications WHERE id=:id)").param("id",id).param("code",result.responseCode()).param("p",result.messageId()).update();timeline(id,"SENT","Provider aceitou a mensagem; aguardando entrega");
                });LOG.info("notification_sent notificationId={}",id);
            }catch(DeliveryFailure e){fail(id,row,e);}
            catch(Exception e){fail(id,row,new DeliveryFailure("DELIVERY_UNKNOWN",false,true,0));}
        }
    }
    private void fail(UUID id,Map<String,Object> row,DeliveryFailure e) {
        int retries=((Number)row.get("retry_count")).intValue();var delay=e.retryable&&!e.ambiguous?retry.delay(retries):Optional.<Duration>empty();Instant next=delay.map(d->Instant.now().plus(d)).orElse(null);
        tx.executeWithoutResult(status->{
            db.sql("UPDATE notifications SET status=:s,error_code=:e,failed_at=CASE WHEN :terminal THEN now() ELSE failed_at END,available_at=COALESCE(:next,available_at),retry_count=retry_count+:inc,locked_at=NULL WHERE id=:id AND status='PROCESSING'")
                .param("s",next==null?"FAILED":"PENDING").param("e",e.ambiguous?"DELIVERY_UNKNOWN":e.code).param("terminal",next==null).param("next",next==null?null:java.sql.Timestamp.from(next)).param("inc",next==null?0:1).param("id",id).update();
            db.sql("UPDATE delivery_attempts SET status=:s,finished_at=now(),error_code=:error,error_message=:message,provider_response_code=:http,next_retry_at=:next WHERE notification_id=:id AND status='PROCESSING'")
                .param("s",e.ambiguous?"UNKNOWN":"FAILED").param("error",e.code).param("message",e.ambiguous?"Entrega incerta; verifique no provider antes de reenviar":"Falha do provider: "+e.code).param("http",e.httpStatus).param("next",next==null?null:java.sql.Timestamp.from(next)).param("id",id).update();timeline(id,next==null?"FAILED":"RETRY_SCHEDULED",next==null?e.code:"Nova tentativa agendada para "+next);
        });LOG.info("notification_attempt_failed notificationId={} code={}",id,e.code);
    }
    private int priority(String s){return switch(s){case "LOW"->1;case "HIGH"->3;case "CRITICAL"->4;default->2;};}
    private void timeline(UUID id,String status,String text){db.sql("INSERT INTO notification_timeline(notification_id,status,description) VALUES(:id,:s,:d)").param("id",id).param("s",status).param("d",text).update();}
}
