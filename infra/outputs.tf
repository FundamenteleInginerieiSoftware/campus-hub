# Read with `terraform output`. The W7 deploy (EC2 + CloudFront) uses these IDs.

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
