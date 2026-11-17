# CloudFront in front of the app server (ADR-002): students get HTTPS on a *.cloudfront.net
# address without us buying a domain or a certificate, and the server itself only accepts
# CloudFront (security_groups.tf). Frontend and API come from the same address, so no CORS.

# Bug avoided: CloudFront drops the Authorization header (our JWT) before calling the server,
# unless the header is part of the cache key, and a cache key is only allowed when caching is on.
# So: Authorization in the key, but at most 1 second of caching. Spring Security already sends
# "Cache-Control: no-cache, no-store" on every response, so in practice nothing is cached.
# The key also keeps one user's answer from ever going to another user.
resource "aws_cloudfront_cache_policy" "app" {
  name        = "campus-hub-pass-through"
  comment     = "Forward the JWT and query strings, no real caching"
  min_ttl     = 0
  default_ttl = 0
  max_ttl     = 1

  parameters_in_cache_key_and_forwarded_to_origin {
    headers_config {
      header_behavior = "whitelist"
      headers {
        items = ["Authorization"]
      }
    }
    cookies_config {
      cookie_behavior = "none" # the app uses no cookies (the JWT is in localStorage)
    }
    query_strings_config {
      query_string_behavior = "all" # ?page=0&size=20&sort=..., search filters
    }
    enable_accept_encoding_gzip   = true
    enable_accept_encoding_brotli = true
  }
}

# AWS-managed: pass every other viewer header (Content-Type, Idempotency-Key, ...) and the
# query string to Spring. "Except Host": Spring gets the server's own host name, not ours.
data "aws_cloudfront_origin_request_policy" "all_viewer_except_host" {
  name = "Managed-AllViewerExceptHostHeader"
}

# AWS-managed security headers, HSTS among them. Spring Security only sends HSTS on HTTPS requests,
# and from CloudFront it only ever sees plain HTTP, so CloudFront adds it for us.
data "aws_cloudfront_response_headers_policy" "security_headers" {
  name = "Managed-SecurityHeadersPolicy"
}

resource "aws_cloudfront_distribution" "app" {
  enabled         = true
  comment         = "campus-hub"
  is_ipv6_enabled = true
  http_version    = "http2and3"
  # Edge locations in Europe and North America only: the cheapest class, and our users are in Romania.
  price_class = "PriceClass_100"

  origin {
    origin_id   = "app"
    domain_name = aws_eip.app.public_dns # stays the same as long as the Elastic IP exists

    custom_origin_config {
      http_port  = 8080
      https_port = 443 # unused, but required
      # CloudFront -> server is plain HTTP: the server has no certificate. Browser -> CloudFront
      # is always HTTPS (below). Known limit for the report: the last hop inside AWS is unencrypted.
      origin_protocol_policy = "http-only"
      origin_ssl_protocols   = ["TLSv1.2"]
      origin_read_timeout    = 30
    }
  }

  # One behavior for everything: API, React files and Swagger all come from Spring Boot.
  default_cache_behavior {
    target_origin_id           = "app"
    viewer_protocol_policy     = "redirect-to-https"
    allowed_methods            = ["GET", "HEAD", "OPTIONS", "PUT", "POST", "PATCH", "DELETE"]
    cached_methods             = ["GET", "HEAD"]
    cache_policy_id            = aws_cloudfront_cache_policy.app.id
    origin_request_policy_id   = data.aws_cloudfront_origin_request_policy.all_viewer_except_host.id
    response_headers_policy_id = data.aws_cloudfront_response_headers_policy.security_headers.id
    compress                   = true
  }

  # CloudFront would keep a server error for 10 s by default, e.g. a 502 while Spring restarts
  # during a deploy. 0 = ask the server again on the next request.
  dynamic "custom_error_response" {
    for_each = [500, 502, 503, 504]
    content {
      error_code            = custom_error_response.value
      error_caching_min_ttl = 0
    }
  }

  restrictions {
    geo_restriction {
      restriction_type = "none"
    }
  }

  # The free *.cloudfront.net certificate.
  viewer_certificate {
    cloudfront_default_certificate = true
  }
}
