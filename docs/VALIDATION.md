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

Provisionamento e deploy HML/PRD estão bloqueados por aprovação automática; DNS/TLS público, imagens em ECR, health na VPS, rollback real e entrega Meta não foram executados. Os canais reais permanecem sem credenciais verificadas. O projeto não deve ser considerado operacional em produção até concluir esses itens.
