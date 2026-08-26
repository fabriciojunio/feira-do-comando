# Kubernetes manifests

Plain manifests, no Helm. The topology is small enough that a chart would add
indirection without removing repetition, and the point of this directory is to
be read, not to be parameterised.

```bash
kubectl apply -k k8s/
kubectl -n feira rollout status deploy/servico-pedidos
```

## What is here

| File | What it holds |
|---|---|
| `namespace.yaml` | the `feira` namespace |
| `broker.yaml` | Redpanda as a StatefulSet with its own volume |
| `bancos.yaml` | three PostgreSQL StatefulSets, one per service |
| `servicos.yaml` | the three application Deployments and their Services |
| `configmap.yaml` | non-secret configuration |
| `secret.yaml` | database credentials, as a template |
| `kustomization.yaml` | ties it together |

## Why three databases

The same reason the compose file has three: one database per service. Sharing a
database would let one service write to another's table inside a single
transaction, and the outbox would stop meaning anything.

## What these manifests are not

They have never been applied to a production cluster. They are validated against
the Kubernetes API schemas on every build, with `kubeconform` in the pipeline,
which catches a misspelled field or a wrong apiVersion but says nothing about
whether the resource requests are right for a real workload.

`secret.yaml` carries placeholder credentials on purpose and is meant to be
replaced by whatever the cluster actually uses — sealed secrets, an external
secrets operator, or the cloud provider's own store. Committing real values
here would put them in the git history forever.
