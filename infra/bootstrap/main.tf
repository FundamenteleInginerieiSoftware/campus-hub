# Creates the S3 bucket that holds the Terraform state of ../ (the real infrastructure).
#
# Chicken-and-egg: the main config keeps its state in this bucket, so the bucket can't be created
# by the main config itself. This tiny config runs once, with a LOCAL state file
# (infra/bootstrap/terraform.tfstate, ignored by git). That file only knows about this one bucket:
# no secrets in it. Losing it is harmless: the bucket keeps working, it just isn't managed anymore.
#
# Run once:  cd infra/bootstrap && terraform init && terraform apply
# Then copy the printed bucket name into ../backend.tf.

terraform {
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
  region = "eu-central-1"

  default_tags {
    tags = {
      Project   = "campus-hub"
      ManagedBy = "terraform-bootstrap"
    }
  }
}

# Bucket names are global across all AWS accounts, so a random suffix avoids clashes
# without putting our account ID in a public repo.
resource "random_id" "suffix" {
  byte_length = 4
}

resource "aws_s3_bucket" "tfstate" {
  bucket = "campus-hub-tfstate-${random_id.suffix.hex}"

  # The state is the only map of what Terraform created. Deleting the bucket by accident would
  # leave every resource orphaned, so Terraform refuses to destroy it.
  lifecycle {
    prevent_destroy = true
  }
}

# Every state write keeps the old version, so a broken apply can be rolled back by hand.
resource "aws_s3_bucket_versioning" "tfstate" {
  bucket = aws_s3_bucket.tfstate.id
  versioning_configuration {
    status = "Enabled"
  }
}

# The state contains the generated DB password and JWT secret (ADR-003): encrypted at rest
# and never public.
resource "aws_s3_bucket_server_side_encryption_configuration" "tfstate" {
  bucket = aws_s3_bucket.tfstate.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

resource "aws_s3_bucket_public_access_block" "tfstate" {
  bucket                  = aws_s3_bucket.tfstate.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

output "state_bucket" {
  value = aws_s3_bucket.tfstate.bucket
}
