# Security

*[Versão em português](SECURITY.md)*

## Reporting a flaw

Use GitHub private reporting: **Security** tab, **Report a vulnerability**. If that is not
possible, email **fabricioad444@gmail.com**. Describe what you found and how to reproduce it. Do not
open a public issue with details of an exploitable flaw.

This is a portfolio project maintained by one person: response depends on availability, and there
is no guaranteed fix deadline.

## Scope

The system only runs in CI and locally, with fictitious data. There is no real shop, no real card
acquirer and no personal data. Any flaw in the four services, the broker, the Kubernetes manifests,
the Terraform or the pipeline itself is of interest.

The PR #5 branch has flaws planted on purpose, to prove the gates block them. They do not count as
findings.

## What is in place

**Closed broker.** TLS, SASL/SCRAM with one user per service and minimal ACLs: each service only
reads and writes the topics it needs. The CI job "Kafka seguro" runs `infra/kafka/provar.sh`, which
attempts the forbidden accesses and only passes if they are refused for the right reason, then
runs an end-to-end order through the four authenticated services.

**Errors without internal detail.** Errors use Problem Details (RFC 9457). Framework messages, with
Java types or body fragments, stay in the log; the client gets fixed text. Invalid input answers
400, an unknown route 404 and an unsupported method 405, instead of 500. The custom Semgrep rule
`excecao-de-framework-na-resposta` blocks the leak from coming back.

**Headers.** Every response carries `X-Content-Type-Options: nosniff` and
`Cross-Origin-Resource-Policy: same-origin`.

**Pipeline.** On every push: gitleaks with a custom rule, Semgrep with tested custom rules plus the
public rulesets, CodeQL, OSV-Scanner, SPDX and CycloneDX SBOMs with Grype, Kyverno rejecting bad
manifests in a `kind` cluster, tested Sigma and Falco rules, and DAST with OWASP ZAP against the
orders, stock and payments APIs, driven by their OpenAPI. Actions pinned by SHA, and Dependabot.
Details in [docs/seguranca/](docs/seguranca/).

## Known limitations

- **There is no user authentication.** The customer id arrives in a header. A real system would
  take it from a validated token; it was kept simple to keep the focus on the saga and the broker.
- **`servico-consulta` is outside DAST.** It publishes no OpenAPI and only reads the projection,
  with no input beyond the id in the route.
- **It has never run on production infrastructure.** The manifests are applied to a throwaway
  `kind` cluster and the Terraform to an AWS emulator.

## Accepted risks

Each acceptance has a reason and a date. When the date passes, the gate fails again.

| Where | Finding | Reason | Until |
|---|---|---|---|
| [`osv-scanner.toml`](osv-scanner.toml) | GHSA-j9f9-w8pj-32f8 (CVE-2026-47890) | requires SSE with view fragments; the project uses neither | 2027-01-28 |
| [`osv-scanner.toml`](osv-scanner.toml) | GHSA-pc63-qcmh-9cmg (CVE-2026-47884) | requires `XsltView`; the project renders no views | 2027-01-28 |
| [`.gitleaksignore`](.gitleaksignore) | `senha-com-simbolo` in three commits | a variable name read as a password, and the fictitious password in the proof branch | no date: the value is not a secret |
| [`.zap/regras.tsv`](.zap/regras.tsv) | Spring Actuator Information Leak | `/actuator/health` only shows status and groups, which the Kubernetes probes read | reviewed on every configuration change |
