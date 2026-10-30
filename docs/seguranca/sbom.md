# SBOM e Grype

*[Read this in English](sbom.en.md)*

O job "SBOM (SPDX e CycloneDX) e Grype" da esteira de segurança empacota os serviços, gera o
inventário de componentes com o syft e passa esse inventário pelo Grype. Os dois formatos ficam
guardados por 90 dias como artefato da execução: SPDX é o que pede quem compra software para
governo e cliente regulado, CycloneDX é o que as ferramentas de segurança leem melhor.

## Por que o Grype além do OSV-Scanner

São duas bases de vulnerabilidade diferentes. O OSV-Scanner lê o SBOM do Maven e o lockfile do
front. O Grype lê os jars já empacotados e cruza com a base da Anchore, que inclui avisos de
distribuição e do GitHub. Um pega o que o outro deixa passar.

## Uma fonte de aceites só

Vulnerabilidade aceita fica em um lugar: o `osv-scanner.toml`, com motivo e prazo
(`ignoreUntil`). O `scripts/aceites_para_grype.py` converte esses aceites para a configuração do
Grype na hora da execução. Aceite vencido não entra, e os dois scanners voltam a reprovar juntos.
O conversor tem teste próprio, que roda antes do Grype.

## Medição de 30/10/2026

| Medida | Resultado |
|---|---|
| Componentes no SBOM | 947 no CycloneDX, 929 no SPDX |
| Grype com os aceites | 0 achados, saída 0; os dois aceites do Spring MVC ignorados |
| Grype sem os aceites (contraprova) | os mesmos dois avisos, classificados como críticos, saída 2 |

Os binários do syft 1.55.0 e do grype 0.121.0 são baixados com o SHA-256 conferido contra o
arquivo de checksums publicado na release.
