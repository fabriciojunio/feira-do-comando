# Custom Semgrep rules

*[Leia em português](semgrep.md)*

The public rule packs (`p/java`, `p/owasp-top-ten`, `p/security-audit`) catch what applies to any
Java project. The rules in `.semgrep/regras` catch what is specific to a Spring and Kafka system
like this one, which the public packs do not cover. Each has a test file in `.semgrep/testes`, with
the cases it must catch (`ruleid`) and the ones it must not (`ok`).

## The rules

| Rule | What it catches | CWE |
|---|---|---|
| `excecao-de-framework-na-resposta` | generic or framework exception messages (Jackson, SQL, arithmetic) sent in the response | CWE-209 |
| `kafka-pacotes-confiaveis-curinga` | Spring Kafka `JsonDeserializer` trusting any package, in Java | CWE-502 |
| `kafka-pacotes-confiaveis-config` | the same, in `application.yml` | CWE-502 |
| `consulta-montada-por-concatenacao` | JPQL or SQL built with `+` or `String.format` | CWE-89 |
| `random-para-segredo` | `Random` or `ThreadLocalRandom` in a method that generates a token, password, code or key | CWE-330 |
| `cors-qualquer-origem` | CORS with `*` or `@CrossOrigin` without an origin | CWE-942 |

## Measurement

Run on 29/10/2026 against the code of four Java projects, 312 files in total, excluding tests.
Every finding was checked by hand.

**First version of the rules:**

| Project | Findings | True | False positives |
|---|---|---|---|
| Feira do Comando | 3 | 1 (`TratadorDeErros`, Jackson message in the response) | 2 (`AdquirenteSimulado`, simulation draw) |
| Vitrine Bauru | 1 | 0 | 1 (message only released after `instanceof`) |
| alicerce | 0 | 0 | 0 |
| brecha | 1 | 1 (deliberately planted flaw, BRECHA-008) | 0 |
| **Total** | **5** | **2** | **3** |

40% precision: a rule like that gets switched off by the team in its second week. Two changes:

- `random-para-segredo` now requires a method whose name suggests a secret. A simulation draw is not
  a secret, and the generic rule had no way of knowing.
- `excecao-de-framework-na-resposta` now ignores messages guarded by `instanceof` and messages sent
  to the log. The first attempt at ignoring logs, filtering by log level, also dropped brecha's true
  positive; the measurement caught it, and the rule now uses one pattern per level.

**Current version**, after fixing Feira's `TratadorDeErros`:

| Project | Findings | True | False positives |
|---|---|---|---|
| Feira do Comando | 0 | 0 | 0 |
| Vitrine Bauru | 0 | 0 | 0 |
| alicerce | 0 | 0 | 0 |
| brecha | 1 | 1 | 0 |

100% precision on this set, without losing the true positive.

**The limit of this measurement:** it says how often a rule is wrong when it fires, not how much it
misses. Measuring misses would need a body of code with the flaws labelled beforehand. The files in
`.semgrep/testes` are the start of that set: each `ruleid` case is a known flaw the rule must keep
catching.

## Custom gitleaks rule

The `prova-dos-portoes` branch planted four flaws and opened a PR to production. Semgrep, CodeQL and
OSV-Scanner failed theirs. gitleaks **passed** the password written in the code: its generic rule
only recognises secrets made of `[A-Za-z0-9_.=-]`, and a strong password with `!` or `#` slips by.

The `senha-com-simbolo` rule in `.gitleaks.toml` covers that case. Measured over the full history of
ten repositories:

| Version | Findings | True | False positives |
|---|---|---|---|
| First (entropy 3.5, no exclusions) | 33 | 1 (the planted password) | 32: test passwords, a file name (`senha-keystore.txt`), a SecureStore key name, an anonymised-account marker, a regular expression, public demo passwords |
| Current (entropy 4.0; excludes tests, file names, bcrypt hashes and regular expressions) | 1 | 1 | 0 |

The accepted limit: passwords shorter than 12 characters are not flagged, because below that the rule
mistook ordinary words for secrets. The `Admin@2025` password that once existed in ConectAgente's
history (fixed, see its `seguranca.md`) has 10 and would not be caught by this rule.

## Running

```bash
pipx run --spec semgrep==1.181.0 semgrep --test --config .semgrep/regras .semgrep/testes
pipx run --spec semgrep==1.181.0 semgrep scan --config .semgrep/regras --exclude .semgrep .
```

In the pipeline, the "Análise estática (Semgrep, regras próprias)" job runs both, together with the
public packs, and fails the PR on any finding.

## Adding a rule

1. Write the test first: in `.semgrep/testes`, the real case that motivated the rule with
   `// ruleid:` and the similar case that is not a flaw with `// ok:`.
2. Write the rule in `.semgrep/regras` until the test passes.
3. Run it against the projects and check every finding by hand. A false positive becomes an `ok`
   case in the test before the rule changes.
4. Update the measurement table.
