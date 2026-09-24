# Mibess Notify

Central de eventos e notificações do ecossistema Mibess. Aplicações publicam **eventos de negócio**; o Notify resolve regras, destinatários, canal, identidade remetente, template e entrega. Eventos e notificações têm ciclos de vida separados.

**Stack:** Java 25 LTS, Spring Boot 3.5.16, Spring Security/Data JPA, PostgreSQL/Flyway, RabbitMQ, Maven; Angular 21 LTS, TypeScript e TailwindCSS. Monólito modular, sem microserviços. Meta Graph API configurável (`v26.0` como padrão verificado em setembro de 2026).

**Estado atual:** plataforma publicada em [produção](https://notify.mibess.com.br) e [homologação](https://notify-hml.mibess.com.br), com CI/CD ativo, HTTPS, health UP e aceite controlado aprovado. Entrega real pela Meta ainda depende de credenciais, números, templates aprovados e consentimento. Veja as [evidências](docs/VALIDATION.md) e o [estado do deploy](docs/DEPLOYMENT.md).

## Desenvolvimento

Requisitos: Java 25, Node 24, Docker com Compose.

```bash
cp .env.example .env
# Preencha senhas locais e ADMIN_PASSWORD (mínimo 14 caracteres).
# Gere MASTER_ENCRYPTION_KEY: openssl rand -base64 32
docker compose --env-file .env -f docker-compose.dev.yml up -d
set -a
. ./.env
set +a
cd backend
./mvnw spring-boot:run
```

Outro terminal:

```bash
cd frontend
npm ci
npm start
```

Painel: `http://127.0.0.1:4200`. API: `http://127.0.0.1:8080`. PostgreSQL local usa **55432** e RabbitMQ **55672**; portas limitadas ao loopback. Console RabbitMQ local: 15673. A opção local não representa a infraestrutura HML/PRD.

O primeiro boot cria workspace Mibess, usuário ADMIN com a senha externa, aplicações ARTGIAN/COMERCIAL/IA_JUD, identidades sem credenciais, templates em rascunho e regras desativadas. A senha de bootstrap não substitui a senha de usuários existentes. Nunca habilite o provider fake em produção: a aplicação rejeita essa combinação.

## Configurar uma integração

1. Entre no painel, abra Aplicações → ARTGIAN → Chaves de API. Copie a chave exibida uma única vez. Rotação revoga as anteriores; revogação individual também está disponível.
2. Cadastre contatos, com telefone E.164 e evidência de consentimento. Adicione os contatos ao grupo ARTGIAN_OPERATIONS.
3. Em Canais, configure MIBESS_NOTIFY com Meta Cloud API ou Evolution API temporária. Para Evolution, informe a instância e as credenciais, e configure EVOLUTION_API_URL no servidor; veja docs/EVOLUTION.md.
4. Configure o template `internal_new_order` com o nome/idioma e parâmetros na ordem aprovada pela Meta. Alterar o status local não aprova o template na Meta.
5. Ative a regra ARTGIAN + ORDER_PAID → grupo → MIBESS_NOTIFY → template.
6. Publique um evento. Acompanhe Eventos → Notificações → Timeline.

```bash
curl --fail-with-body -X POST https://notify.mibess.com.br/api/v1/events \
  -H 'Content-Type: application/json' \
  -H "X-API-Key: $API_KEY" \
  -H 'Idempotency-Key: artgian-order-1842-paid' \
  -d '{"type":"ORDER_PAID","correlationId":"order-1842","data":{"orderNumber":"1842","customerName":"Mariana","amount":189.90,"paymentMethod":"PIX"}}'
```

Resposta: `202 {"eventId":"…","status":"ACCEPTED","duplicate":false}`. A application é inferida exclusivamente pela chave. Repetição da mesma chave idempotente/conteúdo retorna o mesmo evento; conteúdo diferente retorna 409. Limite por application retorna 429.

Para EVENT_RECIPIENT, envie `recipient` com `phone`, `name` e identidade externa. Consentimento pode vir de contato previamente cadastrado ou, para novo destinatário, dos campos `whatsappOptIn`, `whatsappOptInAt` (ISO instant) e `whatsappOptInSource`. Suppressions sempre prevalecem. Dados do cliente são diferentes dos destinatários internos: aplicações não conhecem o grupo de operações.

## Confiabilidade

- Event + outbox persistem na mesma transação. O publisher aguarda confirmação RabbitMQ. Consumidores toleram redelivery.
- Filas duráveis `notify.events`, `notify.deliveries`, `notify.webhooks`, `notify.dead-letter`, em vhosts separados por ambiente. `notify.deliveries` é independente de canal. Retry usa agendamento persistido no banco e nova publicação pela outbox.
- Retry transitório: 1 min, 5 min, 15 min, 1 h, configurável. Resultados de envio incertos **não são reenviados automaticamente**.
- SENT significa aceitação pelo provider. DELIVERED/READ dependem de confirmação. Webhooks fora de ordem não fazem o status regredir.
- Novas tentativas mantêm histórico. Falhas com entrega incerta exigem reconciliação explícita antes de autorizar reenvio.
- Mensagens recebidas não disparam respostas. Metadados mínimos são registrados; palavras explícitas STOP/SAIR/PARAR/CANCELAR/UNSUBSCRIBE geram supressão.

## Testes

```bash
cd backend && ./mvnw clean verify
cd ../frontend && npm ci && npm run build && npm test
```

Os testes de integração exigem Docker e usam PostgreSQL/RabbitMQ reais via Testcontainers. Cobrem os dois cenários Artgian, duplicatas, webhook fake, opt-out, API keys, roles e CSRF. Testes unitários cobrem condições, variáveis, criptografia, assinatura, retry e transições.

## Operação e segurança

- Sessão HttpOnly/SameSite com CSRF. API key só autoriza publicação de eventos. Roles: ADMIN configura; OPERATOR consulta/reenvia/bloqueia destinatários; VIEWER consulta.
- Mesma origem para frontend/API, sem CORS permissivo. Segredos de canal usam AES-256-GCM com contexto de workspace/canal.
- Swagger é autenticado e desabilitado por padrão em PRD. Health público divulga apenas UP/DOWN; demais endpoints Actuator são restritos. Proxy bloqueia endpoints Actuator além de `/actuator/health`.
- Payload bruto de webhook processado é reduzido após 24h, preservando recibo e metadados. Eventos e histórico não são apagados automaticamente. Defina política organizacional de retenção antes de armazenar dados sensíveis; publique apenas os dados necessários.
- Evolution API disponível como alternativa temporária autorizada pelo usuário, com conexão do número pelo Manager existente. Chatbots e campanhas não fazem parte do Notify.

Documentação: [arquitetura](ARCHITECTURE.md), [infraestrutura](docs/INFRASTRUCTURE.md), [deploy/rollback](docs/DEPLOYMENT.md), [Meta](docs/META.md), [contrato HTTP](docs/API.md), [status de implementação](IMPLEMENTATION_PLAN.md).

## Troubleshooting

| Sintoma | Verificação |
|---|---|
| 401 ao publicar | Chave completa correta, aplicação ativa e chave não revogada |
| 403 no painel | Role e cookie CSRF; recarregue e faça login novamente |
| Evento NO_MATCH | Regra ativa, tipo exato, condições e destinatários ativos |
| CANCELLED | Consentimento, suppression, contato/canal/template desativado |
| MISSING_TEMPLATE_VARIABLE | Ordem/lista de variáveis do template e data do evento |
| DELIVERY_UNKNOWN | Reconciliar no provider; não repetir cegamente |
| Webhook DEFERRED | Aguardando vinculação por providerMessageId; reconciliado até 24h |
| Health DOWN | Conexão ao banco, vhost/credencial RabbitMQ e logs do container Notify |
| Master key inválida | 32 bytes codificados em Base64; não trocar sem recriptografar credenciais |
