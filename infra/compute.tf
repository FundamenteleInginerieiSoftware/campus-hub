# The app server (ADR-002): one EC2 instance running our Docker image, with a fixed public
# address for CloudFront (cdn.tf). It holds no data: the database is RDS and photos are in S3,
# so the server can be thrown away and recreated at any time.

# The newest Amazon Linux 2023 image for x86. AWS publishes its ID under this SSM name.
# x86, not ARM (Graviton t4g): our image is built on x86 laptops and in x86 CI, and an ARM server
# can't run an x86 image. t3.small costs ~$3/month more than t4g.small; that is cheaper than
# cross-building the Gradle + Node build for ARM on every deploy.
data "aws_ssm_parameter" "al2023" {
  name = "/aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-x86_64"
}

resource "aws_instance" "app" {
  ami                    = data.aws_ssm_parameter.al2023.value
  instance_type          = "t3.small" # 2 vCPU, 2 GB: the JVM takes 75% (Dockerfile MaxRAMPercentage)
  subnet_id              = aws_subnet.public.id
  vpc_security_group_ids = [aws_security_group.app.id]
  iam_instance_profile   = aws_iam_instance_profile.app.name

  # First boot only: install Docker, then run the newest image from ECR (user-data.sh.tftpl).
  user_data = templatefile("${path.module}/user-data.sh.tftpl", {
    region         = var.aws_region
    repository_url = aws_ecr_repository.app.repository_url
    log_group      = aws_cloudwatch_log_group.app.name
  })
  # The script runs only on the first boot, so a changed script means a new server.
  user_data_replace_on_change = true

  # campus-deploy reads /campus/* once, on the first boot. Wait until every parameter exists, or
  # the env file has no DB_URL and the container restarts forever (RDS alone takes 5-10 minutes).
  depends_on = [
    aws_ssm_parameter.db_url,
    aws_ssm_parameter.mail_from,
    aws_ssm_parameter.admin_email,
    aws_ssm_parameter.admin_password,
  ]

  # "standard": a t3 that stays busy slows down instead of buying extra CPU time on the bill
  # (AWS's default for t3 is "unlimited", which is billed).
  credit_specification {
    cpu_credits = "standard"
  }

  metadata_options {
    # IMDSv2 only: the instance credentials can only be read with a session token, which blocks
    # the classic SSRF trick of making the app fetch http://169.254.169.254/... for an attacker.
    http_tokens = "required"
    # 2 hops, not the default 1: the app runs inside a Docker container, one network hop behind
    # the host. With 1, the AWS SDK in S3StorageService / SesNotificationService gets no credentials.
    http_put_response_hop_limit = 2
  }

  root_block_device {
    volume_type = "gp3"
    volume_size = 20 # OS + a few image versions; up to 30 GB of EBS is in the free tier
    encrypted   = true
  }

  lifecycle {
    # AWS publishes a new Amazon Linux image every few weeks. Without this, the next apply after
    # that would replace the server. OS updates: `sudo dnf upgrade --releasever=latest` in a
    # Session Manager shell (Amazon Linux 2023 pins each server to its image's package version).
    ignore_changes = [ami]
  }

  tags = { Name = "campus-hub-app" }
}

# A fixed public IP. CloudFront needs a host name that never changes; the automatic public IP
# changes on every stop/start. Same price as the automatic one (~$3.6/month for any public IPv4).
resource "aws_eip" "app" {
  domain   = "vpc"
  instance = aws_instance.app.id
  tags     = { Name = "campus-hub-app" }
}
