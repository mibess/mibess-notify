# WhatsApp oficial

Integração exclusivamente com WhatsApp Business Platform / Cloud API, usando templates. Não utiliza a Evolution API existente na VPS nem simula WhatsApp Web.

## Fontes verificadas

- [Meta Developer News](https://developers.meta.com/resources/blog/) lista Graph API **v26.0**, anunciada em julho de 2026. O valor é configurável por ambiente (`META_GRAPH_VERSION`) e por canal (`graphApiVersion`).
- [Evolução de contas WhatsApp](https://developers.meta.com/resources/videos/whatsapp-account-model-evolution/): a Meta documenta mudanças de WABA/WAAC/PMA; a configuração permite businessAccountId e messagingAccountId, sem acoplar esses identificadores ao núcleo.
- [Identificadores WhatsApp](https://developers.meta.com/resources/videos/whatsapp-usernames/): webhooks podem usar identificadores de usuário. O inbound conserva `from_user_id` quando disponível. O envio inicial continua usando telefone E.164; uso outbound de BSUID necessita extensão do adaptador.
- [Cloud API](https://developers.facebook.com/docs/whatsapp/cloud-api/) e [webhooks Graph](https://developers.facebook.com/docs/graph-api/webhooks/getting-started/): tentativas de consulta retornaram HTTP 429 neste ambiente. A implementação segue o contrato de templates `/messages`, verificação challenge e assinatura HMAC-SHA256; validar o contrato real com a conta Meta antes do primeiro envio em produção.

## Configuração de cada identidade

`MIBESS_NOTIFY` para operação interna, `ARTGIAN_WHATSAPP` para clientes. Informar Phone Number ID, identificador de conta, token com permissões WhatsApp apropriadas, App Secret e Verify Token. Cada conexão mantém suas credenciais criptografadas com AES-GCM; elas não são devolvidas pelo endpoint administrativo.

Webhook público: `https://notify.mibess.com.br/webhooks/meta/whatsapp`. O GET verifica token e devolve challenge em texto; o POST valida assinatura dos bytes originais, seleciona identidade pelo phone_number_id, deduplica e persiste antes de processar em fila.

Templates precisam existir e estar aprovados no provider. O painel guarda nome, ID, categoria, idioma, status, corpo e ordem das variáveis. Não existe sincronização/submissão automática de templates neste MVP. Somente componentes de corpo com parâmetros texto são enviados inicialmente; templates que exigem header, mídia ou botões dinâmicos precisam de extensão explícita.

## Coexistência

O canal registra que a conta está configurada para coexistência, mas não ativa elegibilidade nem realiza Embedded Signup. O número deve ser vinculado pelo fluxo oficial disponível à conta, preservando o Business App quando suportado. Não fazer migração de número que desative o app sem validar o fluxo oficial.

## Consentimento

Data, fonte e opt-in são obrigatórios. Opt-out e suppression impedem novos envios e são reavaliados imediatamente antes da chamada externa. Templates, categorias, janela de atendimento e políticas Meta continuam sendo responsabilidades da configuração da conta. O sistema não envia texto livre fora de templates.

## Pendências externas

Não foram localizados Phone Number IDs/tokens oficiais nas fontes consultadas (Notion e configurações locais dos projetos Artgian/Comercial). Os canais iniciais permanecem desativados. Não alegar entrega WhatsApp real até executar um envio autorizado com destinatário consentido e receber o webhook DELIVERED.
