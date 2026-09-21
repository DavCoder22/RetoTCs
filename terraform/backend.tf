terraform {
  backend "s3" {
    bucket         = "smartbancs-tfstate"
    key            = "reto-tcs/terraform.tfstate"
    region         = "us-east-1"
    dynamodb_table = "terraform-locks"
  }
}