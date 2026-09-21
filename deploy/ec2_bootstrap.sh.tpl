#!/bin/bash
# Bootstrap de la EC2 (templado por Terraform): instala Docker, descarga el
# bundle de despliegue (compose + observability) desde S3, inyecta
# OPENROUTER_API_KEY desde SSM en .env y levanta el stack con imágenes de ECR.
set -euo pipefail
exec > >(tee /var/log/smartbancs-bootstrap.log) 2>&1

TF_REGION="${TF_REGION}"
TF_STATE_BUCKET="${TF_STATE_BUCKET}"
TF_ECR_REGISTRY="${TF_ECR_REGISTRY}"
TF_IMAGE_TAG="${TF_IMAGE_TAG}"

REGION="${TF_REGION}"
STATE_BUCKET="${TF_STATE_BUCKET}"
ECR_REGISTRY="${TF_ECR_REGISTRY}"
IMAGE_TAG="${TF_IMAGE_TAG}"

echo "== bootstrap: docker + compose =="
dnf install -y -q docker curl unzip >/dev/null
systemctl enable --now docker >/dev/null 2>&1 || true
# docker compose v2 como plugin del CLI (AL2023 no lo trae por defecto)
if [ ! -x /usr/libexec/docker/cli-plugins/docker-compose ] && [ ! -x /usr/local/lib/docker/cli-plugins/docker-compose ]; then
  mkdir -p /usr/local/lib/docker/cli-plugins
  curl -fsSL "https://github.com/docker/compose/releases/download/v2.29.7/docker-compose-linux-x86_64" \
    -o /usr/local/lib/docker/cli-plugins/docker-compose
  chmod +x /usr/local/lib/docker/cli-plugins/docker-compose
fi

echo "== bootstrap: bundle de despliegue =="
mkdir -p /opt/smartbancs
aws s3 cp "s3://$${STATE_BUCKET}/deploy/deploy-bundle.tar.gz" /tmp/deploy-bundle.tar.gz
tar -xzf /tmp/deploy-bundle.tar.gz -C /opt/smartbancs
cd /opt/smartbancs

echo "== bootstrap: login ECR y secret de OpenRouter =="
aws ecr get-login-password --region "$${REGION}" \
  | docker login --username AWS --password-stdin "$${ECR_REGISTRY}" >/dev/null

api_key="$(aws ssm get-parameter --name "/smartbancs/openrouter-api-key" \
  --with-decryption --region "$${REGION}" --query Parameter.Value --output text)"
printf 'OPENROUTER_API_KEY=%s\nAI_MODEL=moonshotai/kimi-k2.6\n' "$${api_key}" > /opt/smartbancs/.env

echo "== bootstrap: docker compose up =="
export ECR_REGISTRY IMAGE_TAG
docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d --wait || docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d

echo "== bootstrap: done =="