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

resource "aws_ssm_parameter" "openrouter_api_key" {
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
  statement {
    actions   = ["ssm:GetParameter"]
    resources = [aws_ssm_parameter.openrouter_api_key.arn]
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

resource "aws_iam_instance_profile" "smartbancs_ec2" {
  name = "smartbancs-ec2"
  role = aws_iam_role.smartbancs_ec2.name
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

locals {
  ecr_registry = "${data.aws_caller_identity.current.account_id}.dkr.ecr.${data.aws_region.current.name}.amazonaws.com"
  user_data = templatefile(
    "${path.module}/../deploy/ec2_bootstrap.sh.tpl",
    {
      TF_ECR_REGISTRY = local.ecr_registry
      TF_REGION       = data.aws_region.current.name
      TF_STATE_BUCKET = var.state_bucket
      TF_IMAGE_TAG    = var.image_tag
    }
  )
}

output "public_ip" {
  value = aws_instance.smartbancs.public_ip
}

output "swagger_url" {
  value = "http://${aws_instance.smartbancs.public_ip}:8080/swagger-ui.html"
}

output "recommendations_init_url" {
  value = "http://${aws_instance.smartbancs.public_ip}:8080/actuator/health"
}

output "grafana_url" {
  value = "http://${aws_instance.smartbancs.public_ip}:3333"
}

output "prometheus_url" {
  value = "http://${aws_instance.smartbancs.public_ip}:9090"
}

output "ai_health_url" {
  value = "http://${aws_instance.smartbancs.public_ip}:8081/health"
}