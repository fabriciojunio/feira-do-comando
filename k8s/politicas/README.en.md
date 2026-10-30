# Admission policies (Kyverno)

*[Leia em português](README.md)*

Four CEL policies (`ValidatingPolicy`, Kyverno 1.19's new API and the same language as Kubernetes'
native `ValidatingAdmissionPolicy`). They apply to the `feira` namespace and, through autogen, to
Deployments and StatefulSets too, so the error shows up on `kubectl apply` instead of when the pod
tries to start.

| Policy | Blocks | Scope |
|---|---|---|
| `sem-privilegio` | host network, PID or IPC; privileged containers; privilege escalation; pods without the `RuntimeDefault` seccomp profile | every pod |
| `limites-de-recurso` | containers without CPU and memory requests or without a memory limit | every pod |
| `imagem-confiavel` | images outside `ghcr.io/fabriciojunio`, `postgres`, `mongo` and `redpanda`; the `:latest` tag; untagged images | every pod |
| `servico-restrito` | in-house services running as root, with a writable root filesystem, with capabilities or with an API token | the four services |

Databases are kept out of the restricted profile because the official Postgres image starts as
root to set up its data directory.

## How it is tested

- `testes/`: one correct pod, one pinned by digest and one for each broken rule, with the expected
  result declared. `kyverno test k8s/politicas/testes` runs in CI (15 cases). Turning off the
  `:latest` rule makes one case fail, which proves the test actually checks the rule.
- In the CI kind cluster Kyverno is really installed, the policies go in before the application,
  and a `kubectl apply --dry-run=server` with the same pods proves the API server rejects the bad
  ones and admits the good ones.

## Why `overlays/ci` exists

The base uses `:latest` as a reading example. The `overlays/ci` overlay swaps it for a fixed tag, which
is what a real deploy does with the commit tag, and that is what the cluster receives.
