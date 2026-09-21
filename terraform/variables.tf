variable "region" {
  description = "AWS region where resources will be created"
  type        = string
  default     = "us-east-1"
}

variable "profile" {
  description = "AWS CLI profile to use (from ~/.aws/credentials). Leave empty to use the default chain."
  type        = string
  default     = ""
}

variable "state_bucket" {
  description = "S3 bucket for terraform state and the deploy bundle (created by the workflow)."
  type        = string
  default     = "smartbancs-tfstate"
}

variable "openrouter_api_key" {
  description = "OpenRouter API key (NUNCA en tfvars; la pasa CI desde el secret OPENROUTER_API_KEY)."
  type        = string
  default     = ""
  sensitive   = true
}

variable "instance_type" {
  description = "Tipo de instancia permitido por el Free Plan de esta cuenta (creada post-jul-2025): t3.micro/t3.small/t4g/c7i-flex.large/m7i-flex.large. t3.medium NO se puede lanzar (error 'not eligible for Free Tier'). m7i-flex.large = 8GB."
  type        = string
  default     = "m7i-flex.large"
}

variable "ami_id" {
  description = "AMI override (vacío => Amazon Linux 2023 más reciente)."
  type        = string
  default     = ""
}

variable "key_name" {
  description = "Opcional: par de claves EC2 existente para SSH."
  type        = string
  default     = ""
}

variable "subnet_id" {
  description = "Subnet override (vacío => primera subred de la VPC default)."
  type        = string
  default     = ""
}

variable "allowed_cidr" {
  description = "CIDR con acceso HTTP/observabilidad (demo)."
  type        = string
  default     = "0.0.0.0/0"
}

variable "root_volume_size_gb" {
  description = "Tamaño del disco de la EC2 (imágenes de observabilidad ocupan espacio)."
  type        = number
  default     = 30
}

variable "postgres_volume_size_gb" {
  description = "Volumen EBS dedicado para la BBDD. El bootstrap lo monta en /var/lib/docker: 'postgres-data' persiste ante sustitución de la EC2 y no hay pérdida de datos."
  type        = number
  default     = 20
}

variable "redundancy_enabled" {
  description = <<-EOT
    true => activa la ESTRATEGIA DE REDUNDANCIA para cubrir la demanda (>10.000 tx):
    un ALB delante y una 2ª EC2 (solo api+ai) replicando las capas sin estado;
    la BBDD única (ACID) queda en el primario. false => instancia única (actual).
  EOT
  type        = bool
  default     = false
}

variable "image_tag" {
  description = "Tag de las imágenes en ECR (por defecto latest)."
  type        = string
  default     = "latest"
}