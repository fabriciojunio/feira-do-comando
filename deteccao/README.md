# Detecção como código

*[Read this in English](README.en.md)*

| Camada | Onde | O que vigia | Como é testado |
|---|---|---|---|
| Aplicação | `sigma/` (4 regras) | mensagem ilegível no Kafka, requisição ilegível, erro inesperado, mensagem travada no outbox | `test_regras_sigma.py`: cada regra vira SQL pelo backend SQLite do pySigma e roda contra `amostras.jsonl`; cada linha declara quais regras têm de pegá-la |
| Contêiner | `falco/regras-feira.yaml` (4 regras) | shell em serviço, leitura de credencial do sistema, banco conectando para fora, gravação em diretório de binários | o próprio Falco 0.45.1 carrega as regras com o conjunto padrão e recusa campo ou macro inexistente |

Cada regra Sigma tem runbook em `runbooks.md`, e o teste reprova regra sem runbook ou sem amostra.
As regras Sigma levam a técnica do MITRE ATT&CK na tag; as do Falco, na tag `mitre_*`.

As amostras são Dados simulados, escritas a partir das mensagens de log que os serviços emitem.
Tirar a variante "Evento ilegivel em" da regra do Kafka faz o teste reprovar, que é a prova de que
ele olha a regra e não só a sintaxe.
