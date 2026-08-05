# Workload Identity: a GCP service account the backend pods impersonate (no key files).
# The Kubernetes service account "fixbridge-backend" in namespace "fixbridge" is bound to it.

locals {
  k8s_namespace       = "fixbridge"
  k8s_service_account = "fixbridge-backend"
}

resource "google_service_account" "backend" {
  account_id   = "${var.name_prefix}-${var.environment}-be"
  display_name = "FixBridge backend (${var.environment})"
}

# Let the backend read secrets, connect to Cloud SQL, and manage objects in the media bucket only.
resource "google_project_iam_member" "backend_secret_accessor" {
  project = var.project_id
  role    = "roles/secretmanager.secretAccessor"
  member  = "serviceAccount:${google_service_account.backend.email}"
}

resource "google_project_iam_member" "backend_cloudsql_client" {
  project = var.project_id
  role    = "roles/cloudsql.client"
  member  = "serviceAccount:${google_service_account.backend.email}"
}

resource "google_storage_bucket_iam_member" "backend_media_admin" {
  bucket = google_storage_bucket.media.name
  role   = "roles/storage.objectAdmin"
  member = "serviceAccount:${google_service_account.backend.email}"
}

# Bind the Kubernetes SA to the GCP SA (Workload Identity).
resource "google_service_account_iam_member" "workload_identity" {
  service_account_id = google_service_account.backend.name
  role               = "roles/iam.workloadIdentityUser"
  member             = "serviceAccount:${var.project_id}.svc.id.goog[${local.k8s_namespace}/${local.k8s_service_account}]"
}
