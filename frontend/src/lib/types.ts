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

export interface PaymentView {
  id: string;
  type: string;
  status: string;
  amountCents: number;
  refundedCents: number;
  refundableCents: number;
  disputed: boolean;
  createdAt: string;
}

export interface PayoutHoldView {
  jobId: string;
  held: boolean;
  reason: string | null;
}

export interface CompletionView {
  id: string;
  summary: string | null;
  materialsUsed: string | null;
  arrivedAt: string | null;
  completedAt: string | null;
  beforePhotoUrls: string[];
  afterPhotoUrls: string[];
  invoiceUrl: string | null;
  warrantyText: string | null;
  approved: boolean;
  approvedAt: string | null;
}

export interface ContractorPerformance {
  contractorId: string;
  businessName: string;
  status: string;
  bidsSubmitted: number;
  jobsPaidOut: number;
  totalEarnedCents: number;
}

export interface ReportOverview {
  collectedCents: number;
  refundedCents: number;
  netRevenueCents: number;
  contractorPayoutsCents: number;
  grossProfitCents: number;
  grossMarginPercent: number;
  jobsReported: number;
  jobsCompleted: number;
  conversionPercent: number;
  funnel: Record<string, number>;
  contractors: ContractorPerformance[];
}

export interface ComplianceDocument {
  id: string;
  kind: string;
  jurisdiction: string | null;
  number: string | null;
  status: string;
  expiresOn: string | null;
  daysUntilExpiry: number | null;
  fileUrl: string | null;
}

export interface ComplianceStatus {
  compliant: boolean;
  missingOrUnverified: string[];
  expired: string[];
  documents: ComplianceDocument[];
}

export interface ContractorOption {
  id: string;
  businessName: string;
  status: string;
  eligible: boolean;
  ineligibleReason: string | null;
}

export interface BidOption {
  bidId: string;
  contractorId: string;
  contractorName: string;
  netTotalCents: number;
  previewRetailCents: number;
  previewMarginCents: number;
  durationDays: number | null;
  submittedAt: string;
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

// ---- Guided repair assistant ----
export type ConversationStatus =
  | "NEED_MORE_INFORMATION" | "NEED_IMAGE" | "REPAIR_PLAN_READY"
  | "PROFESSIONAL_REQUIRED" | "EMERGENCY";

export type SafetyLevel =
  | "SAFE_DIY" | "PROFESSIONAL_REQUIRED" | "EMERGENCY" | "INSUFFICIENT_INFORMATION";

export type StepState = "pending" | "in_progress" | "completed" | "verified" | "failed";

export type VerificationResult =
  | "STEP_COMPLETED" | "STEP_NOT_COMPLETED" | "UNCERTAIN" | "ESCALATE";

export interface RepairStepView {
  id: string;
  number: number;
  instruction: string;
  why: string | null;
  tools: string[];
  warnings: string[];
  expectedResult: string | null;
  requiresImageVerification: boolean;
  state: StepState;
}

export interface RepairPlanView {
  id: string;
  problem: string;
  estimatedMinutes: number | null;
  stopConditions: string[];
  steps: RepairStepView[];
}

export interface ConversationView {
  id: string;
  status: ConversationStatus;
  safetyLevel: SafetyLevel | null;
  category: string | null;
  problem: string | null;
  message: string;
  quickReplies: string[];
  requiresImage: boolean;
  plan: RepairPlanView | null;
}

export interface VerificationView {
  stepId: string;
  stepNumber: number;
  result: VerificationResult;
  confidence: number;
  reason: string;
  nextAction: string;
}

export interface ContractorMatch {
  contractorId: string;
  businessName: string;
  trade: string;
  completedJobs: number;
  /** Null when the contractor has no reviews yet — not the same as a zero rating. */
  rating: number | null;
  reviewCount: number;
  /** Null when either side has no known location. */
  distanceMiles: number | null;
  minTripChargeCents: number | null;
  travelRadiusMiles: number | null;
  score: number;
  availability: "AVAILABLE" | "OUT_OF_RANGE";
}

export interface MatchResult {
  requiredTrade: string;
  matches: ContractorMatch[];
  /** Populated only when nothing matched. */
  reason: string | null;
  /** False when no contractor declared the trade, so results are broader than asked for. */
  tradeFilterApplied: boolean;
}

export interface ReviewView {
  id: string;
  contractorId: string;
  jobId: string;
  rating: number;
  comment: string | null;
  createdAt: string;
}
