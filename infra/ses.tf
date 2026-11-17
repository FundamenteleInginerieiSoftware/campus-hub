# The sender identity for SesNotificationService (#129). Creating it makes AWS email a
# verification link to that address; SES refuses to send from it until someone clicks the link.
# While the account is in the SES sandbox, SES also only delivers TO verified addresses (W9:
# verify the team's emails, or ask AWS for production access).
resource "aws_sesv2_email_identity" "sender" {
  email_identity = var.mail_from
}
