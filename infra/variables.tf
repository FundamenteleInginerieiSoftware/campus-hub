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
  description = "Create the RDS database (costs money while it exists). false until the first deploy in W7; see rds.tf."
  type        = bool
  default     = false
}
