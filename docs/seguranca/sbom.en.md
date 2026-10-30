# SBOM and Grype

*[Leia em português](sbom.md)*

The "SBOM (SPDX e CycloneDX) e Grype" job in the security pipeline packages the services, builds
the component inventory with syft and runs it through Grype. Both formats are kept for 90 days as
run artifacts: SPDX is what public-sector and regulated buyers ask for, CycloneDX is what security
tooling reads best.

## Why Grype on top of OSV-Scanner

They use different vulnerability databases. OSV-Scanner reads the Maven SBOM and the front-end
lockfile. Grype reads the packaged jars and matches them against Anchore's database, which adds
distribution and GitHub advisories. Each one catches what the other misses.

## One source of accepted risks

An accepted vulnerability lives in one place: `osv-scanner.toml`, with a reason and a deadline
(`ignoreUntil`). `scripts/aceites_para_grype.py` turns those entries into Grype's configuration at
run time. An expired acceptance is left out, and both scanners fail together again. The converter
has its own test, which runs before Grype.

## Measurement on 2026-10-30

| Measure | Result |
|---|---|
| Components in the SBOM | 947 in CycloneDX, 929 in SPDX |
| Grype with the accepted list | 0 findings, exit 0; both accepted Spring MVC advisories ignored |
| Grype without it (counter-test) | the same two advisories, rated critical, exit 2 |

The syft 1.55.0 and grype 0.121.0 binaries are downloaded with their SHA-256 checked against the
checksum file published with each release.
