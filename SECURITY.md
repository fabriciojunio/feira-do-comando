# Segurança

*[Read this in English](SECURITY.en.md)*

## Como avisar de uma falha

Use o aviso privado do GitHub: aba **Security**, botão **Report a vulnerability**. Se não der,
escreva para **fabricioad444@gmail.com**. Descreva o que encontrou e como reproduzir. Não abra issue
pública com detalhe de falha explorável.

É um projeto de portfólio mantido por uma pessoa: a resposta depende de disponibilidade, e não há
prazo de correção garantido.

## Escopo

O sistema roda só em CI e em ambiente local, com dado fictício. Não há loja de verdade, adquirente
de verdade nem dado pessoal. Interessa qualquer falha nos quatro serviços, no broker, nos
manifestos do Kubernetes, no Terraform e na própria esteira.

A branch do PR #5 tem falhas plantadas de propósito, para provar que os portões barram. Elas não
contam como achado.

## O que está feito

**Broker fechado.** TLS, SASL/SCRAM com um usuário por serviço e ACL mínima: cada serviço só lê e
escreve nos tópicos de que precisa. O job "Kafka seguro" da CI roda `infra/kafka/provar.sh`, que
tenta os acessos proibidos e só passa se forem recusados pelo motivo certo, e depois faz um pedido
de ponta a ponta com os quatro serviços autenticados.

**Erro sem detalhe interno.** Os erros saem no formato Problem Details (RFC 9457). Mensagem de
framework, com tipo Java ou trecho do corpo, fica no log; o cliente recebe texto fixo. Entrada
inválida responde 400, rota inexistente 404 e método não aceito 405, em vez de 500. A regra
própria do Semgrep `excecao-de-framework-na-resposta` barra a volta do vazamento.

**Cabeçalhos.** Toda resposta leva `X-Content-Type-Options: nosniff` e
`Cross-Origin-Resource-Policy: same-origin`.

**Esteira.** A cada push: gitleaks com regra própria, Semgrep com regras próprias testadas mais os
conjuntos públicos, CodeQL, OSV-Scanner, SBOM em SPDX e CycloneDX com Grype, Kyverno barrando
manifesto ruim num cluster `kind`, regras Sigma e Falco testadas, e DAST com o OWASP ZAP nas APIs
de pedidos, estoque e pagamentos a partir do OpenAPI. Actions fixadas por SHA e Dependabot.
Detalhe em [docs/seguranca/](docs/seguranca/).

## Limites conhecidos

- **Não há autenticação de usuário.** O id do cliente chega num cabeçalho. Num sistema real viria
  de um token validado; ficou simples para o foco ser a saga e o broker.
- **O `servico-consulta` fica fora do DAST.** Ele não publica OpenAPI e só lê a projeção, sem
  entrada além do id na rota.
- **Nunca rodou em infraestrutura de produção.** Os manifestos são aplicados num cluster `kind`
  descartável e o Terraform num emulador da AWS.

## Riscos aceitos

Cada aceite tem motivo e data. Vencida a data, o portão volta a reprovar.

| Onde | Achado | Motivo | Até |
|---|---|---|---|
| [`osv-scanner.toml`](osv-scanner.toml) | GHSA-j9f9-w8pj-32f8 (CVE-2026-47890) | exige SSE com fragmentos de view; o projeto não usa nenhum dos dois | 28/01/2027 |
| [`osv-scanner.toml`](osv-scanner.toml) | GHSA-pc63-qcmh-9cmg (CVE-2026-47884) | exige `XsltView`; o projeto não renderiza view | 28/01/2027 |
| [`.gitleaksignore`](.gitleaksignore) | `senha-com-simbolo` em três commits | um nome de variável lido como senha, e a senha fictícia da branch de prova | sem data: o valor não é segredo |
| [`.zap/regras.tsv`](.zap/regras.tsv) | Spring Actuator Information Leak | `/actuator/health` mostra só o status e os grupos, que as sondas do Kubernetes leem | revisto a cada mudança de configuração |
| [`.zap/regras.tsv`](.zap/regras.tsv) | Unexpected Content-Type | HTML só na página de erro padrão, sem mensagem nem pilha, e no Swagger UI; a API responde JSON | revisto a cada mudança de configuração |
| [`.zap/regras.tsv`](.zap/regras.tsv) | XSS persistente em resposta JSON (confiança baixa) | o texto volta dentro de JSON com `nosniff`, que o navegador não executa | revisto a cada mudança de configuração |
