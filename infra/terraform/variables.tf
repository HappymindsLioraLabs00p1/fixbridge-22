variable "project_id" {
  type        = string
  description = "GCP project ID. Use a SEPARATE project per environment (staging vs production)."
}

variable "region" {
  type    = string
  default = "us-east1"
}

variable "zone" {
  type    = string
  default = "us-east1-b"
}

variable "environment" {
  type        = string
  description = "staging | production"
  default     = "staging"

  validation {
    condition     = contains(["staging", "production"], var.environment)
    error_message = "environment must be staging or production."
  }
}

variable "name_prefix" {
  type    = string
  default = "fixbridge"
}

# GKE
variable "gke_node_machine_type" {
  type    = string
  default = "e2-standard-2"
}

variable "gke_min_nodes" {
  type    = number
  default = 1
}

variable "gke_max_nodes" {
  type    = number
  default = 4
}

# Cloud SQL
variable "db_tier" {
  type    = string
  default = "db-custom-1-3840"
}

variable "db_name" {
  type    = string
  default = "fixbridge"
}

variable "db_user" {
  type    = string
  default = "fixbridge"
}

# Memorystore
variable "redis_memory_gb" {
  type    = number
  default = 1
}

# Application config surfaced into Secret Manager (values supplied out-of-band, not in tfvars).
variable "app_secrets" {
  type        = map(string)
  description = <<-EOT
    Application secrets to store in Secret Manager. Provide via a secure channel, NOT committed.
    Keys: jwt-secret, stripe-secret-key, stripe-webhook-secret, openai-api-key, claude-api-key
  EOT
  default     = {}
  sensitive   = true
}
