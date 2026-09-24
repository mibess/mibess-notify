# Mibess Notify

Monólito modular: Java 25 LTS, Spring Boot, PostgreSQL, RabbitMQ; painel Angular e Tailwind. Aplicações publicam eventos de negócio e não conhecem providers, remetentes, templates ou destinatários internos.

## Fluxo

`Application → Event → transactional outbox → RabbitMQ → RoutingRule → Notification → outbox → worker → ChannelProvider → webhook → timeline`

Event e Notification são entidades distintas. Uma regra pode resolver contatos, grupos ou o destinatário do evento. Condições são dados estruturados com AND, nunca código executável. O provider Meta é um adaptador do contrato de canal; o provider fake só existe em testes/homologação.

## Consistência e segurança

- Chave idempotente única por aplicação; repetição com conteúdo diferente é conflito.
- Outbox no PostgreSQL com confirmação do broker e consumidores idempotentes; não há transação distribuída.
- Entrega externa não oferece exatamente uma vez: timeouts após envio podem ser ambíguos. Tentativas ambíguas exigem reconciliação/manual antes de reenviar.
- Workspaces isolam todos os recursos. Sessões administrativas em cookie HttpOnly com CSRF e roles ADMIN/OPERATOR/VIEWER. API keys de aplicações são aleatórias, exibidas uma vez e armazenadas apenas como SHA-256.
- Credenciais de canais usam AES-256-GCM e chave mestra externa. Webhooks têm assinatura HMAC sobre bytes originais, deduplicação e processamento assíncrono.
- Consentimento e suppressions são conferidos no roteamento e imediatamente antes do envio.
- Transições de entrega são monotônicas; SENT não implica DELIVERED. Timeline e tentativas são preservadas.

## Módulos

auth, workspace, application, event, routing, notification, channel, template, contact, whatsapp, webhook, audit, shared. Interfaces de providers e regras de domínio não dependem de infraestrutura Meta.

## Infraestrutura

Os bancos lógicos e usuários próprios estão em `postgres-hml` e `postgres-prd`, sem reiniciar ou recriar os containers. Rede existente `shared-db-network`. AWS ECR existente para imagens com tag de commit. Veja [inventário](docs/INFRASTRUCTURE.md).
