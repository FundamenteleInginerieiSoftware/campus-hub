# Read with `terraform output`.

output "vpc_id" {
  value = aws_vpc.main.id
}

output "public_subnet_id" {
  value = aws_subnet.public.id
}

output "db_subnet_ids" {
  value = aws_subnet.db[*].id
}

output "app_security_group_id" {
  value = aws_security_group.app.id
}

output "db_security_group_id" {
  value = aws_security_group.db.id
}

output "photo_bucket" {
  value = aws_s3_bucket.photos.bucket
}

output "ecr_repository_url" {
  description = "Where `docker push` sends the image (W6/W8)."
  value       = aws_ecr_repository.app.repository_url
}

output "app_instance_profile" {
  description = "Given to the EC2 instance in W7."
  value       = aws_iam_instance_profile.app.name
}

output "db_endpoint" {
  description = "host:port of RDS, or null while db_enabled = false."
  value       = var.db_enabled ? aws_db_instance.main[0].endpoint : null
}

output "app_url" {
  description = "The app on AWS: React pages, /api/v1/..., /swagger-ui.html."
  value       = "https://${aws_cloudfront_distribution.app.domain_name}"
}

output "app_instance_id" {
  description = "For a shell on the server:  aws ssm start-session --target <id>"
  value       = aws_instance.app.id
}

output "admin_password" {
  description = "First admin's password (terraform output -raw admin_password)."
  value       = random_password.admin.result
  sensitive   = true
}

output "github_deploy_role_arn" {
  description = "For deploy.yml:  gh variable set AWS_DEPLOY_ROLE_ARN --body \"$(terraform output -raw github_deploy_role_arn)\""
  value       = aws_iam_role.github_deploy.arn
}
