package com.mibess.notify.workspace;

import java.util.*;
import com.mibess.notify.shared.*;
import com.mibess.notify.shared.ResourceStore.Kind;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class Bootstrap implements ApplicationRunner {
    public static final UUID WORKSPACE=UUID.fromString("00000000-0000-4000-8000-000000000001");
    private final JdbcClient db;private final PasswordEncoder encoder;private final ResourceStore store;private final Json json;private final String email,password,environment;private final boolean fake;
    public Bootstrap(JdbcClient db,PasswordEncoder encoder,ResourceStore store,Json json,@Value("${notify.admin-email}") String email,@Value("${notify.admin-password}") String password,@Value("${notify.environment}") String environment,@Value("${notify.fake-provider}") boolean fake){this.db=db;this.encoder=encoder;this.store=store;this.json=json;this.email=email;this.password=password;this.environment=environment;this.fake=fake;}
    @Override @Transactional public void run(ApplicationArguments args) {
        if(environment.equals("prd")&&fake)throw new IllegalStateException("Provider fake proibido em produção");
        db.sql("INSERT INTO workspaces(id,name) VALUES(:id,'Mibess') ON CONFLICT DO NOTHING").param("id",WORKSPACE).update();
        if(db.sql("SELECT count(*) FROM users").query(Long.class).single()==0) {
            if(password.length()<14)throw new IllegalStateException("ADMIN_PASSWORD inicial deve ter ao menos 14 caracteres");
            UUID user=UUID.randomUUID();db.sql("INSERT INTO users(id,email,password_hash) VALUES(:id,:email,:p)").param("id",user).param("email",email.toLowerCase()).param("p",encoder.encode(password)).update();
            db.sql("INSERT INTO workspace_memberships(user_id,workspace_id,role) VALUES(:id,:w,'ADMIN')").param("id",user).param("w",WORKSPACE).update();
        }
        if(store.list(Kind.APPLICATIONS,WORKSPACE).isEmpty()) {
            for(String code:List.of("ARTGIAN","COMERCIAL","IA_JUD"))store.save(Kind.APPLICATIONS,WORKSPACE,UUID.randomUUID(),code,code.equals("ARTGIAN")?"Artgian":code.equals("COMERCIAL")?"Sistema Comercial":"IA-Jud",true,json.tree(Map.of("description","Aplicação de negócio")));
            UUID internal=UUID.randomUUID(),artgian=UUID.randomUUID();
            store.save(Kind.CHANNELS,WORKSPACE,internal,"MIBESS_NOTIFY","Mibess Notify",false,json.tree(Map.of("provider","WHATSAPP_META","graphApiVersion","v26.0","coexistence",false)));
            store.save(Kind.CHANNELS,WORKSPACE,artgian,"ARTGIAN_WHATSAPP","Artgian WhatsApp",false,json.tree(Map.of("provider","WHATSAPP_META","graphApiVersion","v26.0","coexistence",true)));
            UUID group=UUID.randomUUID();store.save(Kind.GROUPS,WORKSPACE,group,"ARTGIAN_OPERATIONS","Operações Artgian",true,json.tree(Map.of("members",List.of())));
            UUID application=store.list(Kind.APPLICATIONS,WORKSPACE).stream().filter(a->a.code().equals("ARTGIAN")).findFirst().orElseThrow().id();
            String[] templates={"internal_new_order","artgian_order_paid","artgian_tracking_posted","artgian_tracking_out_for_delivery","artgian_tracking_delivered","artgian_tracking_exception"};
            Map<String,UUID> ids=new HashMap<>();
            for(String name:templates) {
                UUID id=UUID.randomUUID();ids.put(name,id);
                store.save(Kind.TEMPLATES,WORKSPACE,id,name,name.replace('_',' '),false,json.tree(Map.of("providerTemplateName",name,"channelConnectionId",name.startsWith("internal")?internal:artgian,"language","pt_BR","category","UTILITY","status","DRAFT","body","Olá, {{customerName}}! Pedido {{orderNumber}}.","variables",List.of("customerName","orderNumber"))));
            }
            seedRule("ARTGIAN_ORDER_PAID","Nova venda interna",application,"ORDER_PAID","CONTACT_GROUP",group,internal,ids.get("internal_new_order"),List.of());
            seedRule("ARTGIAN_POSTED","Pedido postado",application,"TRACKING_POSTED","EVENT_RECIPIENT",null,artgian,ids.get("artgian_tracking_posted"),List.of());
            seedRule("ARTGIAN_OUT_FOR_DELIVERY","Saiu para entrega",application,"TRACKING_STATUS_CHANGED","EVENT_RECIPIENT",null,artgian,ids.get("artgian_tracking_out_for_delivery"),List.of(Map.of("field","data.status","operator","EQUALS","value","OUT_FOR_DELIVERY")));
            seedRule("ARTGIAN_EXCEPTION_CUSTOMER","Problema na entrega — cliente",application,"TRACKING_EXCEPTION","EVENT_RECIPIENT",null,artgian,ids.get("artgian_tracking_exception"),List.of());
            seedRule("ARTGIAN_EXCEPTION_INTERNAL","Problema na entrega — operações",application,"TRACKING_EXCEPTION","CONTACT_GROUP",group,internal,ids.get("internal_new_order"),List.of());
        }
    }
    private void seedRule(String code,String name,UUID app,String event,String target,UUID targetId,UUID channel,UUID template,List<?> conditions) {
        var s=json.object();s.put("applicationId",app.toString());s.put("eventType",event);s.put("targetType",target);if(targetId!=null)s.put("targetId",targetId.toString());s.put("channelConnectionId",channel.toString());s.put("templateId",template.toString());s.put("priority","NORMAL");s.set("conditions",json.tree(conditions));store.save(Kind.RULES,WORKSPACE,UUID.randomUUID(),code,name,false,s);
    }
}
