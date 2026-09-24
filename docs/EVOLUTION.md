# Evolution API temporária

Autorizada pelo usuário em 24/09/2026 enquanto o onboarding Meta está pendente. Instalação existente na Hostinger: Evolution 2.3.7. Instância exclusiva `MIBESS_NOTIFY`, número +55 16 99375-4887; `giulia-bot` não deve ser alterada.

## Configuração

- Backend: `EVOLUTION_API_URL`, definido apenas pelo operador do servidor. Não é aceito um destino arbitrário no formulário de canais. Na VPS, `http://host.docker.internal:8282` usa o gateway local Docker, sem trafegar credenciais pela internet.
- Canal: provider `WHATSAPP_EVOLUTION`, `instanceName`, credentials com `apiKey` da instância e `webhookToken` independente. Ambos criptografados em repouso, omitidos das respostas administrativas. Ao trocar de provedor é obrigatório fornecer novas credenciais.
- Webhook: `https://notify.mibess.com.br/webhooks/evolution/whatsapp/{channelId}`. Configurar na instância `webhook.enabled=true`, `byEvents=false`, `base64=false`, header `X-Notify-Webhook-Token` e eventos `MESSAGES_UPDATE`, `MESSAGES_UPSERT`.
- HML deve usar instância de teste separada ou servidor simulado. Nunca conectar as duas instalações à mesma instância de produção.

## Comportamento

Templates locais com status APPROVED são revisados pelo operador, não aprovados pela Meta. O corpo usa `{{variavel}}` e a mesma lista de variáveis/validações do fluxo existente. Envios limitados a texto (4096 caracteres), destinatários E.164, consentimento e suppression; sem grupos de WhatsApp ou campanhas.

A resposta com `key.id` registra aceitação do envio. DELIVERED/READ dependem dos webhooks. Erros HTTP 5xx, respostas inválidas, redirecionamentos ou falha de transporte após conexão são resultados incertos, sem reenvio automático. HTTP 429 permite retry.

Webhooks exigem token, canal habilitado e instância correspondente. O payload bruto da Evolution contém apikey e NÃO é persistido: normalizamos somente status e comandos de opt-out. Mensagens comuns e mídias são descartadas. STOP/SAIR/PARAR/CANCELAR/UNSUBSCRIBE de contatos individuais geram suppression; grupos e identificadores LID sem telefone alternativo são ignorados.

A conexão Baileys usa WhatsApp Web; não equivale ao cadastro Cloud API/coexistência oficial. O fluxo oficial Meta e suas credenciais permanecem independentes. Para migrar, configurar novo canal Meta, cadastrar templates oficiais e apontar regras para o novo canal após validar. Desabilitar o temporário e remover o webhook da instância ao finalizar a migração.

## Referências da versão verificada

- https://github.com/evolution-foundation/evolution-api/tree/2.3.7
- `src/api/integrations/event/webhook/webhook.controller.ts`: headers customizados e payload com apikey.
- `src/api/integrations/channel/whatsapp/whatsapp.baileys.service.ts`: messages.update com keyId, fromMe, status; messages.upsert com key/message.

## Validação

Testes HTTP simulados verificam payload, autenticação e tratamento de respostas ambíguas. Teste integrado verifica rejeição sem token, token incorreto e instância divergente, deduplicação e ausência de apikey persistida. Um envio real depende de autorização específica do destinatário.
