// =====================================================================
// Renti Agent Pro — Jenkinsfile (Declarative, 本地执行版)
//
// 架构（Jenkins 与部署目录在同一台宿主机，Jenkins 跑在容器里）：
//   1. 拉代码（在 Jenkins workspace）
//   2. docker build 三个镜像 —— 操作的是宿主 docker daemon
//      （Jenkins 容器已挂 /var/run/docker.sock 并 group_add 宿主 docker 组）
//   3. 推送到 ghcr.io
//   4. 本地部署：把 compose 与 nginx 配置复制到部署目录、写 IMAGE_TAG、
//      在部署目录 docker compose pull && up -d
//      （部署目录 /home/ubuntu/workspace/renti 已挂进 Jenkins 容器）
//   5. 健康冒烟
//
// 前提（已就绪）：
//   - Jenkins 容器挂 docker.sock + group_add 988（宿主 docker 组）
//   - 部署目录 /home/ubuntu/workspace/renti 挂进 Jenkins 容器同路径
//   - Jenkins 凭据：ghcr-pat (Secret text, GitHub PAT: write:packages/read:packages)
//   - 服务器 .env 已手填（POSTGRES_PASSWORD / 各 API key 等）
// 不再需要 ssh / deploy-ssh-key / DEPLOY_HOST / SSH_PORT。
// =====================================================================

pipeline {
    agent any

    environment {
        // GitHub 用户名小写 = ghcr 镜像 namespace（ghcr 镜像名必须小写）
        GHCR_NS    = 'qiaosefennv'
        IMAGE_TAG  = "${env.GIT_COMMIT?.take(12) ?: 'latest'}"
        // 部署目录（挂进 Jenkins 容器的同路径）
        DEPLOY_DIR = '/home/ubuntu/workspace/renti'
        // ghcr 凭据：credentials() 对 "Username with password" 类型返回 (user, psd)
        GHCR_PAT   = credentials('ghcr-pat')
        GHCR_USER  = "${GHCR_PAT_USR}"
        GHCR_TOKEN = "${GHCR_PAT_PSW}"
    }

    options {
        timestamps()
        // 构建可能在 4C4G 上较慢，给足超时
        timeout(time: 60, unit: 'MINUTES')
        buildDiscarder(logRotator(numToKeepStr: '20'))
        disableConcurrentBuilds()
    }

    triggers {
        // 兜底：每 5 分钟轮询 GitHub（webhook 未命中时仍能检出）
        pollSCM('H/5 * * * *')
    }

    stages {

        stage('Build Images') {
            steps {
                sh '''
                set -e
                echo "===> 构建 backend 镜像"
                docker build \
                  -t ghcr.io/${GHCR_NS}/renti-agent-backend:${IMAGE_TAG} \
                  -f renti-agent-backend/Dockerfile \
                  renti-agent-backend

                echo "===> 构建 agent-service 镜像"
                docker build \
                  -t ghcr.io/${GHCR_NS}/renti-agent-service:${IMAGE_TAG} \
                  -f renti-agent-backend/agent-service/Dockerfile \
                  renti-agent-backend/agent-service

                echo "===> 构建 front 镜像"
                docker build \
                  -t ghcr.io/${GHCR_NS}/renti-agent-front:${IMAGE_TAG} \
                  -f renti-agent-front/Dockerfile \
                  renti-agent-front
                '''
            }
        }

        stage('Login & Push to ghcr.io') {
            steps {
                sh '''
                set -e
                echo "${GHCR_TOKEN}" | docker login ghcr.io -u "${GHCR_USER}" --password-stdin
                docker push ghcr.io/${GHCR_NS}/renti-agent-backend:${IMAGE_TAG}
                docker push ghcr.io/${GHCR_NS}/renti-agent-service:${IMAGE_TAG}
                docker push ghcr.io/${GHCR_NS}/renti-agent-front:${IMAGE_TAG}
                # 同时打 latest，方便首次部署 / 回滚
                docker tag  ghcr.io/${GHCR_NS}/renti-agent-backend:${IMAGE_TAG}  ghcr.io/${GHCR_NS}/renti-agent-backend:latest
                docker tag  ghcr.io/${GHCR_NS}/renti-agent-service:${IMAGE_TAG} ghcr.io/${GHCR_NS}/renti-agent-service:latest
                docker tag  ghcr.io/${GHCR_NS}/renti-agent-front:${IMAGE_TAG}   ghcr.io/${GHCR_NS}/renti-agent-front:latest
                docker push ghcr.io/${GHCR_NS}/renti-agent-backend:latest
                docker push ghcr.io/${GHCR_NS}/renti-agent-service:latest
                docker push ghcr.io/${GHCR_NS}/renti-agent-front:latest
                docker logout ghcr.io
                '''
            }
        }

        stage('Deploy (local)') {
            steps {
                sh '''
                set -e
                echo "===> 同步 compose 与 nginx 配置到部署目录 ${DEPLOY_DIR}"
                mkdir -p ${DEPLOY_DIR}/nginx/conf.d ${DEPLOY_DIR}/nginx/html
                cp -f docker-compose.prod.yml ${DEPLOY_DIR}/docker-compose.yml
                cp -f renti-agent-front/nginx/default.conf ${DEPLOY_DIR}/nginx/conf.d/default.conf

                echo "===> 确保 IMAGE_TAG 写入 .env（不覆盖你已填的密码/密钥）"
                if [ ! -f ${DEPLOY_DIR}/.env ]; then
                  echo "⚠️  ${DEPLOY_DIR}/.env 不存在！请先按 .env.prod.example 手填。" >&2
                  exit 1
                fi
                grep -q '^IMAGE_TAG=' ${DEPLOY_DIR}/.env || echo 'IMAGE_TAG=${IMAGE_TAG}' >> ${DEPLOY_DIR}/.env
                sed -i 's/^IMAGE_TAG=.*/IMAGE_TAG=${IMAGE_TAG}/' ${DEPLOY_DIR}/.env

                echo "===> 登录 ghcr + 拉镜像 + 重启"
                cd ${DEPLOY_DIR}
                echo "${GHCR_TOKEN}" | docker login ghcr.io -u "${GHCR_USER}" --password-stdin
                docker compose pull
                docker compose up -d
                docker image prune -f
                docker logout ghcr.io
                docker compose ps
                '''
            }
        }

        stage('Smoke Test') {
            steps {
                sh '''
                sleep 12
                curl -fsS http://127.0.0.1/api/health \
                  || (docker compose -f ${DEPLOY_DIR}/docker-compose.yml logs backend --tail=80; exit 1)
                '''
            }
        }
    }

    post {
        always {
            sh 'docker logout ghcr.io 2>/dev/null || true'
            cleanWs()
        }
        success {
            echo "✅ 部署成功：tag=${IMAGE_TAG}"
        }
        failure {
            echo "❌ 部署失败：tag=${IMAGE_TAG}，查看上方日志"
        }
    }
}
