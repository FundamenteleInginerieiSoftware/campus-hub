# PostgreSQL 18 on RDS (ADR-002): small, private, encrypted, single-AZ.
#
# db_enabled switch: RDS is the only part-2 resource that costs money while it exists
# (~$0.50/day), and it is useless before the app server exists (W7). So it stays off until the
# first deploy:   terraform apply -var db_enabled=true
# Turning it back off (false) DELETES the database and its data, without a final snapshot.
# Careful: once it is on, every later apply must pass -var db_enabled=true too, or that apply
# deletes it. So the W7 deploy PR flips the default in variables.tf to true.

resource "aws_db_subnet_group" "main" {
  name       = "campus-hub"
  subnet_ids = aws_subnet.db[*].id
  tags       = { Name = "campus-hub" }
}

resource "aws_db_instance" "main" {
  count = var.db_enabled ? 1 : 0

  identifier     = "campus-hub"
  engine         = "postgres"
  engine_version = "18" # newest 18.x; AWS applies minor updates in the maintenance window
  instance_class = "db.t4g.micro"

  allocated_storage = 20
  storage_type      = "gp3"
  storage_encrypted = true # with the AWS-managed RDS key

  # Same names as on every laptop (AGENTS.md §5), so DB_URL is the only difference.
  db_name  = "campus"
  username = "campus"
  password = random_password.db.result

  db_subnet_group_name   = aws_db_subnet_group.main.name
  vpc_security_group_ids = [aws_security_group.db.id]
  publicly_accessible    = false # no public IP; reachable only from the app server (security_groups.tf)
  multi_az               = false # course budget; "Multi-AZ in production" goes in the report

  backup_retention_period = 1 # one daily snapshot, enough to practise a restore in W10

  # Course settings: `terraform destroy` must work without manual steps, and no paid snapshot
  # is left behind. In production both would be the other way round.
  deletion_protection = false
  skip_final_snapshot = true

  # Changes (e.g. a new password) apply now instead of waiting for the weekly maintenance window.
  apply_immediately = true

  tags = { Name = "campus-hub" }
}
