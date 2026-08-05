"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { useAuth } from "@/store/auth";
import type {
  AdminJob,
  AdminProposal,
  CheckoutView,
  ContractorInvitation,
  CustomerProposal,
  JobDetail,
  JobSummary,
  Property,
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
    mutationFn: (body: { summary: string; materialsUsed?: string }) =>
      api.post(`/api/contractor/jobs/${jobId}/completion`, body),
  });
}

// ---- Admin ----
export function useDispatchQueue() {
  return useQuery({
    queryKey: ["dispatch-queue"],
    queryFn: () => api.get<AdminJob[]>("/api/admin/dispatch-queue"),
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
