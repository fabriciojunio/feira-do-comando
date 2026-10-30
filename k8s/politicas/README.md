# Políticas de admissão (Kyverno)

*[Read this in English](README.en.md)*

Quatro políticas em CEL (`ValidatingPolicy`, a API nova do Kyverno 1.19, a mesma linguagem do
`ValidatingAdmissionPolicy` nativo do Kubernetes). Valem para o namespace `feira` e, pela geração
automática, também para Deployment e StatefulSet: o erro aparece no `kubectl apply`, e não só
quando o pod tenta subir.

| Política | O que barra | Onde se aplica |
|---|---|---|
| `sem-privilegio` | rede, processos ou IPC do host; contêiner privilegiado; escalada de privilégio; pod sem seccomp `RuntimeDefault` | todo pod |
| `limites-de-recurso` | contêiner sem request de CPU e memória ou sem limite de memória | todo pod |
| `imagem-confiavel` | imagem fora de `ghcr.io/fabriciojunio`, `postgres`, `mongo` e `redpanda`; tag `:latest`; imagem sem tag | todo pod |
| `servico-restrito` | serviço próprio como root, com raiz gravável, com capability ou com token da API | os quatro serviços |

Os bancos ficam fora do perfil restrito porque a imagem oficial do Postgres começa como root para
preparar o diretório de dados.

## Como é testado

- `testes/`: um pod correto, um fixado por digest e um para cada regra quebrada, com o resultado
  esperado declarado. `kyverno test k8s/politicas/testes` roda na CI (15 casos). Desligar a regra
  do `:latest` faz um caso reprovar, que é a prova de que o teste olha a regra.
- No cluster kind da CI o Kyverno é instalado de verdade, as políticas entram antes da aplicação e
  um `kubectl apply --dry-run=server` com os mesmos pods prova que o servidor de API recusa os
  ruins e aceita os corretos.

## Por que existe `k8s/ci`

A base usa `:latest` como exemplo de leitura. O overlay `k8s/ci` troca pela tag fixa, que é o que
um deploy de verdade faz com a tag do commit, e é ele que o cluster recebe.
