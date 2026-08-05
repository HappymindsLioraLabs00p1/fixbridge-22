# Private GCS bucket for photos/video/documents (served via signed URLs), and Artifact Registry.

resource "google_storage_bucket" "media" {
  name                        = "${var.project_id}-${var.name_prefix}-${var.environment}-media"
  location                    = var.region
  uniform_bucket_level_access = true
  public_access_prevention    = "enforced"

  versioning {
    enabled = true
  }

  cors {
    origin          = ["*"] # tighten to the app domain in production
    method          = ["GET", "PUT"]
    response_header = ["Content-Type"]
    max_age_seconds = 3600
  }
}

resource "google_artifact_registry_repository" "docker" {
  location      = var.region
  repository_id = "${var.name_prefix}-docker"
  format        = "DOCKER"
  description   = "FixBridge container images"
  depends_on    = [google_project_service.enabled]
}
