#!/usr/bin/env bash
set -euo pipefail

if ! command -v aws >/dev/null 2>&1; then
  echo "ERROR: AWS CLI no instalado."
  echo "Guia rapida: https://docs.aws.amazon.com/cli/latest/userguide/getting-started-install.html"
  exit 1
fi

REGION="${AWS_DEFAULT_REGION:-us-east-1}"
PROFILE="${AWS_PROFILE:-default}"

if [ -n "${AWS_ACCESS_KEY_ID:-}" ] && [ -n "${AWS_SECRET_ACCESS_KEY:-}" ]; then
  echo "Usando AWS_ACCESS_KEY_ID / AWS_SECRET_ACCESS_KEY del entorno."
elif aws configure get aws_access_key_id --profile "$PROFILE" >/dev/null 2>&1; then
  echo "Usando perfil AWS '${PROFILE}' de ~/.aws/credentials."
else
  echo "No hay credenciales. Crea un IAM user (Acceso programatico, permiso minimo)"
  echo "y ejecuta:"
  echo "  aws configure"
  echo "  AWS Access Key ID: ____"
  echo "  AWS Secret Access Key: ____"
  echo "  Default region name [$REGION]:"
  echo "  Default output format: json"
  exit 1
fi

chmod 600 ~/.aws/credentials 2>/dev/null || true

echo "Verificando identidad..."
aws sts get-caller-identity --query 'Arn' --output text

echo "OK. Para lanzar terraform:"
echo "  cd terraform && terraform plan"