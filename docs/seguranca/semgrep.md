# Regras Semgrep próprias

*[Read this in English](semgrep.en.md)*

Os conjuntos públicos (`p/java`, `p/owasp-top-ten`, `p/security-audit`) pegam o que vale para qualquer
projeto Java. As regras de `.semgrep/regras` pegam o que é específico de um sistema Spring com Kafka
como este, e que os conjuntos públicos não cobrem. Cada uma tem arquivo de teste em
`.semgrep/testes`, com os casos que ela tem que pegar (`ruleid`) e os que não pode pegar (`ok`).

## As regras

| Regra | O que pega | CWE |
|---|---|---|
| `excecao-de-framework-na-resposta` | mensagem de exceção genérica ou de framework (Jackson, SQL, aritmética) indo para a resposta | CWE-209 |
| `kafka-pacotes-confiaveis-curinga` | `JsonDeserializer` do Spring Kafka aceitando qualquer pacote, em Java | CWE-502 |
| `kafka-pacotes-confiaveis-config` | o mesmo, em `application.yml` | CWE-502 |
| `consulta-montada-por-concatenacao` | JPQL ou SQL montado com `+` ou `String.format` | CWE-89 |
| `random-para-segredo` | `Random` ou `ThreadLocalRandom` em método que gera token, senha, código ou chave | CWE-330 |
| `cors-qualquer-origem` | CORS com `*` ou `@CrossOrigin` sem origem | CWE-942 |

## Medição

Rodadas em 29/10/2026 contra o código de quatro projetos Java, 312 arquivos ao todo, excluindo
testes. Cada achado foi conferido à mão.

**Primeira versão das regras:**

| Projeto | Achados | Verdadeiros | Falsos positivos |
|---|---|---|---|
| Feira do Comando | 3 | 1 (`TratadorDeErros`, mensagem do Jackson na resposta) | 2 (`AdquirenteSimulado`, sorteio da simulação) |
| Vitrine Bauru | 1 | 0 | 1 (mensagem liberada só depois de `instanceof`) |
| alicerce | 0 | 0 | 0 |
| brecha | 1 | 1 (falha plantada de propósito, BRECHA-008) | 0 |
| **Total** | **5** | **2** | **3** |

Precisão de 40%: regra assim é desligada pelo time na segunda semana. Duas mudanças:

- `random-para-segredo` passou a exigir método com nome de segredo. Sorteio de simulação não é
  segredo, e a regra genérica não tinha como saber.
- `excecao-de-framework-na-resposta` passou a ignorar mensagem protegida por `instanceof` e mensagem
  que vai para o log. Na primeira tentativa de ignorar o log, o filtro por nível derrubou também o
  verdadeiro positivo do brecha; a medição pegou, e a regra usa um padrão por nível.

**Versão atual**, depois de corrigir o `TratadorDeErros` da Feira:

| Projeto | Achados | Verdadeiros | Falsos positivos |
|---|---|---|---|
| Feira do Comando | 0 | 0 | 0 |
| Vitrine Bauru | 0 | 0 | 0 |
| alicerce | 0 | 0 | 0 |
| brecha | 1 | 1 | 0 |

Precisão de 100% neste conjunto, sem perder o verdadeiro positivo.

**O limite da medição:** ela diz quanto a regra erra quando acusa, não quanto ela deixa passar. Para
medir o que escapa seria preciso um conjunto de código com as falhas marcadas de antemão. Os
arquivos de `.semgrep/testes` são o começo desse conjunto: cada caso `ruleid` é uma falha conhecida
que a regra tem que continuar pegando.

## Regra própria do gitleaks

A branch `prova-dos-portoes` plantou quatro falhas e abriu um PR para produção. Semgrep, CodeQL e
OSV-Scanner reprovaram as deles. O gitleaks **aprovou** a senha escrita no código: a regra genérica
dele só reconhece segredo feito de `[A-Za-z0-9_.=-]`, e uma senha forte, com `!` ou `#`, escapa.

A regra `senha-com-simbolo`, em `.gitleaks.toml`, cobre esse caso. Medida no histórico inteiro de
dez repositórios:

| Versão | Achados | Verdadeiros | Falsos positivos |
|---|---|---|---|
| Primeira (entropia 3,5, sem exclusões) | 33 | 1 (a senha plantada) | 32: senha de teste, nome de arquivo (`senha-keystore.txt`), nome de chave do SecureStore, marcador de conta anonimizada, expressão regular, senha pública da demonstração |
| Atual (entropia 4,0; exclui teste, nome de arquivo, hash bcrypt e expressão regular) | 1 | 1 | 0 |

O limite assumido: senha com menos de 12 caracteres não é acusada, porque abaixo disso a regra
confundia palavra comum com segredo. A senha `Admin@2025` que existiu no histórico do ConectAgente
(corrigida, ver o `seguranca.md` de lá) tem 10 e não seria pega por esta regra.

## Como rodar

```bash
pipx run --spec semgrep==1.181.0 semgrep --test --config .semgrep/regras .semgrep/testes
pipx run --spec semgrep==1.181.0 semgrep scan --config .semgrep/regras --exclude .semgrep .
```

Na esteira, o job "Análise estática (Semgrep, regras próprias)" roda os dois, junto com os conjuntos
públicos, e reprova o PR com qualquer achado.

## Como acrescentar uma regra

1. Escreva o teste antes: em `.semgrep/testes`, o caso real que motivou a regra com `// ruleid:` e
   o caso parecido que não é falha com `// ok:`.
2. Escreva a regra em `.semgrep/regras` até o teste passar.
3. Rode contra os projetos e confira cada achado à mão. Falso positivo vira caso `ok` no teste antes
   de mexer na regra.
4. Atualize a tabela de medição.
