# Bitbucket Pipelines

[`bitbucket-pipelines.yml`](bitbucket-pipelines.yml) mirrors the GitHub
Actions templates for teams on Bitbucket Cloud:

| Concern | How |
|---|---|
| Reuse | Step templates in `definitions.steps` with YAML anchors (`&test`, `*test`, `<<: *promote`) |
| Runners | `runs-on: [self.hosted, linux, autoscaled]` targets autoscaled runners on Kubernetes |
| AWS auth | `oidc: true` + `AWS_WEB_IDENTITY_TOKEN_FILE`; no static access keys |
| Security | gitleaks, `trivy config`, `trivy image --input` on the saved image before push |
| Promotion | Deployment environments `staging` (auto) and `production` (`trigger: manual`) open a PR in the GitOps repo instead of deploying from CI |

## OIDC to AWS

1. Workspace settings → *OpenID Connect* shows the identity provider URL and
   audience.
2. In IAM, create an OIDC identity provider with that URL and audience.
3. Create a role trusted by that provider. Narrow the trust policy with
   conditions on the token's `sub` claim (it contains the repository UUID and,
   for deployment steps, the environment UUID) so only the intended
   repository/environment can assume it.
4. Store the role ARN as `AWS_OIDC_ROLE_ARN`. Steps that need AWS set
   `oidc: true`.

## Autoscaling self-hosted runners on Kubernetes

Atlassian publishes
[runners-autoscaler](https://bitbucket.org/bitbucketpipelines/runners-autoscaler),
a controller that runs in the cluster, watches runner usage through the
Bitbucket API and creates or deletes runner Jobs (each a pod with the runner
plus a Docker-in-Docker sidecar) to keep a target share of runners idle.

```
Bitbucket Cloud ──(API poll)── runners-autoscaler (Deployment)
                                     │ create/delete
                                     ▼
                        runner Job/pod per runner ── docker (dind) sidecar
```

Setup outline:

1. Create an OAuth consumer (or workspace access token) for the autoscaler
   with permission to manage runners; store it as a Kubernetes Secret.
2. Deploy the autoscaler with the upstream kustomize manifests into its own
   namespace, with [`runners-autoscaler/runners_config.yaml`](runners-autoscaler/runners_config.yaml)
   as its ConfigMap.
3. Put runner pods on a dedicated node group (taints/tolerations) that
   Cluster Autoscaler or Karpenter can scale; the runner autoscaler adds pods,
   the node autoscaler adds nodes.
4. Target the runners from pipelines with the custom label
   (`runs-on: [self.hosted, linux, autoscaled]`).

Things worth getting right:

- **Warm pool vs cost**: `min` keeps a few runners idle so builds start
  immediately; `max` caps spend and protects the cluster.
- **Cool-down**: `runner_cool_down_period` avoids churn when builds arrive in
  bursts (e.g. after a merge train).
- **Isolation**: runner pods need privileged DinD; keep them on separate
  nodes from workloads, with no cloud credentials on the node role. Builds get
  AWS access only through per-step OIDC.
- **Caching**: runner pods are ephemeral, so rely on Bitbucket caches or a
  registry-backed BuildKit cache rather than node-local state.
- **Resources**: set requests/limits for runner and DinD containers so the
  node autoscaler can bin-pack predictably.
