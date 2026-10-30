# AppSec program dashboard

*[Leia em português](programa.md)*

[`metricas.md`](metricas.md) shows how the program is doing, for people who decide and do not
read tool alerts: time to fix by severity, how much of it stayed within the deadline, what is open,
how many findings were false positives and which exceptions are about to expire.

## Where the numbers come from

- [`achados.csv`](achados.csv): one finding per line, from every repository in the program, with
  opening date, closing date, outcome and evidence. It includes CodeQL and Dependency-Check alerts
  and also what the automated proofs and code review found.
- each repository's `osv-scanner.toml`: accepted vulnerabilities, each with a reason and a deadline.

Nothing is estimated. Dates come from the GitHub API (alert opened and closed) or from Git (first
commit of the fix and merge to production).

## Deadlines

| Severity | Time to fix |
|---|---|
| critical | 7 days |
| high | 30 days |
| medium | 90 days |
| low | 180 days |

Tool severities outside this scale (CodeQL `note` and `warning`) count as low.

## How to update

```bash
python scripts/metricas_do_programa.py docs/seguranca/achados.csv \
  --aceites feira-do-comando=osv-scanner.toml \
  --aceites authcore=<path>/osv-scanner.toml \
  --saida docs/seguranca/metricas.md
```

CI runs the script's tests and writes the summary, with this repository's exceptions, to the run
summary. The full `metricas.md`, with every repository's exceptions, is generated locally, because
one repository's CI cannot read the others' files.

## What the numbers from 30/10/2026 say

The 40 critical findings took 17.4 days to fix, and none stayed within 7 days. All of them came from
the same place: codereview-ai stuck on Spring Boot 3.3, unsupported since June. Until the framework
was replaced, none of those flaws had a targeted fix. The lesson is about process: an unsupported
framework version must enter the queue as a critical finding on the day support ends, not when the
scanner starts reporting CVEs.

The accepted exceptions (12, none expired) share a pattern in two repositories: two Spring
Framework flaws that are only fixed by Spring Boot 4, a migration already done in codereview-ai.
