/** Shared types mirroring the backend DTOs. */

export type UserRole = "customer" | "landlord" | "agent" | "contractor" | "admin" | "partner";

export type JobStatus =
  | "draft"
  | "ai_review_complete"
  | "awaiting_service_payment"
  | "paid_for_dispatch"
  | "awaiting_contractor"
  | "contractor_invited"
  | "contractor_accepted"
  | "awaiting_bid"
  | "bid_received"
  | "proposal_sent"
  | "awaiting_customer_approval"
  | "approved"
  | "scheduled"
  | "contractor_en_route"
  | "work_started"
  | "change_order_pending"
  | "work_completed"
  | "customer_review_pending"
  | "admin_review_pending"
  | "payout_pending"
  | "paid_out"
  | "closed"
  | "canceled"
  | "refunded"
  | "disputed";

export type AiUrgency = "low" | "medium" | "high" | "emergency";

export interface UserView {
  id: string;
  email: string;
  fullName: string | null;
  roles: UserRole[];
}

export interface TokenResponse {
  accessToken: string;
  refreshToken: string;
  tokenType: string;
  user: UserView;
}

export interface Property {
  id: string;
  label: string | null;
  line1: string;
  city: string | null;
  state: string | null;
  postalCode: string | null;
  createdAt: string;
}

export interface AssessmentView {
  category: string;
  summary: string;
  urgency: AiUrgency;
  confidence: number;
  recommendedTrade: string;
  professionalRequired: boolean;
  safeDiyAllowed: boolean;
  immediateSafetySteps: string[];
  disclaimer: string;
}

export interface EstimateView {
  priceAvailable: boolean;
  retailLowCents: number | null;
  retailHighCents: number | null;
  message: string;
  disclaimer: string;
}

export interface MediaView {
  mediaType: string;
  url: string;
}

export interface JobDetail {
  id: string;
  status: JobStatus;
  title: string | null;
  description: string | null;
  preferredTime: string | null;
  assessment: AssessmentView | null;
  estimate: EstimateView | null;
  media: MediaView[];
  createdAt: string;
}

export interface UploadTarget {
  objectKey: string;
  uploadUrl: string;
  method: string;
  contentType: string;
}

export interface PlanView {
  code: string;
  name: string;
  blurb: string;
  audience: string;
  interval: string;
  available: boolean;
}

export interface SubscriptionView {
  planCode: string;
  status: string;
  currentPeriodEnd: string | null;
}

export interface BillingCheckout {
  sessionId: string;
  url: string;
}

export interface NotificationItem {
  template: string;
  channel: string;
  jobId: string | null;
  createdAt: string;
}

export interface JobSummary {
  id: string;
  status: JobStatus;
  title: string | null;
  createdAt: string;
}

export interface CheckoutView {
  sessionId: string;
  url: string;
  amountCents: number;
  currency: string;
}

export interface CustomerProposal {
  proposalId: string;
  jobId: string;
  scope: string | null;
  retailTotalCents: number;
  depositCents: number;
  timeline: string | null;
  warranty: string | null;
  exclusions: string | null;
  terms: string | null;
  status: string;
}

export interface ContractorInvitation {
  jobId: string;
  status: string;
  generalArea: string;
  recommendedTrade: string | null;
  urgency: AiUrgency | null;
  expectedNetCents: number | null;
}

export interface AdminJob {
  jobId: string;
  status: JobStatus;
  title: string | null;
  category: string | null;
  urgency: AiUrgency | null;
  estContractorNetLow: number | null;
  estContractorNetHigh: number | null;
  customerRetailLow: number | null;
  customerRetailHigh: number | null;
}

export interface AdminProposal {
  proposalId: string;
  jobId: string;
  contractorNetCents: number;
  retailTotalCents: number;
  marginCents: number;
  status: string;
}

export interface CustomerChangeOrder {
  id: string;
  jobId: string;
  description: string;
  addedRetailCents: number;
  addedDays: number | null;
  status: string;
}

export interface AdminChangeOrder {
  id: string;
  jobId: string;
  description: string;
  addedNetCents: number;
  addedRetailCents: number;
  marginCents: number;
  addedDays: number | null;
  status: string;
}
