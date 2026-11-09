# Network for ADR-002: one public subnet for the EC2 app server, two private subnets for RDS.
# No NAT gateway (~$35/month): the server sits in the public subnet and reaches AWS services
# over the internet gateway; the database subnets have no route out at all.

# Lists the AZs this account can use in the region (eu-central-1a, -1b, ...).
data "aws_availability_zones" "available" {
  state = "available"
}

resource "aws_vpc" "main" {
  cidr_block = var.vpc_cidr

  # RDS gives us a host name, not an IP; the app can only resolve it with DNS support on.
  enable_dns_support   = true
  enable_dns_hostnames = true

  tags = { Name = "campus-hub" }
}

# The VPC's door to the internet. Only subnets whose route table points here can use it.
resource "aws_internet_gateway" "main" {
  vpc_id = aws_vpc.main.id
  tags   = { Name = "campus-hub" }
}

# --- Public subnet: the EC2 app server (W7) ---

resource "aws_subnet" "public" {
  vpc_id            = aws_vpc.main.id
  cidr_block        = cidrsubnet(var.vpc_cidr, 8, 1) # 10.0.1.0/24
  availability_zone = data.aws_availability_zones.available.names[0]

  # The server needs a public IP to pull images from ECR and talk to SSM without a NAT gateway.
  # Being public doesn't open it up: its security group only lets CloudFront in (security_groups.tf).
  map_public_ip_on_launch = true

  tags = { Name = "campus-hub-public" }
}

resource "aws_route_table" "public" {
  vpc_id = aws_vpc.main.id

  # Everything that isn't inside the VPC goes out through the internet gateway.
  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = aws_internet_gateway.main.id
  }

  tags = { Name = "campus-hub-public" }
}

resource "aws_route_table_association" "public" {
  subnet_id      = aws_subnet.public.id
  route_table_id = aws_route_table.public.id
}

# --- Private subnets: RDS only ---

# Two subnets in two different AZs: RDS refuses a subnet group that covers only one AZ, even for
# a single-AZ database (so it could fail over to the other AZ if we ever turned Multi-AZ on).
resource "aws_subnet" "db" {
  count             = 2
  vpc_id            = aws_vpc.main.id
  cidr_block        = cidrsubnet(var.vpc_cidr, 8, 11 + count.index) # 10.0.11.0/24, 10.0.12.0/24
  availability_zone = data.aws_availability_zones.available.names[count.index]

  tags = { Name = "campus-hub-db-${count.index + 1}" }
}

# A route table with no routes besides the built-in "local" one (inside the VPC).
# So the database can talk to the app server and nothing else: no way in or out of the internet.
resource "aws_route_table" "db" {
  vpc_id = aws_vpc.main.id
  tags   = { Name = "campus-hub-db" }
}

resource "aws_route_table_association" "db" {
  count          = 2
  subnet_id      = aws_subnet.db[count.index].id
  route_table_id = aws_route_table.db.id
}
