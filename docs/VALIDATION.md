# Validação — 23/24 setembro de 2026

## Executado

- Backend `./mvnw clean verify`: **13 testes passaram**, sem falhas nem testes ignorados; PostgreSQL 15.5 e RabbitMQ 4.1 reais via Testcontainers.
- Frontend `npm ci`, `npm run build`, `npm test`: build aprovado, **3 testes passaram**.
- Imagens Docker de backend e frontend construídas e iniciadas localmente. Nginx → backend → PostgreSQL/RabbitMQ retornou health UP; o mesmo teste de aceite passou através do proxy na porta local 8392.
- CI remoto no GitHub: [execução inicial aprovada](https://github.com/mibess/mibess-notify/actions/runs/35941638799). Backend/frontend passaram; job de deploy foi deliberadamente pulado pois DEPLOY_ENABLED não foi habilitada.
- Aceite HTTP real local com `infrastructure/smoke.py`: ORDER_PAID → grupo → fake SENT → webhook assíncrono DELIVERED; repetição idempotente; timeline; evento condicional para EVENT_RECIPIENT; opt-out CANCELLED. Chave revogada e configurações de teste desativadas após validar. Nenhuma mensagem real foi enviada.
- Navegador: login com sessão/CSRF, dashboard, edição de aplicação e persistência, navegação móvel, tabela de notificações e timeline com tentativa registrada. Larguras de desktop e celular verificadas; overflow externo móvel corrigido.
- A inspeção visual identificou timestamps Meta com precisão de segundos podendo preceder visualmente etapas internas do mesmo segundo. Timeline agora ordena a sequência de registro pelo ID, preservando os timestamps originais. A correção foi confirmada no teste de integração e no navegador, com DELIVERED após SENT.
- Varredura de marcadores de segredos no código: sem chaves privadas, tokens GitHub ou access keys AWS. Arquivos locais de runtime ignorados pelo Git.

## Cobertura relevante

| Regra | Evidência |
|---|---|
| Event distinto de Notification | Cenários de roteamento e evento NO_MATCH |
| API key e application corretas | Autenticação, revogação e application inferida no endpoint |
| Idempotência concorrente | 16 chamadas em 8 threads → um evento/notificação e 15 duplicatas registradas |
| Workspace | Recurso de outro workspace não é acessível |
| Consentimento | Falta de evidência/opt-out rejeitados; suppression impede envio |
| Retry transitório | Primeira tentativa falha, segunda envia; ambas persistidas |
| Resultado incerto | Retry sem reconciliação retorna 409; explícito mantém histórico |
| Assinatura | HMAC dos bytes exatos; corpo alterado/assinatura ausente rejeitados |
| Entrega | SENT, DELIVERED e READ separados e transições sem regressão |
| Controle administrativo | Sessão obrigatória, CSRF obrigatório, VIEWER sem escrita |

## Ainda não validado

Entrega real Meta e exercício de rollback sob falha induzida ainda não foram executados. Os canais reais permanecem sem credenciais/números/templates verificados. A publicação da plataforma não comprova o aceite de entrega real pelo WhatsApp.

## Homologação pública — 24/09/2026

- [Pipeline HML aprovada](https://github.com/mibess/mibess-notify/actions/runs/35944757394), commit `75db89e0d8727248833840538e458df391c3bdf7`. Imagens imutáveis ECR e deploy por SSH restrito.
- `https://notify-hml.mibess.com.br`: TLS validado, frontend HTTP 200 e health UP. Login/sessão/CSRF confirmados pelo cliente HTTP de aceite e tela pública carregada no navegador.
- Aceite completo pelo domínio: ORDER_PAID → grupo → fake SENT → webhook DELIVERED; idempotência; condição OUT_FOR_DELIVERY → EVENT_RECIPIENT; suppression. Notificação entregue `f6fd9951-6ca6-483a-9d70-1d009fe61f8d`. Chave de teste revogada e configurações desativadas.
- CSP, HSTS, X-Frame-Options e nosniff presentes; API administrativa sem sessão retorna 401 e `/actuator/env` retorna 404.

## Release final HML e PRD — 24/09/2026 (UTC)

- Mesmo commit de aplicação `e7a14e57eb51d9cee937983975ea26b531a4c137` nos dois ambientes: [HML](https://github.com/mibess/mibess-notify/actions/runs/35945938671) e [PRD](https://github.com/mibess/mibess-notify/actions/runs/35946472811), ambos aprovados.
- A inspeção visual detectou incompatibilidade entre o carregador de CSS crítico do Angular e a CSP. Corrigido com `inlineCritical=false`, mantendo a política de segurança. O build agora rejeita HTML com eventos inline; telas públicas verificadas visualmente nos dois domínios.
- PRD: login/sessão/CSRF, chave de API, evento persistido, outbox, RabbitMQ e roteamento NO_MATCH aprovados pelo cliente HTTP de aceite. Sem destinatário e sem mensagem externa. Chaves de teste revogadas, aplicações de teste desativadas e histórico preservado.
- Health UP em ambos os domínios; migrations V1/V2 aplicadas com sucesso. Filas de eventos, entregas e webhooks com consumidores ativos; DLQ vazia. PRD confirmou `NOTIFY_ENV=prd` e `FAKE_PROVIDER_ENABLED=false`.
- Backups anteriores à atualização: HML 63.984 bytes e PRD 58.770 bytes. Release anterior `75db89e0d8727248833840538e458df391c3bdf7` registrada para rollback de imagem. Restauração sob falha induzida não foi exercitada.
- Os IDs dos 12 containers preexistentes permaneceram iguais. Verificação de logs dos backends sem WARN/ERROR no intervalo consultado. Nenhum valor secreto publicado.

## Envios manuais — 24/09/2026

- Versão `587300fb0233af46886a96ae79440ec9dcb62cbf`: **22 testes de backend e 6 de frontend passaram**, com build de produção e verificação CSP aprovados.
- Cobertura: prévia sem envio, confirmação com conteúdo validado, idempotência, permissões/CSRF, consentimento/supressão, alteração de cadastro após revisão, parâmetros de template, retry preservando conteúdo e cancelamento antes da entrega quando contato/template muda.
- Navegador local: contato → texto → revisão → confirmação com provider FAKE → histórico SENT, autor e mensagem registrados, uma tentativa. Layout móvel sem overflow horizontal; tamanho padrão restaurado. Fixtures locais desativadas depois do teste.
- [HML aprovada](https://github.com/mibess/mibess-notify/actions/runs/35952815011). Aceite no domínio público: prévia sem criar notificação, repetição idempotente e conflito com conteúdo alterado, fila SENT e webhook simulado DELIVERED. Notificação `7d7a56f7-2070-4b94-b8b0-360fd8bd7900`, uma tentativa. Canal/contato de teste desativados; nenhuma mensagem real enviada nesse aceite.
- A migração V3 preserva os registros anteriores. A limitação de rollback após aceitar envios manuais está registrada em `DEPLOYMENT.md`.
- [PRD aprovada](https://github.com/mibess/mibess-notify/actions/runs/35953270254), mesma versão de HML. Ambos os domínios retornaram health UP e serviram `main-POTWM5VG.js` com a nova funcionalidade. Login/CSRF e prévia manual com o canal Evolution de produção passaram; a consulta antes/depois confirmou zero notificações criadas. Nenhuma confirmação de envio real foi chamada nesta validação.

## Artgian CRM — 19/10/2026

Diagnóstico confirmado na sessão administrativa de produção: evento `b7b76a27-3d2d-4bc4-9557-0f6997e63fba`, application `e1f3e4cd-97d9-4e0c-9f36-327ca025fe9e` (`ARTGIAN_CRM`) ativa, status NO_MATCH com error_code nulo. As nove regras existentes não cobriam nenhum evento `CRM_*`; a causa foi ausência de regra para o tipo da aplicação, antes da resolução de destinatários.

Configuração inicial aplicada exclusivamente ao CRM: grupo ativo `ARTGIAN_CRM_OPERATIONS` com um único contato já ativo e consentido; template Evolution `crm_aviso`, pt_BR, aprovado localmente, com `title,message,url`; oito regras exatas sem condições, incluindo CRM_NOTIFY_TEST, prioridade HIGH em CRM_SYNC_FAILED e NORMAL nas demais. Cadastros e regras do Studio preservados. Nenhum consentimento novo registrado.

- Backend local: `./mvnw clean verify`, **36 testes aprovados**, sem falhas/erros/ignorados; PostgreSQL 15.5 e RabbitMQ 4.1 reais via Testcontainers, migrations V1→V4. Cobertura adicional de todos os motivos, reprocessamento concorrente (200/409, uma notificação e uma auditoria), catálogo do último payload, prefixos/prioridades, simulação sem envio, roles/CSRF, recuperação manual por autor e health Evolution por GET.
- Compatibilidade Flyway: PostgreSQL 15.5 isolado recebeu V1→V4; Flyway 11.7.2 configurado em seguida somente com V1/V2/V3 executou `validate()` e `migrate()` com sucesso e zero migrations novas, usando o padrão `*:future`. A imagem anterior completa não foi iniciada nesse exercício; a restauração das regras exatas continua necessária para rollback funcional.
- Frontend local: `npm ci`, build de produção com verificação CSP e **14 testes aprovados**. Cobertura de navegação agrupada/abas, oito passos, retomada, chave somente em memória, confirmação explícita para um contato, resposta perdida, invalidação de teste após alteração da configuração e variáveis aninhadas.
- CRM: lint, typecheck, build, **298 unitários e 16 e2e aprovados**, com bancos isolados e transporte fictício, incluindo concorrência e resposta perdida. Verificação visual em 390 px sem overflow. A publicação e o aceite remoto serão registrados após sua execução.

Os resultados acima são locais/configuração; não afirmam publicação desta versão nem confirmação de entrega real. Evidências de pipelines e aceite devem ser adicionadas abaixo quando concluídas.
