# Email alerts without anything running on a laptop: AWS publishes events to an SNS topic and
# SNS mails them. Today it only carries the RDS "restarted itself" notice; other alarms
# (budget, CloudWatch) can publish to the same topic later.

resource "aws_sns_topic" "alerts" {
  name = "campus-hub-alerts"
}

# Who may publish to the topic: our own account (the default rule) and the RDS event service.
# The SourceAccount condition stops RDS in somebody else's account from publishing to it.
resource "aws_sns_topic_policy" "alerts" {
  arn = aws_sns_topic.alerts.arn

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid       = "AccountOwner"
        Effect    = "Allow"
        Principal = { AWS = "*" }
        Action = [
          "sns:Publish",
          "sns:Subscribe",
          "sns:Receive",
          "sns:GetTopicAttributes",
          "sns:SetTopicAttributes",
          "sns:AddPermission",
          "sns:RemovePermission",
          "sns:DeleteTopic",
          "sns:ListSubscriptionsByTopic",
        ]
        Resource  = aws_sns_topic.alerts.arn
        Condition = { StringEquals = { "AWS:SourceOwner" = data.aws_caller_identity.current.account_id } }
      },
      {
        Sid       = "RdsEvents"
        Effect    = "Allow"
        Principal = { Service = "events.rds.amazonaws.com" }
        Action    = "sns:Publish"
        Resource  = aws_sns_topic.alerts.arn
        Condition = { StringEquals = { "aws:SourceAccount" = data.aws_caller_identity.current.account_id } }
      },
    ]
  })
}

# AWS emails a confirmation link to this address first; nothing arrives until it is clicked
# (Terraform can't click it for us, so do it once after the first apply).
resource "aws_sns_topic_subscription" "alerts_email" {
  topic_arn = aws_sns_topic.alerts.arn
  protocol  = "email"
  endpoint  = var.alert_email
}

# A stopped RDS instance is started again by AWS after 7 days and bills again. That start is
# event RDS-EVENT-0154 ("started because it was stopped for 7 days"), category "notification",
# so a paused database that starts costing money is never silent. Only exists while the
# database does (db_enabled, like rds.tf).
resource "aws_db_event_subscription" "rds" {
  count = var.db_enabled ? 1 : 0

  name             = "campus-hub-rds"
  sns_topic        = aws_sns_topic.alerts.arn
  source_type      = "db-instance"
  source_ids       = aws_db_instance.main[*].identifier
  event_categories = ["notification"]
}
