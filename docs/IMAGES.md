# Imagens nas notificações

## Uso no painel

- **Integrações → editar aplicação:** carregue a identidade do cliente em “Imagem padrão da aplicação / cliente” ou use “Criar imagem simples”. Clique em Salvar para associá-la à aplicação. Eventos usam essa imagem automaticamente na Evolution.
- **Templates:** uma imagem própria substitui a da aplicação. “Enviar apenas texto” desativa a herança. Na Meta, declare `headerType: IMAGE` apenas para um template realmente aprovado com cabeçalho de imagem; `NONE` continua enviando somente texto, mesmo que a aplicação tenha imagem.
- **Enviar notificação:** carregue uma imagem opcional e revise a imagem e o texto antes de confirmar. Sem upload, o envio manual usa a imagem do template, se configurada. Um envio manual livre não tem aplicação para herdar identidade.
- O histórico exibe a imagem que foi selecionada no momento da criação. Trocar a identidade da aplicação não troca a imagem de notificações já criadas. Reenvio explícito de um evento atualiza mensagem e imagem usando a configuração atual; novas tentativas automáticas mantêm a imagem original.

O formato atual é **960 × 320 px, JPG/PNG, até 500.000 bytes (500 KB)**, com altura 37% menor que a primeira versão (504 px). Os limites aparecem no painel e são verificados novamente no servidor com leitura do formato real e das dimensões. Arquivos de outro formato, MIME incompatível, peso ou dimensões incorretos são rejeitados. As quatro aplicações possuem artes próprias em `assets/notification-images/v2/`, entre 24 e 39 KB: Studio com monograma AS marinho/dourado; CRM com selo “a.” e terracota; Comercial verde com assinatura SC; IA-Jud com composição tipográfica em ameixa/lavanda. As referências de Studio, CRM e Comercial vieram dos respectivos projetos; IA-Jud recebeu identidade própria. As artes foram geradas pelo ImageGen integrado e exportadas como JPEG; os prompts estão versionados junto delas. O botão “Criar imagem simples” continua disponível como alternativa genérica por nome, gerada por código. Uploads permitem preservar a identidade de cada cliente. Imagens antigas de 960 × 504 continuam válidas no histórico e nas tentativas pendentes; apenas novos uploads e novas imagens simples seguem 960 × 320.

A Evolution recebe imagem e legenda em **uma única requisição `sendMedia`**; a legenda está limitada a 1024 caracteres para manter compatibilidade com mensagens de mídia. A Meta recebe um parâmetro de imagem no componente `header` do template, preservando os parâmetros do corpo. Aprovação local não aprova o template no provedor. Nenhuma mensagem real é disparada ao subir uma imagem.

## Armazenamento

Bucket criado em 19/10/2026: `mibess-notify-images-497176797028`, região `sa-east-1`, conta `497176797028`, seguindo a região verificada do `artgian-precificacao`.

- Todos os quatro controles de Block Public Access estão ativos; Object Ownership é BucketOwnerEnforced (ACLs desativadas).
- Criptografia SSE-S3 (AES256) explícita no bucket e em cada upload; a política do bucket rejeita HTTP.
- Chaves imutáveis: `mibess-notify/{dev|prd}/{workspaceId}/{imageId}.jpg` ou `.png`. Apenas desenvolvimento e produção receberam acessos IAM, conforme solicitado.
- O painel lê imagens por endpoint autenticado da própria aplicação, com isolamento por workspace e `Cache-Control: no-store`. Não exige CORS no bucket nem ampliação da CSP para domínios externos.
- O banco guarda ID e metadados. Na tentativa de envio é criada uma URL GET assinada com validade de uma hora; uma nova tentativa gera uma nova URL. Links assinados não ficam no comando persistido, no histórico ou nos logs do Notify.
- Uploads exigem ADMIN ou OPERATOR, sessão e CSRF. Alterar identidades de aplicações/templates exige ADMIN. VIEWER pode consultar as imagens do próprio workspace.
- As imagens são preservadas para o histórico. Não há exclusão automática de arquivos sem referência. Eventuais uploads abandonados também permanecem; definir retenção é uma decisão operacional futura.

O acesso do runtime precisa apenas de `s3:PutObject` e `s3:GetObject` no prefixo do ambiente. A análise inicial foi gerada pelo IAM Policy Autopilot a partir de `ImageStorage.java`; o escopo final elimina ações condicionais que este fluxo não usa (ACLs, tags, retenção, KMS, Object Lambda e versões) e restringe o ARN ao bucket/prefixo. A aplicação não precisa listar buckets, apagar objetos nem administrar IAM.

**Validação:** 46 testes backend (incluindo PostgreSQL/RabbitMQ reais), 19 testes frontend e build Angular/CSP aprovados. O SDK Java executou upload, leitura autenticada e download com URL temporária no bucket real; acesso anônimo retornou 403. O JPEG de amostra ARTGIAN CRM ficou com 20.773 bytes. O arquivo temporário de teste no S3 foi removido. O teste automatizado não envia mensagens reais. O envio real autorizado pelo usuário está registrado abaixo.

**Estado operacional:** bucket criado, protegido e verificado. Após autorização explícita, foram criadas somente as identidades IAM `mibess-notify-images-dev` e `mibess-notify-images-prd`, com uma chave de acesso cada e política `NotificationImagesOnly` limitada ao prefixo correspondente. Upload, leitura, download assinado e bloqueio entre ambientes foram verificados com as credenciais reais de cada identidade. Credenciais em `.local/images-dev.env` e `.local/images-prd.env`, modo 0600 e ignoradas pelo Git. Nenhuma identidade ou chave de HML foi criada. Políticas em `infrastructure/image-access/dev.json` e `prd.json`. O código foi publicado em HML/PRD no commit `7f7269f1d462a807668c081eb40a8713a8316aa7`; as quatro aplicações reais de produção receberam imagens padrão e passaram no envio real autorizado.

## Configuração do backend

```env
NOTIFY_IMAGES_BUCKET=mibess-notify-images-497176797028
AWS_REGION=sa-east-1
NOTIFY_IMAGES_PREFIX=mibess-notify/prd
NOTIFY_IMAGES_WIDTH=960
NOTIFY_IMAGES_HEIGHT=320
NOTIFY_IMAGES_MAX_BYTES=500000
```

A autenticação usa a cadeia padrão de credenciais do AWS SDK Java v2. Em execução AWS, prefira uma role de workload. A VPS atual precisa de credenciais dedicadas ou um mecanismo de federação. Não usar a sessão de administração/root como credencial permanente da aplicação. Para a publicação, configurar `AWS_ACCESS_KEY_ID` e `AWS_SECRET_ACCESS_KEY` exclusivamente nos arquivos protegidos do backend de cada ambiente; nunca no frontend/Git. O Compose preserva prefixos distintos de HML e PRD; HML não recebeu credenciais S3 e permanece sem imagens até configuração explícita.

Sem bucket, notificações de texto continuam funcionando; o painel explica que imagens estão desabilitadas. O servidor permite ajustar dimensões (100–4096 px) e peso (até 5.000.000 bytes) sem recompilar. A configuração desta entrega mantém 500 KB. Nginx e multipart aceitam até 6 MB por requisição; o limite de conteúdo mais estrito é validado pela aplicação.

## API

- `GET /api/v1/admin/images/policy`: dimensões, formatos, peso e estado da configuração.
- `POST /api/v1/admin/images`: multipart com campo `file`; responde `{id,contentType,width,height,byteSize,previewUrl}`.
- `POST /api/v1/admin/images/standard`: JSON `{ "name": "ARTGIAN CRM" }`, com o mesmo retorno.
- `GET /api/v1/admin/images/{id}` e `GET /api/v1/admin/images/{id}/content`: metadados/conteúdo restritos ao workspace.
- Aplicações aceitam `spec.imageId`; templates aceitam `spec.imageId`, `spec.imageDisabled` e `spec.headerType` (`NONE`/`IMAGE`).
- Prévia e envio manual aceitam `imageId` opcional; a prévia retorna o ID efetivamente selecionado. Esse ID participa dos hashes de revisão e idempotência.
- A migration V5 adiciona somente `notification_images`; não modifica tabelas existentes. Alterações nos JSONs são aditivas. Versões anteriores não enviam imagens; rollback exige desabilitar temporariamente envios que dependam de imagem para não perder a identidade visual.

## Custos e referências

Há cobrança de armazenamento, requisições PUT/GET e transferência de saída conforme uso; cada identidade é reutilizada por ID, sem upload repetido em cada notificação. Consulta do histórico também gera GET no S3. O limite de 500 KB reduz armazenamento e transferência. O envio não exige CDN, transformação de imagem ou geração por IA em tempo de execução; as quatro artes personalizadas foram criadas uma única vez. Consulte [preços S3](https://aws.amazon.com/s3/pricing/) para valores vigentes.

Referências: [URLs temporárias no AWS SDK Java](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/examples-s3-presign.html), [segurança S3](https://docs.aws.amazon.com/AmazonS3/latest/userguide/security-best-practices.html), [contrato de mídia Evolution](https://github.com/evolution-foundation/evolution-api/blob/main/src/api/dto/sendMessage.dto.ts) e [tipos oficiais de mensagens WhatsApp](https://github.com/WhatsApp/WhatsApp-Nodejs-SDK/blob/main/src/types/messages.ts).

## Teste real autorizado — 19/10/2026

Depois de autorizar apenas os acessos IAM de desenvolvimento e produção, o usuário solicitou uma mensagem de teste para **+55 16 99720-0212**. O contato estava ativo e sem suppression; a consulta de número na instância `MIBESS_NOTIFY` respondeu HTTP 200 e confirmou registro no WhatsApp.

Foi feito um único `sendMedia` pela Evolution já existente, usando a imagem JPEG do padrão com o título MIBESS NOTIFY, **960 × 504 px / 21.115 bytes**, armazenada no S3 com o acesso restrito de produção e disponibilizada por URL temporária do SDK Java. Legenda identifica expressamente o teste solicitado. A Evolution respondeu **HTTP 201**, tipo `imageMessage`, ID `3EB0E11F8AAD38931E708E`. A consulta posterior ao registro exato da mensagem confirmou recibos **DELIVERY_ACK e READ**. Não houve reenvio.

Esse foi um envio de amostra diretamente pelo provedor, sem publicação da versão nova e sem passagem pela fila/histórico do Notify. O estado do teste está em `.local/authorized-image-test-state.json`; o arquivo temporário com URL assinada foi removido. A arte do teste permanece no bucket, privada, sem credenciais embutidas.

## Ativação por aplicação — 19/10/2026

Por solicitação do proprietário, a versão com imagens foi publicada primeiro em [HML](https://github.com/mibess/mibess-notify/actions/runs/39301475058), com aceite fake completo, e depois em [PRD](https://github.com/mibess/mibess-notify/actions/runs/39302382839). As imagens foram geradas pelo endpoint da aplicação já em produção, com fonte do container, formato JPEG e credenciais IAM restritas de PRD. As aplicações que eram fixtures antigas permaneceram desativadas.

| Aplicação | Imagem | Bytes | Notificação de teste | Status confirmado |
|---|---|---:|---|---|
| ARTGIAN STUDIO | `f3520dc0-fd72-42d3-86ba-07ea76268881` | 22710 | `04bdef41-7819-429b-9108-9d0d6ba82191` | DELIVERED |
| ARTGIAN CRM | `bb014da9-8122-4d1a-a721-08c9cfae5847` | 21153 | `95ef77d8-9b09-4168-918e-6f859c70d24e` | DELIVERED |
| SISTEMA COMERCIAL | `fb331560-404b-4ee2-9459-5dd382348bff` | 24704 | `58ead68c-33fc-4be7-bbc1-f732b3fa7bc4` | DELIVERED |
| IA-JUD | `855fe7ff-9c59-4722-b93d-fe7e98daeb2a` | 17943 | `06d161ed-0273-4c88-961d-da0e7f1e94bb` | DELIVERED |

Os quatro testes foram publicados como eventos `NOTIFY_IMAGE_PREVIEW` nas próprias aplicações, destinados exclusivamente ao contato autorizado **+55 16 99720-0212**. O fluxo completo (evento → fila → regra → imagem herdada da aplicação → Evolution → webhook) foi conferido no histórico. Cada envio teve uma tentativa. Foram usadas chaves de API temporárias, revogadas após aceitar cada evento, sem rotação das chaves operacionais. Regras/templates de teste foram desativados após o envio; histórico e auditoria foram preservados.

As regras de negócio existentes conservaram configuração e estado. Os templates ativos da Evolution herdam a imagem da aplicação; os próximos eventos usam a identidade correspondente sem alteração nos publicadores. IA-Jud e Sistema Comercial ficaram com imagem cadastrada para os próximos fluxos; não foram criadas regras de negócio para essas aplicações. O usuário pode substituir as artes pelo upload no painel.
