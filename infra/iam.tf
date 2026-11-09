# The EC2 instance role (ADR-002): the app server's identity. The AWS SDK inside the container
# finds these credentials on its own (instance metadata), so no AWS keys ever sit on the server,
# in the image or in GitHub. Each permission is limited to our own resources.

resource "aws_iam_role" "app" {
  name = "campus-hub-app"

  # "Who may wear this role": only EC2 instances.
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "ec2.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })
}

# EC2 can't take a role directly; it takes an "instance profile" that wraps the role.
resource "aws_iam_instance_profile" "app" {
  name = "campus-hub-app"
  role = aws_iam_role.app.name
}

# AWS-managed policies:
# - SSM core: Session Manager (our shell, instead of SSH) and Run Command (the W8 deploy).
# - ECR read-only: `docker pull` of our image.
resource "aws_iam_role_policy_attachment" "ssm_core" {
  role       = aws_iam_role.app.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
}

resource "aws_iam_role_policy_attachment" "ecr_read" {
  role       = aws_iam_role.app.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonEC2ContainerRegistryReadOnly"
}

data "aws_caller_identity" "current" {}

resource "aws_iam_role_policy" "app" {
  name = "campus-hub-app"
  role = aws_iam_role.app.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        # S3StorageService (#129): store, load and delete photos, and nothing outside our bucket.
        Sid      = "PhotoObjects"
        Effect   = "Allow"
        Action   = ["s3:PutObject", "s3:GetObject", "s3:DeleteObject"]
        Resource = "${aws_s3_bucket.photos.arn}/*"
      },
      {
        # Without ListBucket, S3 answers 403 instead of 404 for a missing photo, and
        # S3StorageService can't tell "not found" from "not allowed" (#177, doc 29).
        Sid      = "PhotoList"
        Effect   = "Allow"
        Action   = "s3:ListBucket"
        Resource = aws_s3_bucket.photos.arn
      },
      {
        # SesNotificationService (#129). SES itself only sends from verified identities (W9),
        # and only to verified addresses while the account is in the SES sandbox.
        Sid      = "SendEmail"
        Effect   = "Allow"
        Action   = "ses:SendEmail"
        Resource = "*"
      },
      {
        # The deploy script reads /campus/* into the container's environment (ADR-003).
        # SecureString values are decrypted with the AWS-managed aws/ssm key, whose key policy
        # already allows that through SSM, so no kms:Decrypt is needed here.
        # Two ARNs: GetParametersByPath is checked against the folder itself ("parameter/campus"),
        # which "parameter/campus/*" alone doesn't match.
        Sid    = "ReadAppParameters"
        Effect = "Allow"
        Action = ["ssm:GetParameter", "ssm:GetParameters", "ssm:GetParametersByPath"]
        Resource = [
          "arn:aws:ssm:${var.aws_region}:${data.aws_caller_identity.current.account_id}:parameter/campus",
          "arn:aws:ssm:${var.aws_region}:${data.aws_caller_identity.current.account_id}:parameter/campus/*",
        ]
      },
      {
        # Docker's awslogs driver (W7) writes the container output to our log group only.
        Sid      = "WriteLogs"
        Effect   = "Allow"
        Action   = ["logs:CreateLogStream", "logs:PutLogEvents"]
        Resource = "${aws_cloudwatch_log_group.app.arn}:*"
      },
    ]
  })
}
