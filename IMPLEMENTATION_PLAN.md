# Plano e evidências de execução

## Ordem

1. [x] Inspecionar repositório oficial (vazio), liga-pes, Notion e VPS.
2. [x] Documentar arquitetura e infraestrutura sem segredos.
3. [x] Implementar banco, autenticação, CRUD administrativo e eventos idempotentes.
4. [x] Implementar roteamento, outbox, filas, workers, tentativas, consentimento e adaptador Meta (entrega real ainda não validada).
5. [x] Implementar painel responsivo e timeline.
6. [x] Validar unidades, integração PostgreSQL/RabbitMQ e fluxos de aceite com provider fake (13 testes backend, 3 frontend; builds aprovados).
7. [x] Configurar imagens, CI/CD ECR/SSH, rollback e HTTPS.
8. [x] Publicar HML e validar pelo navegador.
9. [x] Publicar PRD, validar domínio e executar evento controlado.
10. [ ] Validar entrega real Meta quando credenciais, números e templates aprovados estiverem disponíveis.

Não considerar configuração de canal como prova de entrega real. Registrar dependências externas e evidências de execução no documento de implantação.

## Estado final da implantação

- Aprovações específicas recebidas; provisionamento, Actions Secrets/variables e acesso SSH restrito executados.
- HML e PRD publicados no commit `e7a14e57eb51d9cee937983975ea26b531a4c137`, com pipelines aprovadas, HTTPS e health UP. Evidências em [VALIDATION.md](docs/VALIDATION.md).
- Provider fake desabilitado em PRD. Os canais Meta oficiais continuam sem credenciais/números/templates verificados; o aceite de entrega real depende dessa configuração e do consentimento dos destinatários.
- CI/CD ativo: develop publica HML; main publica PRD, sempre após testes. Novas versões devem ser promovidas de HML para PRD após o aceite.
