# The deploy role for GitHub Actions (W8, ADR-002): .github/workflows/deploy.yml wears this role to
# push the image to ECR and tell the server to run it. GitHub OIDC instead of AWS keys: for every
# run, GitHub signs a short-lived token that says which repository, branch and workflow is asking;
# AWS checks that signature and hands out credentials that expire after one hour. Nothing secret
# is stored in GitHub, so there is no key to leak or rotate.

# "AWS trusts tokens signed by GitHub Actions." One per AWS account; no thumbprint needed, AWS
# checks GitHub's certificate itself.
resource "aws_iam_openid_connect_provider" "github" {
  url            = "https://token.actions.githubusercontent.com"
  client_id_list = ["sts.amazonaws.com"]
}

locals {
  # The token's "sub" claim for runs on main. Our repository uses GitHub's immutable subject
  # format (name@id), so a repository deleted and recreated under the same name by someone else
  # gets different IDs and can't wear the role. Check the prefix with:
  #   gh api repos/FundamenteleInginerieiSoftware/campus-hub/actions/oidc/customization/sub
  # Branch, not a GitHub environment: main is already protected (PR + review + green ci), and an
  # environment would need extra repo settings to stop other branches from using it.
  github_deploy_subject = "repo:FundamenteleInginerieiSoftware@338709262/campus-hub@1407699804:ref:refs/heads/main"
}

resource "aws_iam_role" "github_deploy" {
  name = "campus-hub-github-deploy"

  # "Who may wear this role": only a workflow run of our repository on main. A PR branch, a fork
  # or another repository gets a token with a different "sub" and AWS refuses it.
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Federated = aws_iam_openid_connect_provider.github.arn }
      Action    = "sts:AssumeRoleWithWebIdentity"
      Condition = {
        StringEquals = {
          "token.actions.githubusercontent.com:aud" = "sts.amazonaws.com"
          "token.actions.githubusercontent.com:sub" = local.github_deploy_subject
        }
      }
    }]
  })

  # The deploy finishes in minutes; the default hour is plenty.
  max_session_duration = 3600
}

resource "aws_iam_role_policy" "github_deploy" {
  name = "campus-hub-github-deploy"
  role = aws_iam_role.github_deploy.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        # amazon-ecr-login exchanges the role for a `docker login` password. AWS only accepts "*"
        # for this action; the password alone allows nothing, the next statement decides what.
        Sid      = "EcrLogin"
        Effect   = "Allow"
        Action   = "ecr:GetAuthorizationToken"
        Resource = "*"
      },
      {
        # docker push to our one repository. DescribeImages: the workflow first checks whether the
        # commit's image is already there (a re-run or a rollback), because tags are immutable
        # (storage.tf) and a second push of the same tag would fail.
        Sid    = "EcrPush"
        Effect = "Allow"
        Action = [
          "ecr:BatchCheckLayerAvailability",
          "ecr:InitiateLayerUpload",
          "ecr:UploadLayerPart",
          "ecr:CompleteLayerUpload",
          "ecr:PutImage",
          "ecr:DescribeImages",
        ]
        Resource = aws_ecr_repository.app.arn
      },
      {
        # "Is the server running?" AWS paused to save credits (doc 56) means skip, not fail.
        # DescribeInstances only reads, and AWS doesn't support limiting it to one instance.
        Sid      = "FindServer"
        Effect   = "Allow"
        Action   = "ec2:DescribeInstances"
        Resource = "*"
      },
      {
        # SSM Run Command: `campus-deploy <sha>` (user-data.sh.tftpl) on our server only, and only
        # through AWS's plain shell document. AWS checks SendCommand against both ARNs.
        # The AWS-owned document has no account ID in its ARN.
        Sid    = "RunDeployScript"
        Effect = "Allow"
        Action = "ssm:SendCommand"
        Resource = [
          "arn:aws:ssm:${var.aws_region}::document/AWS-RunShellScript",
          "arn:aws:ec2:${var.aws_region}:${data.aws_caller_identity.current.account_id}:instance/${aws_instance.app.id}",
        ]
      },
      {
        # Wait for the script and print its output in the GitHub log. Read-only, and AWS doesn't
        # support limiting it to one command.
        Sid      = "ReadDeployResult"
        Effect   = "Allow"
        Action   = "ssm:GetCommandInvocation"
        Resource = "*"
      },
    ]
  })
}
