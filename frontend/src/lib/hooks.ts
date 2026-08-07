"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api, apiFetch } from "@/lib/api";
import { useAuth } from "@/store/auth";
import type {
  AdminChangeOrder,
  AdminJob,
  AdminProposal,
  BidOption,
  BillingCheckout,
  ComplianceStatus,
  CompletionView,
  ContractorOption,
  CheckoutView,
  ContractorInvitation,
  CustomerChangeOrder,
  CustomerProposal,
  JobDetail,
  JobSummary,
  NotificationItem,
  PaymentView,
  PayoutHoldView,
  ReportOverview,
  PlanView,
  Property,
  SubscriptionView,
  TokenResponse,
  UserRole,
} from "@/lib/types";

// ---- Auth ----
export function useLogin() {
  const setAuth = useAuth((s) => s.setAuth);
  return useMutation({
    mutationFn: (body: { email: string; password: string }) =>
      api.post<TokenResponse>("/api/auth/login", body),
    onSuccess: setAuth,
  });
}

export function useRegister() {
  const setAuth = useAuth((s) => s.setAuth);
  return useMutation({
    mutationFn: (body: { email: string; password: string; fullName?: string; role: UserRole }) =>
      api.post<TokenResponse>("/api/auth/register", body),
    onSuccess: setAuth,
  });
}

// ---- Properties ----
export function useProperties() {
  return useQuery({ queryKey: ["properties"], queryFn: () => api.get<Property[]>("/api/properties") });
}

export function useCreateProperty() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: Record<string, string>) => api.post<Property>("/api/properties", body),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["properties"] }),
  });
}

// ---- Customer jobs ----
export function useMyJobs() {
  return useQuery({ queryKey: ["jobs"], queryFn: () => api.get<JobSummary[]>("/api/jobs") });
}

export function useJob(jobId: string) {
  return useQuery({ queryKey: ["job", jobId], queryFn: () => api.get<JobDetail>(`/api/jobs/${jobId}`) });
}

export function useReportIssue() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: {
      propertyId: string;
      title?: string;
      description?: string;
      mediaKeys?: string[];
      preferredTime?: string;
    }) => api.post<JobDetail>("/api/jobs", body),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["jobs"] }),
  });
}

export function useDispatchCheckout(jobId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (serviceType: string) =>
      api.post<CheckoutView>(`/api/jobs/${jobId}/dispatch-checkout`, { serviceType }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["job", jobId] }),
  });
}

export function useProposals(jobId: string) {
  return useQuery({
    queryKey: ["proposals", jobId],
    queryFn: () => api.get<CustomerProposal[]>(`/api/proposals?jobId=${jobId}`),
  });
}

export function useApproveProposal(jobId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (proposalId: string) =>
      api.post<CheckoutView>(`/api/proposals/${proposalId}/approve`),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["proposals", jobId] });
      qc.invalidateQueries({ queryKey: ["job", jobId] });
    },
  });
}

// ---- Contractor ----
export function useOnboardContractor() {
  return useMutation({
    mutationFn: (body: { businessName: string; contactPhone?: string }) =>
      api.post("/api/contractor/onboard", body),
  });
}

export function useInvitations() {
  return useQuery({
    queryKey: ["invitations"],
    queryFn: () => api.get<ContractorInvitation[]>("/api/contractor/invitations"),
  });
}

export function useSubmitBid(jobId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: Record<string, number | string | undefined>) =>
      api.post(`/api/contractor/jobs/${jobId}/bid`, body),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["invitations"] }),
  });
}

export function useSubmitCompletion(jobId: string) {
  return useMutation({
    mutationFn: (body: {
      summary: string;
      materialsUsed?: string;
      arrivedAt?: string;
      completedAt?: string;
      beforeKeys?: string[];
      afterKeys?: string[];
      invoiceUrl?: string;
      warrantyText?: string;
    }) => api.post(`/api/contractor/jobs/${jobId}/completion`, body),
  });
}

// ---- Completion proof & sign-off ----
export function useCompletion(jobId: string) {
  return useQuery({
    queryKey: ["completion", jobId],
    queryFn: () => api.get<CompletionView | null>(`/api/jobs/${jobId}/completion`),
  });
}

export function useConfirmCompletion(jobId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: () => api.post<CompletionView>(`/api/jobs/${jobId}/confirm-completion`),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["completion", jobId] });
      qc.invalidateQueries({ queryKey: ["job", jobId] });
    },
  });
}

// ---- Contractor compliance ----
export function useMyCompliance() {
  return useQuery({
    queryKey: ["compliance"],
    queryFn: () => api.get<ComplianceStatus>("/api/contractor/compliance"),
  });
}

export function useSubmitDocument() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: {
      kind: string;
      number?: string;
      jurisdiction?: string;
      expiresOn?: string;
      storageKey?: string;
    }) => api.post("/api/contractor/documents", body),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["compliance"] }),
  });
}

// ---- Admin: reporting ----
export function useReportOverview() {
  return useQuery({
    queryKey: ["admin-reports"],
    queryFn: () => api.get<ReportOverview>("/api/admin/reports/overview"),
  });
}

// ---- Admin: contractor compliance review ----
export function useContractorCompliance(contractorId: string | null) {
  return useQuery({
    queryKey: ["admin-compliance", contractorId],
    queryFn: () => api.get<ComplianceStatus>(`/api/admin/contractors/${contractorId}/compliance`),
    enabled: !!contractorId,
  });
}

export function useReviewDocument(contractorId: string | null) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (v: { documentId: string; approve: boolean; note?: string }) =>
      api.post(`/api/admin/documents/${v.documentId}/review`, { approve: v.approve, note: v.note }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["admin-compliance", contractorId] });
      qc.invalidateQueries({ queryKey: ["admin-contractors"] });
    },
  });
}

export function useSetSuspension(contractorId: string | null) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (v: { suspended: boolean; reason?: string }) =>
      api.post(`/api/admin/contractors/${contractorId}/suspension`, v),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["admin-compliance", contractorId] });
      qc.invalidateQueries({ queryKey: ["admin-contractors"] });
    },
  });
}

// ---- Admin ----
export function useDispatchQueue() {
  return useQuery({
    queryKey: ["dispatch-queue"],
    queryFn: () => api.get<AdminJob[]>("/api/admin/dispatch-queue"),
  });
}

/** Contractors the admin can invite (with eligibility). */
export function useContractorOptions() {
  return useQuery({
    queryKey: ["admin-contractors"],
    queryFn: () => api.get<ContractorOption[]>("/api/admin/contractors"),
  });
}

/** Bids submitted for a job, each previewing the retail price and margin. */
export function useBidOptions(jobId: string) {
  return useQuery({
    queryKey: ["admin-bids", jobId],
    queryFn: () => api.get<BidOption[]>(`/api/admin/jobs/${jobId}/bids`),
  });
}

export function useInviteContractor(jobId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (contractorId: string) =>
      api.post(`/api/admin/jobs/${jobId}/invite`, { contractorId }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["dispatch-queue"] }),
  });
}

export function useCreateProposal(jobId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: {
      bidId: string;
      scope?: string;
      depositCents?: number;
      timeline?: string;
      warranty?: string;
      exclusions?: string;
      terms?: string;
    }) => api.post<AdminProposal>(`/api/admin/jobs/${jobId}/proposal`, body),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["dispatch-queue"] }),
  });
}

export function useReleasePayout(jobId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: () => api.post(`/api/admin/jobs/${jobId}/payout`),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["dispatch-queue"] }),
  });
}

// ---- Admin money controls: refunds, disputes, payout holds ----
export function useJobPayments(jobId: string) {
  return useQuery({
    queryKey: ["admin-payments", jobId],
    queryFn: () => api.get<PaymentView[]>(`/api/admin/jobs/${jobId}/payments`),
  });
}

export function useRefundPayment(jobId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (v: { paymentId: string; amountCents: number; reason?: string }) =>
      api.post(`/api/admin/payments/${v.paymentId}/refund`, {
        amountCents: v.amountCents,
        reason: v.reason,
      }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["admin-payments", jobId] }),
  });
}

export function useSetPayoutHold(jobId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (reason: string | null) =>
      reason
        ? api.post<PayoutHoldView>(`/api/admin/jobs/${jobId}/payout-hold`, { reason })
        : apiFetch<PayoutHoldView>(`/api/admin/jobs/${jobId}/payout-hold`, { method: "DELETE" }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["dispatch-queue"] }),
  });
}

// ---- Change orders ----
export function useChangeOrders(jobId: string) {
  return useQuery({
    queryKey: ["change-orders", jobId],
    queryFn: () => api.get<CustomerChangeOrder[]>(`/api/change-orders?jobId=${jobId}`),
  });
}

export function useApproveChangeOrder(jobId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (changeOrderId: string) =>
      api.post<CustomerChangeOrder>(`/api/change-orders/${changeOrderId}/approve`),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["change-orders", jobId] });
      qc.invalidateQueries({ queryKey: ["job", jobId] });
    },
  });
}

export function useSubmitChangeOrder(jobId: string) {
  return useMutation({
    mutationFn: (body: { description: string; addedNetCents: number; addedDays?: number }) =>
      api.post(`/api/contractor/jobs/${jobId}/change-orders`, body),
  });
}

export function useAdminChangeOrders(jobId: string) {
  return useQuery({
    queryKey: ["admin-change-orders", jobId],
    queryFn: () => api.get<AdminChangeOrder[]>(`/api/admin/jobs/${jobId}/change-orders`),
  });
}

export function usePublishChangeOrder(jobId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (changeOrderId: string) =>
      api.post<AdminChangeOrder>(`/api/admin/change-orders/${changeOrderId}/publish`),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["admin-change-orders", jobId] }),
  });
}

// ---- Billing / subscriptions ----
export function usePlans() {
  return useQuery({ queryKey: ["plans"], queryFn: () => api.get<PlanView[]>("/api/billing/plans") });
}

export function useCurrentSubscription() {
  return useQuery({
    queryKey: ["subscription"],
    queryFn: () => api.get<SubscriptionView | null>("/api/billing/subscription"),
  });
}

export function useSubscribe() {
  return useMutation({
    mutationFn: (planCode: string) => api.post<BillingCheckout>("/api/billing/checkout", { planCode }),
  });
}

// ---- Notification feed ----
export function useNotifications() {
  return useQuery({
    queryKey: ["notifications"],
    queryFn: () => api.get<NotificationItem[]>("/api/notifications"),
    refetchInterval: 30_000,
  });
}
