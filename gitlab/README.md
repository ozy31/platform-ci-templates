# GitLab CI templates

Hidden jobs (names starting with `.`) that service pipelines pull in with
`include:` and specialise with `extends:`. Nothing runs until a project
extends a job, so including the files is side-effect free.

| File | Hidden jobs | Purpose |
|---|---|---|
| `templates/rules.gitlab-ci.yml` | `.rules:mr-and-default-branch`, `.rules:default-branch`, `.rules:default-branch-manual` | Shared `rules:` |
| `templates/go.gitlab-ci.yml` | `.go:test` | gofmt, vet, race tests, coverage regex |
| `templates/security.gitlab-ci.yml` | `.security:gitleaks`, `.security:trivy-config`, `.security:trivy-image` | Secrets, IaC, image scan (feeds the MR security widget) |
| `templates/docker.gitlab-ci.yml` | `.docker:build`, `.docker:push`, `.aws:oidc` | Build once, scan the tarball, push to ECR with OIDC, cosign keyless sign |
| `templates/gitops.gitlab-ci.yml` | `.gitops:promote` | Bump image tag in the GitOps repo, open an MR via push options |

See [`examples/service.gitlab-ci.yml`](examples/service.gitlab-ci.yml) for a
complete pipeline: `test → build → scan → publish → promote:staging →
promote:production (manual)`.

## AWS via OIDC

`id_tokens` issues a job-scoped JWT (`aud: sts.amazonaws.com`); the AWS CLI
exchanges it through `AWS_WEB_IDENTITY_TOKEN_FILE` + `AWS_ROLE_ARN`. In the
IAM trust policy, pin the `sub` claim, e.g.
`project_path:group/payments-api:ref_type:branch:ref:main`, so only the
default branch of that project can push images.

## Signing

`.docker:push` requests a second token with `aud: sigstore`
(`SIGSTORE_ID_TOKEN`), which cosign uses for keyless signing. Verify with:

```sh
cosign verify "$IMAGE@$DIGEST" \
  --certificate-identity "https://gitlab.com/group/payments-api//.gitlab-ci.yml@refs/heads/main" \
  --certificate-oidc-issuer https://gitlab.com
```
