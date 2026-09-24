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
