# Despliegue SmartBancs en AWS (reto).
#
# EC2 (Amazon Linux 2023) que levanta el stack docker compose de producción:
#   api + ai-service (imágenes de ECR) + postgres/observabilidad (imágenes públicas).
# OPENROUTER_API_KEY llega desde el secret de GitHub -> TF_VAR -> SSM SecureString ->
# user-data la vuelca a .env (nunca viaja en el código ni en los logs).
#
# Uso:
#   terraform init && terraform plan && terraform apply
#   (el bucket S3 de estado y la tabla DynamoDB los crea el workflow antes de init)

data "aws_region" "current" {}
data "aws_caller_identity" "current" {}

data "aws_ami" "al2023" {
  most_recent = true
  owners      = ["amazon"]
  filter {
    name   = "name"
    values = ["al2023-ami-2023.*-x86_64"]
  }
  filter {
    name   = "virtualization-type"
    values = ["hvm"]
  }
}

data "aws_vpc" "default" {
  default = true
}

data "aws_subnets" "default" {
  filter {
    name   = "vpc-id"
    values = [data.aws_vpc.default.id]
  }
}

# Parámetro SSM SOLO si hay key (si el secret de GitHub no está configurado, se
# omite y el agente en la EC2 cae a modo mock; evita el PutParameter vacío).
resource "aws_ssm_parameter" "openrouter_api_key" {
  count = var.openrouter_api_key != "" ? 1 : 0
  name  = "/smartbancs/openrouter-api-key"
  type  = "SecureString"
  value = var.openrouter_api_key
}

resource "aws_security_group" "smartbancs" {
  name        = "smartbancs-ec2"
  description = "SmartBancs: HTTP demo (api/ia) + observabilidad + SSH"
  vpc_id      = data.aws_vpc.default.id

  ingress {
    from_port   = 22
    to_port     = 22
    protocol    = "tcp"
    cidr_blocks = [var.allowed_cidr]
  }
  ingress {
    from_port   = 8080
    to_port     = 8080
    protocol    = "tcp"
    cidr_blocks = [var.allowed_cidr]
  }
  ingress {
    from_port   = 8081
    to_port     = 8081
    protocol    = "tcp"
    cidr_blocks = [var.allowed_cidr]
  }
  ingress {
    from_port   = 3333
    to_port     = 3333
    protocol    = "tcp"
    cidr_blocks = [var.allowed_cidr]
  }
  ingress {
    from_port   = 9090
    to_port     = 9090
    protocol    = "tcp"
    cidr_blocks = [var.allowed_cidr]
  }
  ingress {
    from_port   = 3200
    to_port     = 3200
    protocol    = "tcp"
    cidr_blocks = [var.allowed_cidr]
  }
  ingress {
    from_port   = 3100
    to_port     = 3100
    protocol    = "tcp"
    cidr_blocks = [var.allowed_cidr]
  }
  ingress {
    from_port   = 9187
    to_port     = 9187
    protocol    = "tcp"
    cidr_blocks = [var.allowed_cidr]
  }
  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
}

data "aws_iam_policy_document" "ec2_assume" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["ec2.amazonaws.com"]
    }
  }
}

data "aws_iam_policy_document" "ec2_stack" {
  statement {
    actions = [
      "ecr:GetAuthorizationToken",
      "ecr:BatchGetImage",
      "ecr:GetDownloadUrlForLayer",
    ]
    resources = ["*"]
  }
  statement {
    actions   = ["s3:GetObject"]
    resources = ["arn:aws:s3:::${var.state_bucket}/deploy/*"]
  }
  # Backups automáticos de la BBDD: la EC2 puede subir volcados a deploy/backups/.
  statement {
    actions   = ["s3:PutObject"]
    resources = ["arn:aws:s3:::${var.state_bucket}/deploy/backups/*"]
  }
}

data "aws_iam_policy_document" "ec2_ssm" {
  count = var.openrouter_api_key != "" ? 1 : 0
  statement {
    actions   = ["ssm:GetParameter"]
    resources = [aws_ssm_parameter.openrouter_api_key[0].arn]
  }
}

resource "aws_iam_role" "smartbancs_ec2" {
  name               = "smartbancs-ec2"
  assume_role_policy = data.aws_iam_policy_document.ec2_assume.json
}

resource "aws_iam_role_policy_attachment" "smartbancs_ec2_ssm" {
  role       = aws_iam_role.smartbancs_ec2.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
}

resource "aws_iam_role_policy" "smartbancs_ec2_stack" {
  name   = "smartbancs-ec2-stack"
  role   = aws_iam_role.smartbancs_ec2.id
  policy = data.aws_iam_policy_document.ec2_stack.json
}

resource "aws_iam_role_policy" "smartbancs_ec2_ssm" {
  count  = length(data.aws_iam_policy_document.ec2_ssm)
  name   = "smartbancs-ec2-ssm"
  role   = aws_iam_role.smartbancs_ec2.id
  policy = data.aws_iam_policy_document.ec2_ssm[0].json
}

resource "aws_iam_instance_profile" "smartbancs_ec2" {
  name = "smartbancs-ec2"
  role = aws_iam_role.smartbancs_ec2.name
}

# ---------------------------------------------------------------------------
# Dirección elástica (IP dinámica estable): la EC2 la asocia en cada arranque,
# así el endpoint público no cambia aunque se sustituya la instancia (cero
# pérdida de conectividad/datos del lado del cliente).
# ---------------------------------------------------------------------------
resource "aws_eip" "smartbancs" {
  domain = "vpc"
  tags = {
    Name = "smartbancs-eip"
  }
}

resource "aws_eip_association" "smartbancs" {
  instance_id   = aws_instance.smartbancs.id
  allocation_id = aws_eip.smartbancs.id
}

# Volumen EBS dedicado para la BBDD (anti pérdida de datos). El bootstrap monta
# este volumen en /var/lib/docker, de modo que `postgres-data` persiste ante
# sustitución de la EC2 (el volumen sobrevive al replace y se re-monta solo).
resource "aws_ebs_volume" "postgres" {
  availability_zone = aws_instance.smartbancs.availability_zone
  size              = var.postgres_volume_size_gb
  type              = "gp3"
  tags = {
    Name = "smartbancs-pgdata"
  }
}

resource "aws_volume_attachment" "postgres" {
  device_name = "/dev/xvdg"
  volume_id   = aws_ebs_volume.postgres.id
  instance_id = aws_instance.smartbancs.id
}

resource "aws_instance" "smartbancs" {
  ami                    = var.ami_id != "" ? var.ami_id : data.aws_ami.al2023.id
  instance_type          = var.instance_type
  subnet_id              = var.subnet_id != "" ? var.subnet_id : data.aws_subnets.default.ids[0]
  vpc_security_group_ids = [aws_security_group.smartbancs.id]
  iam_instance_profile   = aws_iam_instance_profile.smartbancs_ec2.name
  key_name               = var.key_name != "" ? var.key_name : null
  user_data              = local.user_data

  root_block_device {
    volume_size = var.root_volume_size_gb
    volume_type = "gp3"
  }

  tags = {
    Name = "smartbancs-demo"
  }
}

# ---------------------------------------------------------------------------
# ESTRATEGIA DE REDUNDANCIA (opcional, var.redundancy_enabled=true):
# cubre el pico de demanda (>= 10.000 tx) replicando las capas SIN ESTADO
# (api + agente IA) detrás de un ALB, manteniendo UNA sola BBDD PostgreSQL
# (integridad ACID) con el worker de recomendaciones ACTIVO solo en el primario.
#   activar:  terraform apply -var="redundancy_enabled=true"
#   apagar:   terraform apply -var="redundancy_enabled=false"
# ---------------------------------------------------------------------------

# El ALB debe poder alcanzar el 8080 de AMBAS instancias y el primario debe
# exponer postgres (5432) solo a sí mismo (misma SG, self-reference).
resource "aws_security_group" "alb" {
  count       = var.redundancy_enabled ? 1 : 0
  name        = "smartbancs-alb"
  description = "SmartBancs: ALB over api:8080"
  vpc_id      = data.aws_vpc.default.id

  ingress {
    from_port   = 80
    to_port     = 80
    protocol    = "tcp"
    cidr_blocks = [var.allowed_cidr]
  }
  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
}

resource "aws_security_group_rule" "alb_to_api_8080" {
  count                    = var.redundancy_enabled ? 1 : 0
  type                     = "ingress"
  from_port                = 8080
  to_port                  = 8080
  protocol                 = "tcp"
  security_group_id        = aws_security_group.smartbancs.id
  source_security_group_id = aws_security_group.alb[0].id
}

resource "aws_security_group_rule" "postgres_self_5432" {
  count                    = var.redundancy_enabled ? 1 : 0
  type                     = "ingress"
  from_port                = 5432
  to_port                  = 5432
  protocol                 = "tcp"
  security_group_id        = aws_security_group.smartbancs.id
  source_security_group_id = aws_security_group.smartbancs.id
}

# Segunda instancia: SOLO api + ai-service (sin postgres ni observabilidad),
# apuntando la BBDD a la IP privada del primario y con el worker de
# recomendaciones desactivado (SMARTBANCS_AI_WORKER_ENABLED=false) para no
# procesar el outbox dos veces.
resource "aws_instance" "smartbancs_app" {
  count                  = var.redundancy_enabled ? 1 : 0
  ami                    = var.ami_id != "" ? var.ami_id : data.aws_ami.al2023.id
  instance_type          = var.instance_type
  subnet_id              = var.subnet_id != "" ? var.subnet_id : data.aws_subnets.default.ids[0]
  vpc_security_group_ids = [aws_security_group.smartbancs.id]
  iam_instance_profile   = aws_iam_instance_profile.smartbancs_ec2.name
  key_name               = var.key_name != "" ? var.key_name : null
  user_data              = local.user_data_app

  root_block_device {
    volume_size = var.root_volume_size_gb
    volume_type = "gp3"
  }

  tags = {
    Name = "smartbancs-demo-app"
  }
}

resource "aws_lb" "smartbancs" {
  count              = var.redundancy_enabled ? 1 : 0
  name               = "smartbancs-alb"
  internal           = false
  load_balancer_type = "application"
  security_groups    = [aws_security_group.alb[0].id]
  subnets            = data.aws_subnets.default.ids
  tags = {
    Name = "smartbancs-alb"
  }
}

resource "aws_lb_target_group" "api" {
  count       = var.redundancy_enabled ? 1 : 0
  name        = "smartbancs-api"
  port        = 8080
  protocol    = "HTTP"
  vpc_id      = data.aws_vpc.default.id
  target_type = "instance"
  health_check {
    path                = "/actuator/health"
    interval            = 30
    timeout             = 5
    healthy_threshold   = 2
    unhealthy_threshold = 3
  }
  depends_on = [aws_lb.smartbancs]
}

resource "aws_lb_listener" "api" {
  count             = var.redundancy_enabled ? 1 : 0
  load_balancer_arn = aws_lb.smartbancs[0].arn
  port              = 80
  protocol          = "HTTP"
  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.api[0].arn
  }
}

resource "aws_lb_target_group_attachment" "primary" {
  count            = var.redundancy_enabled ? 1 : 0
  target_group_arn = aws_lb_target_group.api[0].arn
  target_id        = aws_instance.smartbancs.id
  port             = 8080
}

resource "aws_lb_target_group_attachment" "app" {
  count            = var.redundancy_enabled ? 1 : 0
  target_group_arn = aws_lb_target_group.api[0].arn
  target_id        = aws_instance.smartbancs_app[0].id
  port             = 8080
}

locals {
  ecr_registry = "${data.aws_caller_identity.current.account_id}.dkr.ecr.${data.aws_region.current.name}.amazonaws.com"
  user_data = templatefile(
    "${path.module}/../deploy/ec2_bootstrap.sh.tpl",
    {
      TF_ECR_REGISTRY    = local.ecr_registry
      TF_REGION          = data.aws_region.current.name
      TF_STATE_BUCKET    = var.state_bucket
      TF_IMAGE_TAG       = var.image_tag
      TF_PGDATA_GB       = var.postgres_volume_size_gb
      TF_SMARTBANCS_ROLE = "full"
      TF_DB_PRIVATE_IP   = ""
    }
  )
  user_data_app = templatefile(
    "${path.module}/../deploy/ec2_bootstrap.sh.tpl",
    {
      TF_ECR_REGISTRY    = local.ecr_registry
      TF_REGION          = data.aws_region.current.name
      TF_STATE_BUCKET    = var.state_bucket
      TF_IMAGE_TAG       = var.image_tag
      TF_PGDATA_GB       = var.postgres_volume_size_gb
      TF_SMARTBANCS_ROLE = "app"
      TF_DB_PRIVATE_IP   = aws_instance.smartbancs.private_ip
    }
  )
}

output "public_ip" {
  description = "IP elástica estable del primario (smartbancs-demo)"
  value       = aws_eip.smartbancs.public_ip
}

output "swagger_url" {
  value = "http://${aws_eip.smartbancs.public_ip}:8080/swagger-ui.html"
}

output "recommendations_init_url" {
  value = "http://${aws_eip.smartbancs.public_ip}:8080/actuator/health"
}

output "grafana_url" {
  value = "http://${aws_eip.smartbancs.public_ip}:3333"
}

output "prometheus_url" {
  value = "http://${aws_eip.smartbancs.public_ip}:9090"
}

output "ai_health_url" {
  value = "http://${aws_eip.smartbancs.public_ip}:8081/health"
}

output "lb_dns" {
  description = "Endpoint del ALB cuando redundancy_enabled=true (una sola URL para las N réplicas)"
  value       = var.redundancy_enabled ? "http://${aws_lb.smartbancs[0].dns_name}" : ""
}