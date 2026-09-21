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