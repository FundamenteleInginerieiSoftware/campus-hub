terraform {
  # 1.10+ is needed for S3-native state locking (use_lockfile in backend.tf).
  required_version = ">= 1.10"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.72"
    }
    random = {
      source  = "hashicorp/random"
      version = "~> 3.9"
    }
  }
}

provider "aws" {
  region = var.aws_region

  # Added to every resource Terraform creates, so Cost Explorer can filter our bill by Project
  # and anyone looking in the console can tell what Terraform owns.
  default_tags {
    tags = {
      Project   = "campus-hub"
      ManagedBy = "terraform"
    }
  }
}
