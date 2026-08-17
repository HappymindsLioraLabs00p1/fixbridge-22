package com.fixbridge.job;

import com.fixbridge.auth.AuthUser;
import com.fixbridge.common.enums.JobStatus;
import com.fixbridge.common.enums.ProposalStatus;
import com.fixbridge.common.error.ApiException;
import com.fixbridge.contractor.Contractor;
import com.fixbridge.contractor.ContractorRepository;
import com.fixbridge.job.dto.ChangeOrderDtos;
import com.fixbridge.pricing.PricingEngine;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Change-order workflow (spec §12.3): contractor documents newly discovered work → admin applies retail
 * pricing rules → customer approves the retail change order → work resumes. The added net is confidential
 * to contractor + admin; the customer only ever sees the added retail.
 */
@Service
public class ChangeOrderService {

    private final ChangeOrderRepository changeOrders;
    private final JobService jobService;
    private final ContractorRepository contractors;
    private final PricingEngine pricingEngine;
    private final com.fixbridge.notification.NotificationService notifications;
    private final com.fixbridge.audit.AuditService audit;

    public ChangeOrderService(ChangeOrderRepository changeOrders, JobService jobService,
                              ContractorRepository contractors, PricingEngine pricingEngine,
                              com.fixbridge.notification.NotificationService notifications,
                              com.fixbridge.audit.AuditService audit) {
        this.changeOrders = changeOrders;
        this.jobService = jobService;
        this.contractors = contractors;
        this.pricingEngine = pricingEngine;
        this.notifications = notifications;
        this.audit = audit;
    }

    /** Contractor submits newly discovered work + confidential net cost; the job pauses for approval. */
    @Transactional
    public void submit(AuthUser user, UUID jobId, ChangeOrderDtos.SubmitRequest req) {
        Contractor contractor = contractors.findByOwnerUserId(user.id())
                .orElseThrow(() -> ApiException.forbidden());
        Job job = jobService.requireJob(jobId);
        if (!contractor.getId().equals(job.getAssignedContractorId())) {
            throw ApiException.forbidden();
        }
        // Extra work is work discovered while doing the job, so there has to be a job under way.
        // Without this, additional cost could be attached to one that was never started or is
        // already finished — after the customer had approved a price and stopped watching.
        if (job.getStatus() != JobStatus.work_started) {
            throw ApiException.conflict(
                    "Extra work can only be reported while the job is in progress");
        }
        // One at a time. A second unresolved change order gives the customer two prices to approve
        // for the same visit, and the completion gate would then be satisfied by approving either.
        boolean unresolved = changeOrders.findByJobIdOrderByCreatedAtAsc(jobId).stream()
                .anyMatch(co -> co.getStatus() == ProposalStatus.draft
                        || co.getStatus() == ProposalStatus.sent);
        if (unresolved) {
            throw ApiException.conflict(
                    "There is already extra work awaiting approval on this job");
        }

        ChangeOrder co = new ChangeOrder();
        co.setJobId(jobId);
        co.setDescription(req.description());
        co.setAddedNetCents(req.addedNetCents());
        co.setAddedRetailCents(0); // set by admin at publish time
        co.setAddedDays(req.addedDays());
        co.setStatus(ProposalStatus.draft);
        changeOrders.save(co);

        // Additional work must not continue before approval.
        jobService.transition(job, JobStatus.change_order_pending, user.id());
    }

    /** Admin applies retail pricing rules to the added net and sends the change order to the customer. */
    @Transactional
    public ChangeOrderDtos.AdminView publish(AuthUser admin, UUID changeOrderId) {
        ChangeOrder co = require(changeOrderId);
        long retail = pricingEngine.retailForNet(co.getAddedNetCents());
        co.setAddedRetailCents(retail);
        co.setStatus(ProposalStatus.sent);
        changeOrders.save(co);
        Job job = jobService.requireJob(co.getJobId());
        notifications.changeOrderSent(job.getCustomerId(), job.getId(), retail);
        audit.record(admin.id(), "change_order.publish", "change_order", co.getId(),
                java.util.Map.of("jobId", job.getId().toString(), "addedNetCents", co.getAddedNetCents(),
                        "addedRetailCents", retail));
        return adminView(co);
    }

    @Transactional
    public ChangeOrderDtos.CustomerView approve(AuthUser user, UUID changeOrderId) {
        ChangeOrder co = require(changeOrderId);
        Job job = jobService.requireJob(co.getJobId());
        if (!job.getCustomerId().equals(user.id())) {
            throw ApiException.forbidden();
        }
        if (co.getStatus() != ProposalStatus.sent) {
            throw ApiException.conflict("Change order is not open for approval");
        }
        co.setStatus(ProposalStatus.approved);
        changeOrders.save(co);
        // Work resumes once the customer approves. (The added retail is billed at final invoice.)
        jobService.transition(job, JobStatus.work_started, user.id());
        return customerView(co);
    }

    @Transactional(readOnly = true)
    public List<ChangeOrderDtos.CustomerView> listForCustomer(AuthUser user, UUID jobId) {
        Job job = jobService.requireJob(jobId);
        if (!job.getCustomerId().equals(user.id())) {
            throw ApiException.forbidden();
        }
        return changeOrders.findByJobIdOrderByCreatedAtAsc(jobId).stream().map(this::customerView).toList();
    }

    @Transactional(readOnly = true)
    public List<ChangeOrderDtos.AdminView> listForAdmin(UUID jobId) {
        return changeOrders.findByJobIdOrderByCreatedAtAsc(jobId).stream().map(this::adminView).toList();
    }

    private ChangeOrder require(UUID id) {
        return changeOrders.findById(id).orElseThrow(() -> ApiException.notFound("Change order"));
    }

    private ChangeOrderDtos.CustomerView customerView(ChangeOrder co) {
        return new ChangeOrderDtos.CustomerView(co.getId(), co.getJobId(), co.getDescription(),
                co.getAddedRetailCents(), co.getAddedDays(), co.getStatus());
    }

    private ChangeOrderDtos.AdminView adminView(ChangeOrder co) {
        return new ChangeOrderDtos.AdminView(co.getId(), co.getJobId(), co.getDescription(),
                co.getAddedNetCents(), co.getAddedRetailCents(),
                co.getAddedRetailCents() - co.getAddedNetCents(), co.getAddedDays(), co.getStatus());
    }
}
