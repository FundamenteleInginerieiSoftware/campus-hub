# S3 bucket for incident photos (S3StorageService, #129) and the ECR repository for the
# Docker image (Dockerfile, #114).

# Bucket names are global across all AWS accounts; the suffix avoids clashes without putting our
# account ID in a public repo. Generated once, then kept in the state.
resource "random_id" "suffix" {
  byte_length = 4
}

# --- Photos ---

resource "aws_s3_bucket" "photos" {
  bucket = "campus-hub-photos-${random_id.suffix.hex}"

  # `terraform destroy` after grading also deletes the photos (they are test data).
  force_destroy = true
}

# Photos are only ever served through the backend (Stefania's GET /incidents/{id}/photo checks
# who may see them), so the bucket itself is never public.
resource "aws_s3_bucket_public_access_block" "photos" {
  bucket                  = aws_s3_bucket.photos.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_server_side_encryption_configuration" "photos" {
  bucket = aws_s3_bucket.photos.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

# --- Docker images ---

resource "aws_ecr_repository" "app" {
  name = "campus-hub"

  # Each image is tagged with its commit SHA (ADR-002 deploy): a tag can't be reused for a
  # different image, so "roll back to SHA x" always gets exactly what was tested.
  image_tag_mutability = "IMMUTABLE"

  # Free basic scan for known vulnerabilities on every push.
  image_scanning_configuration {
    scan_on_push = true
  }

  # Lets `terraform destroy` delete the repository even with images in it.
  force_delete = true
}

# Storage is billed per GB; old images are only needed for a rollback, so keep the last 10.
resource "aws_ecr_lifecycle_policy" "app" {
  repository = aws_ecr_repository.app.name
  policy = jsonencode({
    rules = [{
      rulePriority = 1
      description  = "Keep the last 10 images"
      selection = {
        tagStatus   = "any"
        countType   = "imageCountMoreThan"
        countNumber = 10
      }
      action = { type = "expire" }
    }]
  })
}

# --- Logs ---

# The container's stdout goes here (Docker awslogs driver, W7). 7 days keeps the cost near zero;
# without a retention, CloudWatch keeps (and bills) logs forever.
resource "aws_cloudwatch_log_group" "app" {
  name              = "/campus-hub/app"
  retention_in_days = 7
}
