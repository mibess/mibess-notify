# Contrato HTTP

Prefixo `/api/v1`. Painel e backend compartilham origem. Erros de validação usam ProblemDetail com `detail` legível, sem SQL ou segredos.

## Publicação

`POST /events`, headers `X-API-Key` e `Idempotency-Key` obrigatórios. Campos permitidos: type, occurredAt, correlationId, recipient, data. Tamanho máximo de evento: 64 KB. Tipo no formato UPPER_SNAKE_CASE. `data` é objeto. `occurredAt` deve incluir fuso. IDs de aplicação, provider, template ou destinatário interno não são aceitos no topo.

202 evento aceito; 400 inválido; 401 chave ausente/inválida/revogada; 409 chave idempotente com payload diferente; 429 limite por application.

```json
{
  "type": "TRACKING_STATUS_CHANGED",
  "correlationId": "order-1842",
  "recipient": {
    "externalId": "customer-912",
    "name": "Mariana",
    "phone": "+5516999999999",
    "whatsappOptIn": true,
    "whatsappOptInAt": "2026-09-23T20:00:00Z",
    "whatsappOptInSource": "checkout"
  },
  "data": {"status":"OUT_FOR_DELIVERY","orderNumber":"1842","customerName":"Mariana"}
}
```

## Sessão

GET `/auth/csrf` cria token; POST `/auth/login` com `{email,password}` e `X-XSRF-TOKEN` inicia sessão. GET `/auth/me`, POST `/auth/logout`, POST `/auth/password`. Cookie SESSION HttpOnly, SameSite Strict e Secure em HTTPS. Mutações administrativas exigem CSRF. API de eventos e webhook usam suas próprias autenticações.

## Configurações administrativas

GET/POST `/admin/{applications|contacts|groups|channels|templates|rules}`; PUT `/admin/{kind}/{id}`. Documento `{code,name,enabled,spec}`. Cada módulo tem allowlist de campos e valida referências no mesmo workspace. Soft delete por enabled=false. Apenas ADMIN altera configurações.

GET/POST `/admin/applications/{id}/keys`, POST com `?rotate=true` para revogar todas anteriores; DELETE `/admin/applications/{id}/keys/{keyId}` revoga individual. Criação retorna chave completa uma vez, demais leituras retornam prefixo/status/datas.

## Observabilidade e operação

GET `/admin/dashboard`, `/admin/events`, `/admin/notifications`, `/admin/webhooks`, `/admin/audit`. Listas de histórico usam page/size (máximo 100), from/to ISO instant, applicationId, channelConnectionId e status quando aplicável. Dashboard separa contagens SENT/DELIVERED/READ.

GET `/admin/notifications/{id}` retorna notificação, timeline e attempts. POST `/admin/notifications/{id}/retry` exige ADMIN/OPERATOR, status FAILED e preserva histórico. Quando `DELIVERY_UNKNOWN`, o cliente deve informar `?acknowledgeUnknown=true` somente após reconciliar o resultado no provider.

GET/POST `/admin/suppressions`; POST recebe `{address,reason}` e exige ADMIN/OPERATOR. GET/POST `/admin/users` exige ADMIN; criação recebe `{email,password,role}`.

Somente em HML/dev com provider fake habilitado: POST `/admin/notifications/{id}/simulate-delivery`, ADMIN, notificação SENT por FAKE. Gera recibo assíncrono pelo mesmo processamento de webhooks. A aplicação recusa fake provider em PRD.

OpenAPI/Swagger: habilite `OPENAPI_ENABLED=true` em desenvolvimento; acesso autenticado. A API principal contém anotação de segurança e descrição de idempotência. O contrato de configuração dos módulos está nos validadores e formulários versionados.

## Envios manuais

POST `/admin/notifications/manual/preview` e POST `/admin/notifications/manual` exigem sessão ADMIN/OPERATOR e CSRF. Corpo: `{requestId,contactId,channelConnectionId,templateId?,text?,variables?,previewHash?}`. `requestId` é UUID criado pelo cliente para uma única intenção de envio. Mensagem livre exige text e ausência de templateId; Meta exige template aprovado do canal e variáveis por nome.

Preview resolve os cadastros no workspace, valida consentimento/suppression/canal, renderiza o conteúdo e retorna `previewHash`, destinatário, telefone, remetente e texto. A confirmação envia o mesmo corpo com previewHash; alterações invalidam a prévia (409). Retorna 202 `{notificationId,status,duplicate:false}` ou 200 com o mesmo ID para repetição idêntica. Mesmo requestId com outro conteúdo retorna 409, inclusive em chamadas concorrentes.

A notificação manual tem source MANUAL, autor e contato, sem aplicação/evento/regra fictícios. Texto e parâmetros são uma fotografia da confirmação; retries preservam o conteúdo. O worker revalida consentimento, endereço, canal e template antes da chamada externa e cancela se a configuração mudou. Não dispara regras de negócio. A fila, outbox, tentativas, reconciliação de webhooks e suppression são compartilhadas com os envios por evento.

## Diagnóstico e reprocessamento de eventos

GET `/admin/events/{id}` retorna `{event,timeline,notifications}` no workspace da sessão. `event.routing_diagnostics` contém applicationId, eventType, evaluatedAt, reason e as regras avaliadas, com ruleId, ruleCode, targetType, targetId, recipientCount e code. Payloads históricos não são reescritos.

Quando não há notificações, `error_code` identifica o ponto mais avançado alcançado: `NO_RULE_FOR_EVENT_TYPE`, `RULE_DISABLED`, `CONDITIONS_NOT_MET` ou `NO_ACTIVE_RECIPIENTS`. Aplicação desativada produz `SKIPPED` com `APPLICATION_DISABLED`. Regras de outra aplicação não participam da avaliação. Regras cujo tipo não corresponde aparecem no diagnóstico como `EVENT_TYPE_NOT_MATCHED`. Templates, canais ou consentimentos inválidos continuam gerando a notificação FAILED/CANCELLED correspondente, sem serem confundidos com ausência de regra.

POST `/admin/events/{id}/reprocess`, ADMIN/OPERATOR e CSRF, aceita apenas NO_MATCH ou SKIPPED. Retorna o mesmo detalhe após rotear com os cadastros atuais. Registra `REPROCESS_REQUESTED`, o diagnóstico anterior e `EVENT_REPROCESSED` na auditoria. Lock no evento e a unicidade `(event_id,rule_id,recipient_address)` impedem duplicatas; chamadas concorrentes após um processamento bem-sucedido retornam 409. Não é retry de notificações já criadas.

## Regras com vários tipos

`spec.eventType` legado continua válido. `spec.eventTypes` aceita de 1 a 50 valores únicos UPPER_SNAKE_CASE ou prefixos terminados em `_ *` (sem espaço), como `CRM_*`. A lista, quando presente, define o conjunto roteado. `eventType` permanece um tipo exato coberto pela lista para a imagem anterior: é inferido do primeiro tipo exato ou deve ser informado quando a lista contém somente prefixos.

`spec.priority` é o padrão; o mapa opcional `spec.eventPriorities` permite até 50 exceções por tipo exato coberto pela regra, com LOW/NORMAL/HIGH/CRITICAL. Exemplo: uma regra com os oito tipos CRM, `priority: NORMAL` e `eventPriorities: {"CRM_SYNC_FAILED":"HIGH"}`.

## Catálogo, simulação e saúde

- GET `/admin/applications/{id}/event-catalog`: lista `{eventType,lastReceivedAt,eventId,dataFields,sampleData}` do último evento recebido de cada tipo. Campos representam o payload recebido, não um schema obrigatório; ausência ou null ainda pode impedir renderização.
- POST `/admin/rules/{id}/test`, ADMIN/OPERATOR e CSRF: corpo opcional `{eventType,data,recipient}`. Usa o evento mais recente coberto pela regra, ou os dados fornecidos. Retorna destinatários, mensagem renderizada, variáveis, matched e problemas de configuração/consentimento. Não cria evento nem notificação e não chama o envio do provider.
- Enviar o teste de verdade exige escolher um contato, revisar `/admin/notifications/manual/preview` e confirmar `/admin/notifications/manual` com requestId e previewHash. O fluxo é o mesmo do envio manual, com um único destinatário.
- GET `/admin/notifications/manual/requests/{requestId}`, ADMIN/OPERATOR: recupera o envio do próprio autor no workspace, sem repetir a confirmação depois de uma resposta perdida.
- GET `/admin/applications/{id}/health`: checks com code/status/message/resourceKind/resourceId, último evento e contagem NO_MATCH de 24 h. Verifica chave, tipos sem regra, grupos/contatos, templates e canal. Evolution consulta somente connectionState: conexão fechada é erro; aberta não comprova entrega e permanece UNKNOWN até validação operacional. Meta também não é declarado conectado apenas por ter credenciais; FAKE_READY é usado exclusivamente em homologação/testes.


## E-mail transacional e destinatário por canal

O mesmo evento pode gerar avisos internos, WhatsApp do comprador e e-mail, por regras independentes. `recipient.email` aceita um único endereço; `recipient.phone` continua E.164. Endereço ausente/inválido impede somente a resolução daquele canal. Não há credenciais, remetente ou template no evento. Um `whatsappOptIn: false` explícito impede o WhatsApp ao comprador mesmo quando já existe contato com opt-in; a omissão mantém a resolução legada pelo contato. E-mail transacional não depende de opt-in WhatsApp. Contato desativado e suppression continuam prevalecendo.

Canal `EMAIL_SMTP`, cadastro administrativo:

```json
{
  "code": "ARTGIAN_EMAIL", "name": "Artgian — E-mail", "enabled": true,
  "spec": {
    "provider": "EMAIL_SMTP", "smtpHost": "smtp.zoho.com", "smtpPort": 465,
    "smtpSecurity": "TLS", "fromEmail": "contato@artgian.com.br", "fromName": "Artgian",
    "replyTo": "contato@artgian.com.br",
    "credentials": {"username": "contato@artgian.com.br", "password": "SEGREDO_EXTERNO"}
  }
}
```

`smtpSecurity` aceita `TLS` (TLS implícito) ou `STARTTLS` obrigatório, sem fallback em texto aberto; certificados e hostname são verificados. Credenciais permanecem criptografadas e são omitidas da resposta. Ao atualizar credenciais envie username/password juntos; omiti-las preserva o segredo existente. Host/porta são configuração exclusiva de ADMIN. Timeouts são limitados; não se armazena resposta SMTP bruta nos erros.

Templates SMTP mantêm `channelConnectionId`, `language`, `category`, `status`, `variables` e `body` (texto, até 20.000 caracteres); exigem `subject` (até 200, sem quebras de linha) e aceitam `html` (até 50.000). `providerTemplateName` não é obrigatório; `APPROVED` significa revisão interna. Variáveis em HTML são escapadas; texto e assunto usam os valores originais, com proteção de cabeçalhos. Imagens WhatsApp não são aplicadas ao e-mail. O cadastro completo de template aceita até 80.000 caracteres.

`CONTACT`, `CONTACT_GROUP` e `EVENT_RECIPIENT` usam o e-mail quando o canal é SMTP, com lookup por e-mail no workspace. Contatos podem ter apenas e-mail; telefone em branco é removido. Suppression recebe `{address,reason,channel:"EMAIL"}`; omitir `channel` mantém o comportamento legado `WHATSAPP`. Bloqueios são independentes por canal e são revalidados no worker.

Envio manual reutiliza os endpoints de preview/confirmação. Para SMTP, `contactId` resolve o e-mail cadastrado. Mensagem livre exige `text` e `subject`, com `html` opcional. Com `templateId`, informe somente `variables`, sem texto/assunto/HTML livres. Preview acrescenta `subject`, `html` e `fromAddress`; conteúdo e remetente participam da fotografia e da confirmação. O detalhe da notificação retorna esses mesmos campos. Testar regra retorna `renderedSubject`, `renderedHtml` e `channel` além dos campos anteriores.

SMTP aceito gera `SENT` com Message-ID. Não se declara `DELIVERED` ou `READ` sem confirmação de entrega. Respostas explícitas transitórias permitem retry; resultado incerto depois do envio é `DELIVERY_UNKNOWN` e exige reconciliação. A outbox, fila, worker, timeline e unicidade `(event_id,rule_id,recipient_address)` continuam compartilhados; repetir o evento não repete os canais já criados. `V6__email_contact_lookup.sql` adiciona apenas índice de consulta por e-mail, sem alterar dados históricos.

Testes usam servidor SMTP local TLS com certificado exclusivamente de teste, sem contas ou envio real; PostgreSQL/RabbitMQ de integração são isolados via Testcontainers.
