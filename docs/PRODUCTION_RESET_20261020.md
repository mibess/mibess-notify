# Limpeza e identidade dos avisos — 20/10/2026

O proprietário autorizou a exclusão do histórico do Notify até 20/10, incluindo testes, a manutenção exclusiva dos projetos Artgian CRM e Artgian Studio e a publicação em produção. O corte é **21/10/2026 às 00h de America/Sao_Paulo**, equivalente a `2026-10-21T03:00:00Z`.

## Estado publicado

- Aplicações: `ARTGIAN` / **Artgian Studio** e `ARTGIAN_CRM` / **Artgian CRM**. Chaves operacionais preservadas, uma ativa por aplicação.
- **44 regras e 44 templates ativos**, com condições sem sobreposição. Regras amplas e antigas removidas. Canal Evolution existente mantido; rascunho Meta sem uso removido.
- **40 artes por origem/evento**, mais as duas identidades padrão. Cada JPEG de evento tem 960 × 320 px e 24–37 KB. Uploads e downloads autenticados conferidos por SHA-256; sete arquivos antigos sem uso foram removidos após cópia protegida.
- Destinatários: teste terminado em **0212**; definitivo terminado em **2741**. Os dois grupos operacionais contêm somente o contato definitivo, com consentimentos existentes preservados. Não houve envio de teste ao contato definitivo.
- Títulos seguem `Aplicação - Evento`, com Shopee, Mercado Livre, Artgian Site ou Artgian CRM. O restante da mensagem, seus links e variáveis foi preservado.
- Notify HTTPS/health **UP**. A configuração é compatível com o runtime existente, sem migration ou troca de containers.

## Aplicações de origem

CRM: commit [`be48df2f90186987ed3d2621b6b236aaf89fd6f5`](https://github.com/mibess/artgian-crm/commit/be48df2f90186987ed3d2621b6b236aaf89fd6f5), [deploy Vercel READY](https://vercel.com/claudemir-custodios-projects/artgian-crm/CRWsYmPPwuhguTZ33PPLmPPhoWcW), alias `crm.artgian.com.br` conferido. Publicação a partir de arquivo isolado desse commit; alterações locais concorrentes não foram incorporadas. Lint, typecheck, **379 testes**, build local e build Vercel aprovados. Os novos títulos aparecem no painel e no evento publicado; corpo, metadados e idempotência não mudaram.

Studio: os textos vivem nos templates do Notify, atualizados diretamente em produção. Nenhuma alteração de código ou novo deploy foi necessário. Deploy existente `dpl_A9sF3LM6cp4phAzdMt7oUPUs9Jh7` conferido READY. Outbox de produção consultada: um evento antigo aceito, zero pendentes/em publicação/falhos. CRM também tinha zero eventos pendentes antes do trabalho. Pedidos, clientes, custos e checkpoints dos sistemas de origem foram preservados.

## Aceite e limpeza

As 44 regras foram simuladas no servidor com título e corpo esperados, sem problemas e exatamente um destinatário autorizado. A matriz local verifica correspondência única para todas as combinações suportadas, incluindo estados de pagamento, entrega e reconexão.

Oito eventos controlados cobriram as três origens, as duas aplicações, novo pedido, entrega, cancelamento e reconexão. Um nono teste foi disparado pelo próprio CRM publicado. **Todos chegaram a DELIVERED, com uma única tentativa, imagem esperada e somente o contato de teste.** As chaves temporárias foram revogadas e excluídas; as chaves reais não foram rotacionadas. Após os testes, os dois grupos passaram ao destinatário definitivo e as 44 simulações foram repetidas sem enviar mensagens.

A primeira limpeza removeu 29 eventos e 31 notificações. A segunda removeu os 13 eventos/notificações surgidos durante o trabalho (nove testes e quatro avisos de sincronização), além de tentativas, timelines, outbox, webhooks e auditorias anteriores ao corte. Seis projetos extras, cadastros de teste e configurações substituídas foram excluídos. Conferência final: **zero eventos, notificações e auditorias** naquele instante, apenas as duas aplicações e configuração final. O teste criado no CRM também foi excluído; as 20 notificações restantes foram conferidas por hash, e os dados comerciais não mudaram.

Evidências privadas, sem credenciais no Git: `.local/event-notification-rollout-20261020.json`, `.local/cleanup-20261020-before.json` e `.local/unused-images-20261020.json`. Backups protegidos no servidor, em `/opt/mibess-notify/backups/prd/`, incluem o banco antes da limpeza, a configuração após o rollout e as imagens descartadas. A leitura dos arquivos de dump foi verificada com `pg_restore --list`; isso não equivale a um ensaio completo de restauração.

## Virada do dia

Uma limpeza **única**, ainda agendada no encerramento deste trabalho, retira os registros do dia 20 que surgirem após a conferência final. O timer persistente `notify-history-cutover-20261021.timer` foi instalado e conferido ativo, com execução em **21/10 às 00h05 de São Paulo**. Tem limite absoluto de tentativas às 00h35, backup obrigatório, transação e marcador de conclusão. Preserva os novos eventos a partir de 00h, configurações, contatos e consentimentos. Não é uma política recorrente de retenção.

Procedimento e evidências da execução futura: [operação do corte](../infrastructure/HISTORY_CUTOVER_20261021.md). O histórico de amanhã começa em zero sem exigir a remoção de dados comerciais das aplicações de origem.
