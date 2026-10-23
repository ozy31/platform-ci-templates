/**
 * containerPipeline - declarative pipeline for a containerised Go service.
 *
 * Stages: Test -> Build image -> Scan -> Push (selected branches) -> GitOps promote.
 * Runs in an ephemeral Kubernetes agent pod (see resources/.../build-pod.yaml):
 *   golang    - tests
 *   buildkit  - rootless BuildKit, no Docker daemon and no privileged pod
 *   trivy     - image scanning
 *   awscli    - ECR auth via IRSA / EKS Pod Identity (no static AWS keys)
 *
 * Usage (Jenkinsfile):
 *   @Library('platform-ci-templates@v1.0.0') _
 *   containerPipeline(
 *     imageName:   'team/payments-api',
 *     ecrRegistry: '123456789012.dkr.ecr.eu-central-1.amazonaws.com',
 *   )
 */
def call(Map userConfig = [:]) {
    Map config = [
        imageName       : null,
        ecrRegistry     : null,
        awsRegion       : 'eu-central-1',
        goDir           : '.',
        dockerContext   : '.',
        pushBranches    : ['main'],
        scanSeverity    : 'CRITICAL,HIGH',
        gitopsRepo      : '',          // e.g. 'https://github.com/org/gitops.git'
        gitopsValues    : '',          // e.g. 'apps/payments-api/staging/values.yaml'
        gitopsCredential: 'gitops-token',
        timeoutMinutes  : 30,
    ] + userConfig

    ['imageName', 'ecrRegistry'].each { key ->
        if (!config[key]) {
            error("containerPipeline: '${key}' is required")
        }
    }

    pipeline {
        agent {
            kubernetes {
                yaml libraryResource('io/github/ozy31/platform/build-pod.yaml')
                defaultContainer 'golang'
            }
        }

        options {
            timeout(time: config.timeoutMinutes as int, unit: 'MINUTES')
            disableConcurrentBuilds(abortPrevious: true)
            buildDiscarder(logRotator(numToKeepStr: '30'))
            timestamps()
        }

        environment {
            IMAGE      = "${config.ecrRegistry}/${config.imageName}"
            AWS_REGION = "${config.awsRegion}"
        }

        stages {
            stage('Prepare') {
                steps {
                    script {
                        env.IMAGE_TAG = "sha-${sh(returnStdout: true, script: 'git rev-parse --short=7 HEAD').trim()}"
                        env.SHOULD_PUSH = (env.BRANCH_NAME in config.pushBranches).toString()
                    }
                }
            }

            stage('Test') {
                steps {
                    dir(config.goDir) {
                        sh '''
                            test -z "$(gofmt -l .)" || { gofmt -l .; exit 1; }
                            go vet ./...
                            go test -race -coverprofile=coverage.out ./...
                            go tool cover -func=coverage.out | tail -1
                        '''
                    }
                }
            }

            stage('Build image') {
                steps {
                    container('buildkit') {
                        withEnv(["CTX=${config.dockerContext}"]) {
                            sh '''
                                buildctl-daemonless.sh build \
                                  --frontend dockerfile.v0 \
                                  --local context="$CTX" \
                                  --local dockerfile="$CTX" \
                                  --opt build-arg:VERSION="$IMAGE_TAG" \
                                  --export-cache type=local,dest=/tmp/buildkit-cache,mode=max \
                                  --output type=docker,name="$IMAGE:$IMAGE_TAG",dest=image.tar
                            '''
                        }
                    }
                }
            }

            stage('Scan') {
                steps {
                    container('trivy') {
                        sh "trivy image --input image.tar --severity ${config.scanSeverity} --ignore-unfixed --exit-code 1"
                    }
                }
            }

            stage('Push') {
                when { environment name: 'SHOULD_PUSH', value: 'true' }
                steps {
                    container('awscli') {
                        // Credentials come from the pod's service account (IRSA / Pod Identity).
                        sh '''
                            mkdir -p "$WORKSPACE/.docker"
                            pass="$(aws ecr get-login-password --region "$AWS_REGION")"
                            auth="$(printf 'AWS:%s' "$pass" | base64 | tr -d '\\n')"
                            registry="${IMAGE%%/*}"
                            printf '{"auths":{"%s":{"auth":"%s"}}}' "$registry" "$auth" > "$WORKSPACE/.docker/config.json"
                        '''
                    }
                    container('buildkit') {
                        withEnv(["CTX=${config.dockerContext}", "DOCKER_CONFIG=${env.WORKSPACE}/.docker"]) {
                            // Same inputs + local cache: this rebuild is a cache hit, then pushes.
                            sh '''
                                buildctl-daemonless.sh build \
                                  --frontend dockerfile.v0 \
                                  --local context="$CTX" \
                                  --local dockerfile="$CTX" \
                                  --opt build-arg:VERSION="$IMAGE_TAG" \
                                  --import-cache type=local,src=/tmp/buildkit-cache \
                                  --output type=image,name="$IMAGE:$IMAGE_TAG",push=true
                            '''
                        }
                    }
                }
                post {
                    always {
                        sh 'rm -rf "$WORKSPACE/.docker"'
                    }
                }
            }

            stage('GitOps promote') {
                when {
                    allOf {
                        environment name: 'SHOULD_PUSH', value: 'true'
                        expression { config.gitopsRepo && config.gitopsValues }
                    }
                }
                steps {
                    withCredentials([usernamePassword(credentialsId: config.gitopsCredential,
                                                      usernameVariable: 'GIT_USER',
                                                      passwordVariable: 'GIT_TOKEN')]) {
                        withEnv(["GITOPS_REPO=${config.gitopsRepo}", "VALUES_FILE=${config.gitopsValues}"]) {
                            // Pushes a branch; the PR/MR is opened by the Git host's
                            // automation or reviewed manually. Argo CD syncs after merge.
                            sh '''
                                rm -rf gitops
                                git -c credential.helper='!f() { echo "username=$GIT_USER"; echo "password=$GIT_TOKEN"; }; f' \
                                  clone --depth 1 "$GITOPS_REPO" gitops
                                cd gitops
                                # yq via `go run`: version-pinned and checksum-verified by sum.golang.org.
                                go run github.com/mikefarah/yq/v4@v4.54.1 -i '.image.tag = strenv(IMAGE_TAG)' "$VALUES_FILE"
                                git checkout -b "promote/$JOB_BASE_NAME/$IMAGE_TAG"
                                git -c user.name="Jenkins" -c user.email="jenkins@noreply.invalid" \
                                  commit -am "chore: promote $IMAGE_TAG"
                                git -c credential.helper='!f() { echo "username=$GIT_USER"; echo "password=$GIT_TOKEN"; }; f' \
                                  push origin HEAD
                            '''
                        }
                    }
                }
            }
        }

        post {
            always {
                archiveArtifacts artifacts: "${config.goDir}/coverage.out", allowEmptyArchive: true
            }
        }
    }
}
