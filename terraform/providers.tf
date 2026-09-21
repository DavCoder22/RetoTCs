provider "aws" {
  region  = var.region
  profile = var.profile != "" ? var.profile : null
}