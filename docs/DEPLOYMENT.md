# Implantação

## Estado

Provisionamento e acesso de deploy aprovados explicitamente pelo proprietário e executados em 24/09/2026 (UTC). HML e PRD publicados e saudáveis no commit `e7a14e57eb51d9cee937983975ea26b531a4c137`, usando as mesmas imagens imutáveis ECR. A release anterior e os backups próprios estão registrados na VPS.

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
