# Detection as code

*[Leia em português](README.md)*

| Layer | Where | Watches | How it is tested |
|---|---|---|---|
| Application | `sigma/` (4 rules) | unreadable Kafka message, unreadable request, unexpected error, message stuck in the outbox | `test_regras_sigma.py`: each rule becomes SQL through pySigma's SQLite backend and runs against `amostras.jsonl`; each line declares which rules must catch it |
| Container | `falco/regras-feira.yaml` (4 rules) | shell in a service, system credential read, database connecting out, write to a binaries directory | Falco 0.45.1 itself loads the rules with its default set and rejects unknown fields or macros |

Each Sigma rule has a runbook in `runbooks.md`, and the test fails for a rule without a runbook or
without a sample. Sigma rules carry the MITRE ATT&CK technique as a tag; Falco rules use `mitre_*`.

The samples are simulated data (Dados simulados), written from the log messages the services emit.
Removing the "Evento ilegivel em" variant from the Kafka rule makes the test fail, which proves it
checks the rule and not just the syntax.
