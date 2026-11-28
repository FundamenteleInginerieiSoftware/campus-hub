# Email alerts without anything running on a laptop: AWS publishes events to an SNS topic and
# SNS mails them. Carries both RDS lifecycle notices and 5 CloudWatch operational alarms
# (EC2 status/CPU, RDS storage/CPU, app ERROR logs), staying within AWS CloudWatch's 10-alarm
# and 10-custom-metric free tier.

resource "aws_sns_topic" "alerts" {
  name = "campus-hub-alerts"
}

# Who may publish to the topic: our own account (the default rule), RDS events and CloudWatch
# alarms. The SourceAccount condition stops those services in somebody else's account from
# publishing to it.
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
      {
        Sid       = "CloudWatchAlarms"
        Effect    = "Allow"
        Principal = { Service = "cloudwatch.amazonaws.com" }
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

# --- CloudWatch operational alarms (5 of 10 free-tier alarms) ---
# Every alarm sets treat_missing_data = "notBreaching": when we stop EC2/RDS between demos to
# save AWS credits, metrics stop arriving and must not flood the inbox with false alarms.

# Catches a wedged OS or failed AWS host hardware on the app server.
resource "aws_cloudwatch_metric_alarm" "ec2_status" {
  alarm_name          = "campus-hub-ec2-status"
  alarm_description   = "EC2 instance or underlying AWS host failed status checks for 2 minutes."
  namespace           = "AWS/EC2"
  metric_name         = "StatusCheckFailed"
  statistic           = "Maximum"
  period              = 60
  evaluation_periods  = 2
  threshold           = 1
  comparison_operator = "GreaterThanOrEqualToThreshold"
  treat_missing_data  = "notBreaching"

  dimensions = {
    InstanceId = aws_instance.app.id
  }

  alarm_actions = [aws_sns_topic.alerts.arn]
}

# Sustained high CPU (15 minutes) warns before t3.small burns through its standard CPU credits.
resource "aws_cloudwatch_metric_alarm" "ec2_cpu" {
  alarm_name          = "campus-hub-ec2-cpu"
  alarm_description   = "EC2 average CPU stayed above 80% for 15 minutes."
  namespace           = "AWS/EC2"
  metric_name         = "CPUUtilization"
  statistic           = "Average"
  period              = 300
  evaluation_periods  = 3
  threshold           = 80
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"

  dimensions = {
    InstanceId = aws_instance.app.id
  }

  alarm_actions = [aws_sns_topic.alerts.arn]
}

# 2 GiB free out of the 20 GB gp3 volume (rds.tf) leaves enough runway to expand before writes fail.
resource "aws_cloudwatch_metric_alarm" "rds_storage" {
  count = var.db_enabled ? 1 : 0

  alarm_name          = "campus-hub-rds-low-storage"
  alarm_description   = "RDS free storage dropped below 2 GiB (out of 20 GB allocated)."
  namespace           = "AWS/RDS"
  metric_name         = "FreeStorageSpace"
  statistic           = "Average"
  period              = 300
  evaluation_periods  = 1
  threshold           = 2147483648 # 2 GiB in bytes
  comparison_operator = "LessThanThreshold"
  treat_missing_data  = "notBreaching"

  dimensions = {
    DBInstanceIdentifier = aws_db_instance.main[0].identifier
  }

  alarm_actions = [aws_sns_topic.alerts.arn]
}

resource "aws_cloudwatch_metric_alarm" "rds_cpu" {
  count = var.db_enabled ? 1 : 0

  alarm_name          = "campus-hub-rds-cpu"
  alarm_description   = "RDS average CPU stayed above 80% for 15 minutes."
  namespace           = "AWS/RDS"
  metric_name         = "CPUUtilization"
  statistic           = "Average"
  period              = 300
  evaluation_periods  = 3
  threshold           = 80
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"

  dimensions = {
    DBInstanceIdentifier = aws_db_instance.main[0].identifier
  }

  alarm_actions = [aws_sns_topic.alerts.arn]
}

# Counts Spring Boot ERROR log lines in the container log group (storage.tf) into one custom metric
# (1 of 10 free-tier custom metrics). default_value = "0" publishes a zero when a 5-minute window
# has logs but no errors, so the alarm returns to OK cleanly instead of sticking on missing data.
resource "aws_cloudwatch_log_metric_filter" "app_errors" {
  name           = "campus-hub-app-errors"
  pattern        = "ERROR"
  log_group_name = aws_cloudwatch_log_group.app.name

  metric_transformation {
    name          = "ErrorCount"
    namespace     = "CampusHub/App"
    value         = "1"
    default_value = "0"
  }
}

resource "aws_cloudwatch_metric_alarm" "app_errors" {
  alarm_name          = "campus-hub-app-errors"
  alarm_description   = "Application logged 10 or more ERROR lines within 5 minutes."
  namespace           = "CampusHub/App"
  metric_name         = "ErrorCount"
  statistic           = "Sum"
  period              = 300
  evaluation_periods  = 1
  threshold           = 10
  comparison_operator = "GreaterThanOrEqualToThreshold"
  treat_missing_data  = "notBreaching"

  alarm_actions = [aws_sns_topic.alerts.arn]
}
