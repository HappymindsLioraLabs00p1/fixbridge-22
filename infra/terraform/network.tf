# VPC-native networking for GKE, plus private services access for Cloud SQL / Memorystore.

resource "google_compute_network" "vpc" {
  name                    = "${var.name_prefix}-${var.environment}-vpc"
  auto_create_subnetworks = false
  depends_on              = [google_project_service.enabled]
}

resource "google_compute_subnetwork" "subnet" {
  name          = "${var.name_prefix}-${var.environment}-subnet"
  region        = var.region
  network       = google_compute_network.vpc.id
  ip_cidr_range = "10.10.0.0/20"

  # Secondary ranges for GKE pods and services (VPC-native cluster).
  secondary_ip_range {
    range_name    = "pods"
    ip_cidr_range = "10.20.0.0/16"
  }
  secondary_ip_range {
    range_name    = "services"
    ip_cidr_range = "10.30.0.0/20"
  }

  private_ip_google_access = true
}

# Cloud NAT so private GKE nodes can reach the internet (pull images, call Stripe/OpenAI/Claude).
resource "google_compute_router" "router" {
  name    = "${var.name_prefix}-${var.environment}-router"
  region  = var.region
  network = google_compute_network.vpc.id
}

resource "google_compute_router_nat" "nat" {
  name                               = "${var.name_prefix}-${var.environment}-nat"
  router                             = google_compute_router.router.name
  region                             = var.region
  nat_ip_allocate_option             = "AUTO_ONLY"
  source_subnetwork_ip_ranges_to_nat = "ALL_SUBNETWORKS_ALL_IP_RANGES"
}

# Private Services Access — reserved range + VPC peering for Cloud SQL / Memorystore private IP.
resource "google_compute_global_address" "private_services" {
  name          = "${var.name_prefix}-${var.environment}-psa"
  purpose       = "VPC_PEERING"
  address_type  = "INTERNAL"
  prefix_length = 16
  network       = google_compute_network.vpc.id
}

resource "google_service_networking_connection" "private_services" {
  network                 = google_compute_network.vpc.id
  service                 = "servicenetworking.googleapis.com"
  reserved_peering_ranges = [google_compute_global_address.private_services.name]
  depends_on              = [google_project_service.enabled]
}
