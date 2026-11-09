# The state (Terraform's record of what it created) lives in S3, not in the repo: it contains the
# generated DB password and JWT secret. The bucket comes from bootstrap/ (created once).
# use_lockfile: S3 itself locks the state while someone runs plan/apply, so two runs can't
# overwrite each other (no DynamoDB table needed since Terraform 1.10).
terraform {
  backend "s3" {
    bucket       = "campus-hub-tfstate-099cd54b"
    key          = "campus-hub/terraform.tfstate"
    region       = "eu-central-1"
    encrypt      = true
    use_lockfile = true
  }
}
