# Plano e evidências de execução

## Ordem

1. [x] Inspecionar repositório oficial (vazio), liga-pes, Notion e VPS.
2. [x] Documentar arquitetura e infraestrutura sem segredos.
3. [x] Implementar banco, autenticação, CRUD administrativo e eventos idempotentes.
4. [x] Implementar roteamento, outbox, filas, workers, tentativas, consentimento e adaptador Meta (entrega real ainda não validada).
5. [x] Implementar painel responsivo e timeline.
6. [x] Validar unidades, integração PostgreSQL/RabbitMQ e fluxos de aceite com provider fake (13 testes backend, 3 frontend; builds aprovados).
7. [ ] Configurar imagens, CI/CD ECR/SSH, rollback e HTTPS.
8. [ ] Publicar HML e validar pelo navegador.
9. [ ] Publicar PRD, validar domínio e executar evento controlado.
10. [ ] Validar entrega real Meta quando credenciais, números e templates aprovados estiverem disponíveis.

Não considerar configuração de canal como prova de entrega real. Registrar dependências externas e evidências de execução no documento de implantação.

## Bloqueios atuais

- Revisão automática rejeitou provisionamento root de bancos lógicos, RabbitMQ, IAM/OIDC, DNS e Caddy. Aprovação específica solicitada e pendente.
- Revisão automática rejeitou configuração de Actions Secrets/variables e chave de deploy. Aprovação específica solicitada e pendente.
- Canais Meta oficiais sem credenciais/números/templates verificados. O provider fake só é permitido em dev/HML.
- Workflows mantêm CI ativo e condicionam deploy à variable DEPLOY_ENABLED=true, a configurar somente após o provisionamento autorizado.
