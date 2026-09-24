package com.mibess.notify;

import java.util.*;
import java.time.*;
import com.mibess.notify.shared.*;
import com.mibess.notify.shared.ResourceStore.*;
import com.mibess.notify.application.ApiKeyService;
import com.mibess.notify.webhook.WebhookService;
import com.mibess.notify.workspace.Bootstrap;
import com.mibess.notify.channel.FakeProvider;
import com.mibess.notify.channel.ChannelProvider.DeliveryFailure;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.*;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;

@SpringBootTest(properties={"notify.fake-provider=true","notify.admin-password=Integration-only-Password-2026","notify.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=","notify.outbox-delay=100","notify.dispatch-delay=100","notify.retry-delays=1,1","server.servlet.session.cookie.secure=false"})
@AutoConfigureMockMvc @Testcontainers
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class IntegrationTest {
    @Container static PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:15.5");
    @Container static RabbitMQContainer rabbit=new RabbitMQContainer("rabbitmq:4.1-management");
    @DynamicPropertySource static void configuration(DynamicPropertyRegistry r){r.add("spring.datasource.url",postgres::getJdbcUrl);r.add("spring.datasource.username",postgres::getUsername);r.add("spring.datasource.password",postgres::getPassword);r.add("spring.rabbitmq.host",rabbit::getHost);r.add("spring.rabbitmq.port",rabbit::getAmqpPort);r.add("spring.rabbitmq.username",rabbit::getAdminUsername);r.add("spring.rabbitmq.password",rabbit::getAdminPassword);r.add("spring.rabbitmq.virtual-host",()->"/");}
    @Autowired MockMvc mvc;@Autowired ResourceStore store;@Autowired Json json;@Autowired JdbcClient db;@Autowired ApiKeyService keys;@Autowired WebhookService webhooks;
    @MockitoSpyBean FakeProvider fake;
    UUID app,channel,contact,group,template;String key;
    @BeforeEach void fixture(){
        String suffix=UUID.randomUUID().toString().substring(0,8);app=UUID.randomUUID();channel=UUID.randomUUID();contact=UUID.randomUUID();group=UUID.randomUUID();template=UUID.randomUUID();
        store.save(Kind.APPLICATIONS,Bootstrap.WORKSPACE,app,"TEST_"+suffix,"Artgian teste",true,json.object());
        store.save(Kind.CHANNELS,Bootstrap.WORKSPACE,channel,"CHANNEL_"+suffix,"Fake",true,json.tree(Map.of("provider","FAKE")));
        store.save(Kind.CONTACTS,Bootstrap.WORKSPACE,contact,"CONTACT_"+suffix,"Angélica teste",true,json.tree(Map.of("phone","+551600000"+String.format("%04d",new Random().nextInt(10000)),"type","INTERNAL","whatsappOptIn",true,"whatsappOptInAt",Instant.now().toString(),"whatsappOptInSource","Teste automatizado")));
        store.save(Kind.GROUPS,Bootstrap.WORKSPACE,group,"GROUP_"+suffix,"Operações teste",true,json.tree(Map.of("members",List.of(contact))));
        store.save(Kind.TEMPLATES,Bootstrap.WORKSPACE,template,"TEMPLATE_"+suffix,"Pedido teste",true,json.tree(Map.of("channelConnectionId",channel,"providerTemplateName","internal_new_order","language","pt_BR","status","APPROVED","body","Pedido {{orderNumber}}","variables",List.of("orderNumber"))));
        rule("ORDER_PAID","CONTACT_GROUP",group,List.of());
        key=keys.create(Bootstrap.WORKSPACE,app,false).get("key").toString();
    }
    void rule(String event,String type,UUID target,List<?> conditions){var spec=json.object();spec.put("applicationId",app.toString());spec.put("eventType",event);spec.put("targetType",type);if(target!=null)spec.put("targetId",target.toString());spec.put("channelConnectionId",channel.toString());spec.put("templateId",template.toString());spec.put("priority","HIGH");spec.set("conditions",json.tree(conditions));UUID id=UUID.randomUUID();store.save(Kind.RULES,Bootstrap.WORKSPACE,id,"RULE_"+id.toString().substring(0,8),"Regra teste",true,spec);}
    String event(String idem,String payload)throws Exception{return mvc.perform(post("/api/v1/events").header("X-API-Key",key).header("Idempotency-Key",idem).contentType("application/json").content(payload)).andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();}
    @Test void orderPaidThroughRealPostgresRabbitFakeAndWebhook()throws Exception {
        String payload="{\"type\":\"ORDER_PAID\",\"correlationId\":\"order-1842\",\"data\":{\"orderNumber\":\"1842\"}}";String accepted=event("paid-1842",payload);UUID event=UUID.fromString(json.read(accepted).path("eventId").asText());
        assertThat(json.read(event("paid-1842",payload)).path("duplicate").asBoolean()).isTrue();
        await().atMost(Duration.ofSeconds(30)).untilAsserted(()->assertThat(db.sql("SELECT status FROM notifications WHERE event_id=:e").param("e",event).query(String.class).optional()).contains("SENT"));
        var notification=db.sql("SELECT * FROM notifications WHERE event_id=:e").param("e",event).query().singleRow();assertThat(notification.get("attempt_count")).isEqualTo(1);
        var receipt=json.tree(Map.of("statuses",List.of(Map.of("id",notification.get("provider_message_id"),"status","delivered","timestamp",String.valueOf(Instant.now().getEpochSecond())))));
        var connection=store.get(Kind.CHANNELS,Bootstrap.WORKSPACE,channel);webhooks.accept(connection,receipt,Crypto.hash(json.write(receipt)));webhooks.accept(connection,receipt,Crypto.hash(json.write(receipt)));
        await().atMost(Duration.ofSeconds(20)).untilAsserted(()->assertThat(db.sql("SELECT status FROM notifications WHERE event_id=:e").param("e",event).query(String.class).single()).isEqualTo("DELIVERED"));
        assertThat(db.sql("SELECT count(*) FROM notifications WHERE event_id=:e").param("e",event).query(Integer.class).single()).isEqualTo(1);
        mvc.perform(get("/api/v1/admin/notifications/"+notification.get("id")).with(user("admin@mibess.com.br").roles("ADMIN"))).andExpect(status().isOk()).andExpect(jsonPath("$.timeline.length()").value(7));
        mvc.perform(post("/api/v1/events").header("X-API-Key",key).header("Idempotency-Key","paid-1842").contentType("application/json").content(payload.replace("1842","1843"))).andExpect(status().isConflict());
    }
    @Test void conditionsSelectEventRecipientAndSuppressOptOut()throws Exception {
        rule("TRACKING_STATUS_CHANGED","EVENT_RECIPIENT",null,List.of(Map.of("field","data.status","operator","EQUALS","value","OUT_FOR_DELIVERY")));
        var payload=json.object();payload.put("type","TRACKING_STATUS_CHANGED");payload.set("recipient",json.tree(Map.of("name","Mariana","phone","+5516999999999","whatsappOptIn",true,"whatsappOptInAt",Instant.now().toString(),"whatsappOptInSource","Checkout")));payload.set("data",json.tree(Map.of("status","IN_TRANSIT","orderNumber","1842")));
        UUID noMatch=UUID.fromString(json.read(event("in-transit",json.write(payload))).path("eventId").asText());
        await().atMost(Duration.ofSeconds(20)).untilAsserted(()->assertThat(db.sql("SELECT status FROM events WHERE id=:e").param("e",noMatch).query(String.class).single()).isEqualTo("NO_MATCH"));
        payload.set("data",json.tree(Map.of("status","OUT_FOR_DELIVERY","orderNumber","1842")));UUID matched=UUID.fromString(json.read(event("out-for-delivery",json.write(payload))).path("eventId").asText());
        await().atMost(Duration.ofSeconds(20)).untilAsserted(()->assertThat(db.sql("SELECT status FROM notifications WHERE event_id=:e").param("e",matched).query(String.class).optional()).contains("SENT"));
        db.sql("INSERT INTO suppressions(id,workspace_id,address,reason) VALUES(:id,:w,'+5516999999999','Test opt-out') ON CONFLICT DO NOTHING").param("id",UUID.randomUUID()).param("w",Bootstrap.WORKSPACE).update();UUID suppressed=UUID.fromString(json.read(event("suppressed",json.write(payload))).path("eventId").asText());
        await().atMost(Duration.ofSeconds(20)).untilAsserted(()->assertThat(db.sql("SELECT status FROM notifications WHERE event_id=:e").param("e",suppressed).query(String.class).optional()).contains("CANCELLED"));
    }
    @Test void authRolesCsrfAndRevocationAreEnforced()throws Exception {
        mvc.perform(get("/api/v1/admin/applications")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/events").contentType("application/json").content("{}")).andExpect(status().isUnauthorized());
        mvc.perform(post("/webhooks/meta/whatsapp").header("X-Hub-Signature-256","sha256="+"0".repeat(64)).contentType("application/json").content("{\"object\":\"whatsapp_business_account\"}")).andExpect(status().isUnauthorized());
        String input="{\"code\":\"NEW_APP\",\"name\":\"New\",\"enabled\":true,\"spec\":{}}";
        mvc.perform(post("/api/v1/admin/applications").with(user("admin@mibess.com.br").roles("ADMIN")).contentType("application/json").content(input)).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/admin/applications").with(user("admin@mibess.com.br").roles("VIEWER")).with(csrf()).contentType("application/json").content(input)).andExpect(status().isForbidden());
        keys.create(Bootstrap.WORKSPACE,app,true);assertThatThrownBy(()->keys.authenticate(key)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }
    @Test void foreignWorkspaceResourceIsInvisible()throws Exception {UUID foreign=UUID.randomUUID(),id=UUID.randomUUID();db.sql("INSERT INTO workspaces(id,name) VALUES(:id,'Other')").param("id",foreign).update();store.save(Kind.APPLICATIONS,foreign,id,"OTHER_APP","Other",true,json.object());assertThatThrownBy(()->store.get(Kind.APPLICATIONS,Bootstrap.WORKSPACE,id)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);}
    @Test void transientFailureRetriesAndPreservesAttempts()throws Exception {
        doThrow(new DeliveryFailure("RATE_LIMIT",true,false,429)).doCallRealMethod().when(fake).send(any());
        UUID event=UUID.fromString(json.read(event("retry-once","{\"type\":\"ORDER_PAID\",\"data\":{\"orderNumber\":\"retry\"}}")).path("eventId").asText());
        await().atMost(Duration.ofSeconds(20)).untilAsserted(()->assertThat(db.sql("SELECT status FROM notifications WHERE event_id=:e").param("e",event).query(String.class).optional()).contains("SENT"));
        assertThat(db.sql("SELECT count(*) FROM delivery_attempts a JOIN notifications n ON n.id=a.notification_id WHERE n.event_id=:e").param("e",event).query(Integer.class).single()).isEqualTo(2);
    }
    @Test void ambiguousFailureRequiresExplicitReconciliation()throws Exception {
        doThrow(new DeliveryFailure("DELIVERY_UNKNOWN",false,true,0)).when(fake).send(any());
        UUID event=UUID.fromString(json.read(event("ambiguous","{\"type\":\"ORDER_PAID\",\"data\":{\"orderNumber\":\"unknown\"}}")).path("eventId").asText());
        await().atMost(Duration.ofSeconds(20)).untilAsserted(()->assertThat(db.sql("SELECT status FROM notifications WHERE event_id=:e").param("e",event).query(String.class).optional()).contains("FAILED"));
        UUID id=db.sql("SELECT id FROM notifications WHERE event_id=:e").param("e",event).query(UUID.class).single();
        mvc.perform(post("/api/v1/admin/notifications/"+id+"/retry").with(user("admin@mibess.com.br").roles("ADMIN")).with(csrf())).andExpect(status().isConflict());
        assertThat(db.sql("SELECT attempt_count FROM notifications WHERE id=:id").param("id",id).query(Integer.class).single()).isEqualTo(1);
        doCallRealMethod().when(fake).send(any());
        mvc.perform(post("/api/v1/admin/notifications/"+id+"/retry?acknowledgeUnknown=true").with(user("admin@mibess.com.br").roles("ADMIN")).with(csrf())).andExpect(status().isOk());
        await().atMost(Duration.ofSeconds(20)).untilAsserted(()->assertThat(db.sql("SELECT status FROM notifications WHERE id=:id").param("id",id).query(String.class).single()).isEqualTo("SENT"));
        assertThat(db.sql("SELECT attempt_count FROM notifications WHERE id=:id").param("id",id).query(Integer.class).single()).isEqualTo(2);
    }
    @Test void concurrentDuplicatesProduceOneEventAndNotification()throws Exception {
        var application=new ApiKeyService.Application(app,Bootstrap.WORKSPACE);var payload=json.read("{\"type\":\"ORDER_PAID\",\"data\":{\"orderNumber\":\"race\"}}");
        try(var executor=java.util.concurrent.Executors.newFixedThreadPool(8)) {
            var tasks=new ArrayList<java.util.concurrent.Callable<UUID>>();for(int i=0;i<16;i++)tasks.add(()->eventService.accept(application,"race",payload).eventId());
            var results=executor.invokeAll(tasks);Set<UUID> ids=new HashSet<>();for(var result:results)ids.add(result.get());assertThat(ids).hasSize(1);
            UUID id=ids.iterator().next();await().atMost(Duration.ofSeconds(20)).untilAsserted(()->assertThat(db.sql("SELECT status FROM notifications WHERE event_id=:e").param("e",id).query(String.class).optional()).contains("SENT"));
            assertThat(db.sql("SELECT duplicate_count FROM events WHERE id=:id").param("id",id).query(Integer.class).single()).isEqualTo(15);
        }
    }
    @Autowired com.mibess.notify.event.EventService eventService;
}
