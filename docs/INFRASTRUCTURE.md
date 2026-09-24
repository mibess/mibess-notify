# Discovery — 23/24 setembro de 2026

## Fontes

- Repositório oficial `mibess/mibess-notify`: público, vazio, branch padrão main.
- Checkout local `mibess/liga-pes`, workflow `.github/workflows/deploy-liga-update-VPS-hostinger.yml` e pipeline correspondente: GitHub Actions → AWS ECR us-east-1 → SSH Hostinger. Padrão antigo usa latest e stop/rm, a substituir por SHA e health/rollback no Notify.
- Notion: [variáveis Hostinger](https://app.notion.com/p/33712eca3f52807ca28ec69bdff21e10), [Postgres PRD/HML](https://app.notion.com/p/37912eca3f52806fafd4fb3d652de289).
- Inspeção SSH real: docker ps/ps -a, networks, volumes, stats, inspect selecionado, ss, df/free, nomes de bancos, ECR, firewall.

## Encontrado

- Host `srv932354.hstgr.cloud`; aproximadamente 15.6 GiB RAM, 12 GiB disponíveis, 180 GiB disco disponíveis.
- PostgreSQL `postgres-prd` e `postgres-hml`, versão 15.5, rede `shared-db-network`. Volumes `prd_postgres_prd_data` e `hml_postgres_hml_data`. Produção sem bancos de aplicações além do banco padrão; HML contém `citel-db`.
- Outros serviços ativos: liga-api, agencia-citel API, cards API/Postgres, gerador-uc app/nginx, Evolution API/Redis/Postgres e Postgres da liga. Não serão alterados.
- RabbitMQ `liga-rabbitmq` (rabbitmq:4-management) parado há quatro semanas, rede bridge, portas públicas 5672/15672, restart=no, volume antigo. Não é infraestrutura compartilhada ativa e reiniciá-lo poderia consumir filas antigas. Decisão: um único broker Notify para HML/PRD, vhosts e usuários separados, sem portas públicas, sem tocar na instalação parada.
- Nenhum reverse proxy HTTP/TLS global ativo; portas 80/443 livres. O nginx existente é exclusivo do gerador-uc na porta 8095. Decisão: Caddy dedicado para Notify com certificados ACME e volumes próprios.
- Docker Compose legado 1.29.2; plugin `docker compose` ausente. Usar plugin moderno isolado do projeto, sem atualizar serviços compartilhados.
- Registry AWS ECR já existente e acessível. Reutilizar conta/região; repositórios de imagem próprios do Notify.
- Não foi identificada rotina de backup dos bancos no inventário inicial. Não há migrations destrutivas planejadas; fazer dump do banco Notify antes de updates e manter rollback de imagem compatível.
- `notify.mibess.com.br` sem resposta DNS no momento da inspeção. Configuração de DNS/TLS ainda necessária.

## Isolamento planejado

| Ambiente | Instância existente | Banco próprio | Usuário próprio | Broker vhost |
|---|---|---|---|---|
| HML | postgres-hml | mibess_notify_hml | mibess_notify_hml | /mibess-notify-hml |
| PRD | postgres-prd | mibess_notify | mibess_notify | /mibess-notify-prd |

Somente frontend/proxy publica HTTP. Backend, broker e conexões de bancos usam redes Docker; nenhuma nova porta PostgreSQL pública. Limites de memória/CPU e rotação de logs nos novos containers. Secrets fora do Git, no host com modo 0600 e nos GitHub Actions Secrets.
