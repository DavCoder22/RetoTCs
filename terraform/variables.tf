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
  description = "EC2 instance type (reto: se necesita RAM para Java+Python+observabilidad)."
  type        = string
  default     = "t3.medium"
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

variable "image_tag" {
  description = "Tag de las imágenes en ECR (por defecto latest)."
  type        = string
  default     = "latest"
}