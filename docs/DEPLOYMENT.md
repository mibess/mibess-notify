# Implantação

## Estado

O inventário foi executado na VPS. A execução inicial de `infrastructure/provision.py` foi bloqueada pela revisão automática por agregar alterações de banco, AWS e rede com root. A configuração dos GitHub Actions Secrets/variables e da chave dedicada também foi rejeitada por criar acesso privilegiado de deploy. As duas aprovações explícitas estão pendentes. **Não houve provisionamento, configuração de secrets ou deploy HML/PRD.** Atualizar esta seção apenas após evidência real.

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
5. Instala Compose em `/opt/mibess-notify/bin/docker-compose`, sem alterar o Compose legado. Cria repositórios ECR com tags imutáveis e role OIDC limitada às branches main/develop deste repositório.
6. Adicionar chave de deploy dedicada à VPS, com forced command `infrastructure/ssh-entrypoint.sh`, sem forwarding/PTY. Não usar chave AWS estática ou credenciais administrativas de PostgreSQL na aplicação.

## GitHub Actions

Variables do repositório:

| Nome | Finalidade |
|---|---|
| AWS_ROLE_ARN | Role OIDC `mibess-notify-github-deploy`, limitada ao ECR do Notify |
| ECR_REGISTRY | Host do registry ECR existente, sem protocolo |
| HOSTINGER_HOST | Host SSH da VPS |
| HOSTINGER_USER | Usuário de deploy |
| DEPLOY_ENABLED | `true` somente após provisionamento aprovado e validação HML; ausente mantém CI ativo e deploy bloqueado |

Secrets:

| Nome | Finalidade |
|---|---|
| HOSTINGER_SSH_PRIVATE_KEY | Chave dedicada com comando restrito ao Notify |
| HOSTINGER_KNOWN_HOSTS | Chave pública verificada do host, com StrictHostKeyChecking |

Secrets de runtime são mantidos **no host**, fora do repositório e do build, em `/opt/mibess-notify/secrets/{hml,prd}.env`: DB_PASSWORD, RABBITMQ_PASSWORD, MASTER_ENCRYPTION_KEY, ADMIN_EMAIL e ADMIN_PASSWORD de bootstrap. `broker.env` contém a credencial administrativa exclusiva do broker. Eles não precisam transitar por Actions. Credenciais Meta entram pelo painel e são criptografadas no banco.

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
