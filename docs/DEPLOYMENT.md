# Implantação

## Estado

HML e PRD publicados e saudáveis em 19/10/2026 no commit `97f9800b8445ae1d66388d79f0fbb82956a2775c`, com imagens ECR identificadas pelo SHA. O aceite HML usou provider fake; o aceite PRD reprocessou uma vez o evento original do CRM e recebeu confirmação Evolution DELIVERED. A release anterior e os backups próprios estão registrados na VPS. Veja a publicação de 19/10 ao final e [VALIDATION.md](VALIDATION.md).

O provisionamento inicial e o acesso de deploy foram aprovados explicitamente pelo proprietário e executados em 24/09/2026 (UTC). Evidências desse marco:

- [Pipeline HML aprovada](https://github.com/mibess/mibess-notify/actions/runs/35945938671).
- [Pipeline PRD aprovada](https://github.com/mibess/mibess-notify/actions/runs/35946472811).
- GitHub Actions Secrets/variables configurados; a chave dedicada recusa comandos fora de `deploy hml|prd <SHA>`.
- HML: aceite completo com provider fake e confirmação DELIVERED. PRD: login/CSRF e evento controlado processado como NO_MATCH, sem mensagem externa.
- Os 12 containers preexistentes mantiveram seus IDs. Nenhum PostgreSQL foi criado ou reiniciado.

A entrega Meta real ainda não foi validada. Consulte [VALIDATION.md](VALIDATION.md) para evidências e limites.

## Topologia

GitHub Actions → testes → imagens ECR com SHA imutável → SSH restrito → Hostinger → health check. HML usa develop; PRD usa main. DNS Route53 aponta diretamente para a Hostinger; Caddy gerencia HTTPS, sem Vercel como runtime.

| Item | HML | PRD |
|---|---|---|
| Domínio | notify-hml.mibess.com.br | notify.mibess.com.br |
| Frontend | mibess-notify-frontend-hml | mibess-notify-frontend-prd |
| Backend | mibess-notify-backend-hml | mibess-notify-backend-prd |
| PostgreSQL existente | postgres-hml | postgres-prd |
| Banco/usuário | mibess_notify_hml | mibess_notify |
| Vhost | /mibess-notify-hml | /mibess-notify-prd |
| Porta de health local | 127.0.0.1:8391 | 127.0.0.1:8390 |

Nenhum Compose HML/PRD cria PostgreSQL. O broker Notify é único, com usuários/vhosts separados e nenhuma porta pública. Proxy é o único novo serviço com portas públicas 80/443.

## Bootstrap revisável

1. Confirmar inventário atual, portas 80/443 livres, nomes de recursos sem colisão e rede `shared-db-network` existente.
2. Copiar `infrastructure/` para `/opt/mibess-notify/infrastructure/`.
3. Executar o provisionamento aprovado: `python3 /opt/mibess-notify/infrastructure/provision.py`.
4. Script preserva secrets existentes, gera novos valores aleatórios com modo 0600, cria somente bancos/usuários próprios e não reinicia os PostgreSQL.
5. Instala Compose em `/opt/mibess-notify/bin/docker-compose`, sem alterar o Compose legado. Cria repositórios ECR com tags imutáveis e role OIDC limitada às branches main/develop deste repositório, usando o subject imutável `repo:mibess@11463771/mibess-notify@1384348076:ref:refs/heads/<branch>`.
6. Adicionar chave de deploy dedicada à VPS, com forced command `infrastructure/ssh-entrypoint.sh`, sem forwarding/PTY. Não usar chave AWS estática ou credenciais administrativas de PostgreSQL na aplicação.

## GitHub Actions

Variables do repositório:

| Nome | Finalidade |
|---|---|
| AWS_ROLE_ARN | Role OIDC `mibess-notify-github-deploy`, limitada ao ECR do Notify |
| ECR_REGISTRY | Host do registry ECR existente, sem protocolo |
| HOSTINGER_HOST | IPv4 verificado da VPS; evita falha de conectividade IPv6 no runner |
| HOSTINGER_USER | Usuário de deploy |
| DEPLOY_ENABLED | `true` somente após provisionamento e acesso de deploy aprovados/configurados; ausente mantém CI ativo e deploy bloqueado |

Secrets:

| Nome | Finalidade |
|---|---|
| HOSTINGER_SSH_PRIVATE_KEY | Chave dedicada com comando restrito ao Notify |
| HOSTINGER_KNOWN_HOSTS | Chave pública verificada do host, com StrictHostKeyChecking |

Secrets de runtime são mantidos **no host**, fora do repositório e do build, em `/opt/mibess-notify/secrets/{hml,prd}.env`: DB_PASSWORD, RABBITMQ_PASSWORD, MASTER_ENCRYPTION_KEY, ADMIN_EMAIL e ADMIN_PASSWORD de bootstrap. `broker.env` contém a credencial administrativa exclusiva do broker. Eles não precisam transitar por Actions. Credenciais Meta entram pelo painel e são criptografadas no banco.

Após habilitar deploy, execute primeiro o workflow de HML na branch develop e conclua seu aceite. Só então dispare o workflow de PRD na main. Novos pushes nessas branches passam a disparar seus respectivos deploys automaticamente.

## Deploy e rollback

```bash
/opt/mibess-notify/infrastructure/deploy.sh hml COMMIT_SHA_DE_40_CARACTERES
```

O script trava concorrência por ambiente, valida configuração, baixa imagens antes de alterar containers, faz backup apenas do banco Notify, inicia a versão, espera health backend + HTTP frontend e só então registra `state/<ambiente>.current`. Falha tenta restaurar a última imagem saudável e valida o rollback. Não é blue/green; pode haver breve indisponibilidade durante a troca.

Rollback explícito: execute o script com o SHA em `state/<ambiente>.previous`. Migrations devem continuar compatíveis com a imagem anterior. O script **não reverte schema automaticamente** nem restaura banco por cima de dados novos.

Backups locais: `/opt/mibess-notify/backups/<ambiente>/*.dump`, modo restrito. A cópia off-site e a política de retenção precisam seguir a política do proprietário; o script não apaga backups nem histórico automaticamente.

## Validação obrigatória

- `docker ps` e health UP dos novos containers.
- HTTPS válido nos dois domínios, frontend 200 e `/actuator/health` UP.
- Login/CSRF, criação de API key, evento idempotente e consulta de timeline.
- HML: fake provider → SENT → webhook fake → DELIVERED; também regra condicional EVENT_RECIPIENT e suppression.
- PRD: evento controlado sem destinatário real; envio Meta só após credenciais/números/templates/consentimento válidos.
- Confirmar que containers compartilhados mantêm os mesmos IDs e uptime.

Consulte [META.md](META.md) para o que depende da conta Meta. Publicar o painel não comprova a entrega de mensagens WhatsApp.

## Migração V3 — envios manuais

A V3 adiciona origem/autor/idempotência às notificações e permite referências nulas somente nos envios manuais, com CHECK por origem. É aditiva e preserva históricos existentes. Depois de aceitar notificações manuais, não reverta para imagens anteriores à V3 com mensagens manuais pendentes: esses workers antigos exigem aplicação/regra/template. Em uma recuperação, interrompa os workers, reconcilie os manuais em processamento e prefira corrigir mantendo a versão compatível com V3. Não reverta a migration nem apague histórico para fazer rollback.

## Migração V4 — integração e diagnóstico

A V4 adiciona `events.routing_diagnostics` com default `{}`, a tabela `event_timeline` e índices de consulta. Não remove nem renomeia colunas, não altera os eventos antigos e não exige reescrita dos contratos existentes. A imagem V3 pode iniciar com o schema V4; mantenha as migrations aplicadas durante rollback.

A compatibilidade de schema não faz uma imagem anterior compreender `eventTypes` ou `eventPriorities`: ela lê somente `eventType` e `priority`. Antes de voltar à imagem anterior, desative a regra de múltiplos tipos e restaure regras individuais, com tipo exato e prioridade correspondente. Na integração CRM, conserve as oito regras individuais desativadas como configuração de recuperação; reative-as somente após desativar a regra consolidada. Nunca deixe ambos os conjuntos ativos, pois regras distintas podem criar duas notificações para o mesmo evento/destinatário. Coordene a troca com o publicador e confira eventos NO_MATCH no intervalo; não altere a regra Studio `ORDER_PAID`.

O aceite fake em `infrastructure/smoke.py` verifica agora diagnóstico NO_MATCH, catálogo, simulação sem entrega, reprocessamento auditado sem duplicatas, prefixos/prioridade por evento e saúde, além de SENT→DELIVERED, evento idempotente, destinatário do evento e suppression. Execute somente no ambiente HML/local, com as credenciais já existentes no ambiente operacional. O script não imprime chaves e revoga a chave de teste e desativa os cadastros ao finalizar.

### Publicação de 19/10/2026

- HML publicou `97f9800b8445ae1d66388d79f0fbb82956a2775c` pela [execução 39272925805](https://github.com/mibess/mibess-notify/actions/runs/39272925805). Testes remotos e deploy aprovados; aceite fake completo, inclusive diagnóstico/reprocessamento, aprovado após o health UP.
- PRD publicou o mesmo commit após o aceite HML e autorização explícita do proprietário: [execução 39274147763 aprovada](https://github.com/mibess/mibess-notify/actions/runs/39274147763), incluindo CI, deploy Hostinger e HTTPS público. Health público UP e login/sessão/CSRF verificados novamente após a publicação.
- Somente os quatro containers frontend/backend Notify de HML/PRD foram substituídos. Os dez demais IDs permaneceram iguais; PostgreSQL HML/PRD, Evolution API/Redis/PostgreSQL, RabbitMQ, proxy e serviço de segredos mantiveram também o horário de início. `liga-api` e `kronosmind-backend` já estavam em ciclos de reinício antes da publicação e continuaram com os mesmos IDs, sem intervenção nesta tarefa.
- Configuração PRD: regra consolidada `ARTGIAN_CRM_AVISOS` ativa, com os sete eventos de negócio e CRM_NOTIFY_TEST, prioridade HIGH em CRM_SYNC_FAILED e NORMAL nas demais. As oito regras exatas permanecem desativadas para recuperação, sem sobreposição. A regra Studio ORDER_PAID foi preservada.
- Depois do health UP do Notify, CRM `ddd85476b21aa660c4138f460c0ef5ec9488cde4` publicado na Vercel: [`dpl_2WY1GfMGnjACx5F3JosmXxQzHMjh`](https://vercel.com/claudemir-custodios-projects/artgian-crm/2WY1GfMGnjACx5F3JosmXxQzHMjh), READY, alias `crm.artgian.com.br`. Documentação posterior não altera os artefatos implantados.
- Único teste real: reprocessamento do evento CRM original às 12:07:04 de 19/10/2026 (Brasília), seguido de SENT às 12:07:05 e DELIVERED às 12:07:06, uma tentativa para o único contato autorizado. Nenhum teste adicional foi disparado pelo botão do CRM.
- Aceite encerrado em 19/10/2026: proprietário confirmou o recebimento correto da mensagem com título e link; card autenticado do CRM conferido como Ativado, destino PRD, 0 pendentes / 1 aceito / 0 falhas. Nenhuma publicação ou mensagem adicional foi necessária para essa confirmação.
