# Secret Manager. The DB password is generated here; app secrets are supplied via var.app_secrets.
# Nothing sensitive is stored in Terraform state in plaintext beyond what GCP already manages.

locals {
  # Connection string uses the Cloud SQL private IP.
  db_url = "jdbc:postgresql://${google_sql_database_instance.postgres.private_ip_address}:5432/${var.db_name}"
}

resource "google_secret_manager_secret" "db_password" {
  secret_id = "${var.name_prefix}-${var.environment}-db-password"
  replication {
    auto {}
  }
  depends_on = [google_project_service.enabled]
}

resource "google_secret_manager_secret_version" "db_password" {
  secret      = google_secret_manager_secret.db_password.id
  secret_data = random_password.db.result
}

resource "google_secret_manager_secret" "db_url" {
  secret_id = "${var.name_prefix}-${var.environment}-db-url"
  replication {
    auto {}
  }
}

resource "google_secret_manager_secret_version" "db_url" {
  secret      = google_secret_manager_secret.db_url.id
  secret_data = local.db_url
}

# Application secrets (jwt-secret, stripe-secret-key, stripe-webhook-secret, openai-api-key, claude-api-key).
# The KEY NAMES are not sensitive (only the values are), so unwrap them for for_each.
resource "google_secret_manager_secret" "app" {
  for_each  = toset(nonsensitive(keys(var.app_secrets)))
  secret_id = "${var.name_prefix}-${var.environment}-${each.value}"
  replication {
    auto {}
  }
  depends_on = [google_project_service.enabled]
}

resource "google_secret_manager_secret_version" "app" {
  for_each    = toset(nonsensitive(keys(var.app_secrets)))
  secret      = google_secret_manager_secret.app[each.key].id
  secret_data = var.app_secrets[each.key]
}
