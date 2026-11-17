variable "aws_region" {
  description = "Region for everything (ADR-005: Frankfurt)."
  type        = string
  default     = "eu-central-1"
}

variable "vpc_cidr" {
  description = "Address range of our VPC. The subnets in network.tf are /24 slices of it."
  type        = string
  default     = "10.0.0.0/16"
}

variable "db_enabled" {
  description = "Create the RDS database (costs money while it exists). true since the W7 deploy; setting it to false DELETES the database (rds.tf)."
  type        = bool
  default     = true
}

variable "mail_from" {
  description = "Sender address of the app's emails (MAIL_FROM). Must be verified in SES: AWS emails a link to it after the first apply (ses.tf). Set in terraform.tfvars, which is never committed."
  type        = string
}

variable "admin_email" {
  description = "Login of the first admin on AWS (ADMIN_EMAIL, AdminBootstrap). Set in terraform.tfvars."
  type        = string
}

variable "alert_email" {
  description = "Gets AWS alerts, e.g. RDS restarted itself after 7 days stopped. Set in terraform.tfvars."
  type        = string
}
