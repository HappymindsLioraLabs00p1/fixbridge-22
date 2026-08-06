package com.fixbridge.job;

import com.fixbridge.auth.AuthUser;
import com.fixbridge.common.enums.ProposalStatus;
import com.fixbridge.common.enums.UserRole;
import com.fixbridge.contractor.ContractorRepository;
import com.fixbridge.job.dto.ChangeOrderDtos;
import com.fixbridge.notification.NotificationService;
import com.fixbridge.pricing.PricingEngine;
import com.fixbridge.pricing.PricingRule;
import com.fixbridge.pricing.PricingRuleRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChangeOrderServiceTest {

    private final AuthUser admin = new AuthUser(UUID.randomUUID(), "admin@ex.com", List.of(UserRole.admin));

    private ChangeOrderService service(ChangeOrderRepository repo) {
        PricingRuleRepository rules = mock(PricingRuleRepository.class);
        when(rules.findFirstByScopeAndActiveTrue("global")).thenReturn(Optional.of(new PricingRule()));
        JobService jobService = mock(JobService.class);
        Job job = new Job();
        job.setCustomerId(UUID.randomUUID());
        when(jobService.requireJob(any())).thenReturn(job);
        return new ChangeOrderService(repo, jobService, mock(ContractorRepository.class),
                new PricingEngine(rules), mock(NotificationService.class));
    }

    @Test
    void publish_appliesRetailPricingAndComputesMargin() {
        ChangeOrder co = new ChangeOrder();
        co.setId(UUID.randomUUID());
        co.setJobId(UUID.randomUUID());
        co.setDescription("Replace corroded shutoff valve found behind the wall");
        co.setAddedNetCents(53_000);

        ChangeOrderRepository repo = mock(ChangeOrderRepository.class);
        when(repo.findById(co.getId())).thenReturn(Optional.of(co));
        when(repo.save(any())).thenAnswer(i -> i.getArgument(0));

        ChangeOrderDtos.AdminView view = service(repo).publish(admin, co.getId());

        // Same margin formula as the main proposal: 53000 net -> 90888 retail.
        assertThat(view.addedRetailCents()).isEqualTo(90_888);
        assertThat(view.marginCents()).isEqualTo(90_888 - 53_000);
        assertThat(view.status()).isEqualTo(ProposalStatus.sent);
        // The persisted change order now carries the retail price.
        assertThat(co.getAddedRetailCents()).isEqualTo(90_888);
    }
}
