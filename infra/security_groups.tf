# Firewall chain from ADR-002:  CloudFront -> app server :8080 -> RDS :5432.
# Security groups are stateful: when a connection is allowed in, its replies go back out
# automatically, so we only write the rules for who may START a connection.
#
# The rules are separate resources (not inline blocks) so changing one rule doesn't
# replace the whole group.

# AWS keeps this list of CloudFront's server IP ranges up to date for us.
data "aws_ec2_managed_prefix_list" "cloudfront" {
  name = "com.amazonaws.global.cloudfront.origin-facing"
}

# --- App server (EC2, W7) ---

resource "aws_security_group" "app" {
  name        = "campus-hub-app"
  description = "App server: HTTP 8080 from CloudFront only, no SSH"
  vpc_id      = aws_vpc.main.id
  tags        = { Name = "campus-hub-app" }
}

# Only CloudFront may call Spring on 8080, so nobody can skip HTTPS by calling the server's IP.
# No port 22: we get a shell through SSM Session Manager, which needs no open port.
# Note: this one rule "weighs" 55 of the default 60 inbound rules per group (one per CloudFront
# IP range), so there is room for only a few more.
resource "aws_vpc_security_group_ingress_rule" "app_from_cloudfront" {
  security_group_id = aws_security_group.app.id
  description       = "Spring Boot from CloudFront"
  prefix_list_id    = data.aws_ec2_managed_prefix_list.cloudfront.id
  ip_protocol       = "tcp"
  from_port         = 8080
  to_port           = 8080
}

# Outgoing HTTPS: ECR (pull the image), SSM (secrets + Session Manager), S3 (photos), SES (emails),
# CloudWatch Logs and OS updates all speak HTTPS on 443. DNS and the time service on the AWS
# link-local addresses don't need a rule.
resource "aws_vpc_security_group_egress_rule" "app_https_out" {
  security_group_id = aws_security_group.app.id
  description       = "AWS APIs, ECR, OS updates"
  cidr_ipv4         = "0.0.0.0/0"
  ip_protocol       = "tcp"
  from_port         = 443
  to_port           = 443
}

resource "aws_vpc_security_group_egress_rule" "app_to_db" {
  security_group_id            = aws_security_group.app.id
  description                  = "PostgreSQL on RDS"
  referenced_security_group_id = aws_security_group.db.id
  ip_protocol                  = "tcp"
  from_port                    = 5432
  to_port                      = 5432
}

# --- Database (RDS, part 2) ---

# No egress rules at all: Terraform removes AWS's default "allow all out" rule, and the database
# never needs to start a connection itself (its replies to the app are covered by statefulness).
resource "aws_security_group" "db" {
  name        = "campus-hub-db"
  description = "RDS PostgreSQL: 5432 from the app server only"
  vpc_id      = aws_vpc.main.id
  tags        = { Name = "campus-hub-db" }
}

# "From the app's security group", not from an IP: whichever server carries that group may connect,
# even after it is replaced and gets a new IP.
resource "aws_vpc_security_group_ingress_rule" "db_from_app" {
  security_group_id            = aws_security_group.db.id
  description                  = "PostgreSQL from the app server"
  referenced_security_group_id = aws_security_group.app.id
  ip_protocol                  = "tcp"
  from_port                    = 5432
  to_port                      = 5432
}
