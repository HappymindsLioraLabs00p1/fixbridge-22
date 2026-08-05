terraform {
  required_version = ">= 1.6.0"

  required_providers {
    google = {
      source  = "hashicorp/google"
      version = "~> 6.0"
    }
    random = {
      source  = "hashicorp/random"
      version = "~> 3.6"
    }
  }

  # Remote state in GCS. Create the bucket once, then `terraform init`.
  # backend "gcs" {
  #   bucket = "fixbridge-tf-state"
  #   prefix = "fixbridge"
  # }
}

provider "google" {
  project = var.project_id
  region  = var.region
}
