# Jenkins shared library

A [shared library](https://www.jenkins.io/doc/book/pipeline/shared-libraries/)
layout: `vars/` holds global steps, `resources/` holds files loaded with
`libraryResource`. Configure the library in Jenkins with this repository and
library path `jenkins/`.

| Step | Description |
|---|---|
| [`containerPipeline`](vars/containerPipeline.groovy) | Full declarative pipeline: Go tests → rootless BuildKit build → Trivy scan → push to ECR → GitOps promotion branch |

Usage: see [`Jenkinsfile.example`](Jenkinsfile.example).

Design notes:

- **Ephemeral Kubernetes agents** ([`build-pod.yaml`](resources/io/github/ozy31/platform/build-pod.yaml)):
  one pod per build, discarded afterwards. All containers run as non-root
  with read-only root filesystems.
- **No Docker daemon**: images are built with rootless BuildKit, so the agent
  never mounts `/var/run/docker.sock` or runs privileged.
- **Scan before push**: the image is written to a tarball, scanned with Trivy,
  and only pushed when the scan passes (the push rebuild is a cache hit).
- **No static AWS keys**: the pod's service account is mapped to an IAM role
  via IRSA or EKS Pod Identity.
- **GitOps**: the pipeline pushes a promotion branch to the GitOps repository
  instead of calling `kubectl`; Argo CD applies the change after review.
