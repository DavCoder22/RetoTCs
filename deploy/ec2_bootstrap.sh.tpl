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
TF_PGDATA_GB="${TF_PGDATA_GB}"
TF_SMARTBANCS_ROLE="${TF_SMARTBANCS_ROLE}"
TF_DB_PRIVATE_IP="${TF_DB_PRIVATE_IP}"

REGION="${TF_REGION}"
STATE_BUCKET="${TF_STATE_BUCKET}"
ECR_REGISTRY="${TF_ECR_REGISTRY}"
IMAGE_TAG="${TF_IMAGE_TAG}"
SMARTBANCS_ROLE="${TF_SMARTBANCS_ROLE}"
DB_PRIVATE_IP="${TF_DB_PRIVATE_IP}"

echo "== bootstrap: docker + compose =="
# AL2023 ya trae curl-minimal (provee curl) y tar; NO instalar "curl" (conflicto).
dnf install -y -q docker >/dev/null

# Volumen EBS dedicado a BBDD (anti pérdida de datos): se monta en /var/lib/docker
# ANTES de arrancar docker. Sobrevive a la sustitución de la EC2 (el recurso se
# desacopla/re-acopla solo) y, si ya trae datos, se monta tal cual sin borrar nada.
echo "== bootstrap: volumen EBS /var/lib/docker =="
MOUNT=/var/lib/docker
detected=""
for i in $(seq 1 30); do
  for d in $(lsblk -ndo NAME 2>/dev/null); do
    path="/dev/$d"
    # Solo discos ENTEROS (no particiones) que coincidan EXACTAMENTE en tamaño
    # con el volumen EBS dedicado, sin particiones ni punto de montaje.
    [ "$(lsblk -ndo TYPE "$path" 2>/dev/null)" = "disk" ] || continue
    size_gb=$(( $(lsblk -ndbo SIZE "$path" 2>/dev/null || echo 0) / 1024 / 1024 / 1024 ))
    [ "$size_gb" = "${TF_PGDATA_GB}" ] || continue
    [ -z "$(lsblk -ndo MOUNTPOINT "$path" 2>/dev/null)" ] || continue
    n_children=$(lsblk -no NAME "$path" 2>/dev/null | wc -l)
    [ "$n_children" -le 1 ] || continue
    detected="$path"; break 2
  done
  [ -n "$detected" ] && break
  sleep 2
done
if [ -n "$detected" ]; then
  fs="$(blkid -o value -s TYPE "$detected" 2>/dev/null || true)"
  if [ -z "$fs" ]; then
    echo "inicializando (xfs) $detected para $MOUNT"
    mkfs.xfs -f "$detected" >/dev/null || echo "AVISO: no se pudo formatear $detected"
  fi
  mkdir -p "$MOUNT"
  uuid="$(blkid -o value -s UUID "$detected")"
  grep -q "$uuid" /etc/fstab 2>/dev/null || echo "UUID=$uuid $MOUNT xfs defaults,nofail 0 2" >> /etc/fstab
  mount "$MOUNT" 2>/dev/null && echo "EBS montado en $MOUNT ($detected): datos BBDD persistidos" || echo "AVISO: mount de $detected falló"
else
  echo "ADVERTENCIA: volumen EBS no detectado; sin persistencia dedicada"
fi

systemctl enable --now docker >/dev/null 2>&1 || true
# docker compose v2 como plugin del CLI (AL2023 no lo trae por defecto)
if [ ! -x /usr/libexec/docker/cli-plugins/docker-compose ] && [ ! -x /usr/local/lib/docker/cli-plugins/docker-compose ]; then
  mkdir -p /usr/local/lib/docker/cli-plugins
  curl -fsSL "https://github.com/docker/compose/releases/download/v2.29.7/docker-compose-linux-x86_64" \
    -o /usr/local/lib/docker/cli-plugins/docker-compose
  chmod +x /usr/local/lib/docker/cli-plugins/docker-compose
fi

echo "== bootstrap: swap (Free Tier t3.micro = 1 GB) =="
if [ "$(free -m | awk 'NR==2{print $2}')" -lt 2048 ] && [ ! -e /swapfile ]; then
  fallocate -l 2G /swapfile 2>/dev/null || dd if=/dev/zero of=/swapfile bs=1M count=2048
  chmod 600 /swapfile
  mkswap /swapfile >/dev/null
  swapon /swapfile
  echo '/swapfile none swap sw 0 0' >> /etc/fstab
fi

echo "== bootstrap: bundle de despliegue =="
mkdir -p /opt/smartbancs
aws s3 cp "s3://$${STATE_BUCKET}/deploy/deploy-bundle.tar.gz" /tmp/deploy-bundle.tar.gz
tar -xzf /tmp/deploy-bundle.tar.gz -C /opt/smartbancs
cd /opt/smartbancs

echo "== bootstrap: login ECR y secret de OpenRouter =="
aws ecr get-login-password --region "$${REGION}" \
  | docker login --username AWS --password-stdin "$${ECR_REGISTRY}" >/dev/null

api_key=""
if aws ssm get-parameter --name "/smartbancs/openrouter-api-key" \
  --with-decryption --region "$${REGION}" --query Parameter.Value --output text > /tmp/api_key.txt 2>/dev/null; then
  api_key="$(cat /tmp/api_key.txt)"
fi

echo "== bootstrap: .env y compose up (rol: $SMARTBANCS_ROLE) =="
export ECR_REGISTRY IMAGE_TAG
if [ "$SMARTBANCS_ROLE" = "app" ]; then
  # Réplica app: solo capas sin estado, BBDD apuntando al primario, worker OFF
  # (el outbox SOLO lo consume el primario -> nada de recomendaciones duplicadas).
  printf 'OPENROUTER_API_KEY=%s\nAI_MODEL=moonshotai/kimi-k2.6\n' "$${api_key}" > /opt/smartbancs/.env
  printf 'SPRING_DATASOURCE_URL=jdbc:postgresql://%s:5432/smartbancs\n' "$${DB_PRIVATE_IP}" >> /opt/smartbancs/.env
  printf 'SMARTBANCS_AI_WORKER_ENABLED=false\n' >> /opt/smartbancs/.env
  docker compose -f docker-compose.yml -f docker-compose.prod.yml \
    up -d --wait --no-deps api ai-service \
    || docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d --no-deps api ai-service
else
  printf 'OPENROUTER_API_KEY=%s\nAI_MODEL=moonshotai/kimi-k2.6\n' "$${api_key}" > /opt/smartbancs/.env
  docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d --wait \
    || docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d
fi

echo "== bootstrap: done =="