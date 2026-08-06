package com.fixbridge.billing;

import com.fixbridge.auth.AuthUser;
import com.fixbridge.billing.dto.BillingDtos;
import com.fixbridge.common.enums.UserRole;
import com.fixbridge.common.error.ApiException;
import com.fixbridge.payment.StripeClient;
import com.fixbridge.support.TestFixtures;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SubscriptionServiceTest {

    private final AuthUser user = new AuthUser(UUID.randomUUID(), "casey@ex.com", List.of(UserRole.customer));

    private SubscriptionService service(SubscriptionRepository repo, StripeClient stripe) {
        return new SubscriptionService(repo, stripe, TestFixtures.props());
    }

    @Test
    void createCheckout_createsIncompleteSubscriptionAndReturnsUrl() {
        SubscriptionRepository repo = mock(SubscriptionRepository.class);
        when(repo.save(any())).thenAnswer(i -> {
            Subscription s = i.getArgument(0);
            if (s.getId() == null) s.setId(UUID.randomUUID());
            return s;
        });
        StripeClient stripe = mock(StripeClient.class);
        when(stripe.createSubscriptionCheckout(any(), any(), any()))
                .thenReturn(new StripeClient.CheckoutSession("cs_sub_1", "https://checkout/sub"));

        BillingDtos.CheckoutView view = service(repo, stripe).createCheckout(user, "diy_plus");

        assertThat(view.sessionId()).isEqualTo("cs_sub_1");
        assertThat(view.url()).isEqualTo("https://checkout/sub");
    }

    @Test
    void createCheckout_rejectsUnknownPlan() {
        assertThatThrownBy(() -> service(mock(SubscriptionRepository.class), mock(StripeClient.class))
                .createCheckout(user, "does_not_exist"))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void activateBySession_marksSubscriptionActive() {
        Subscription sub = new Subscription();
        sub.setId(UUID.randomUUID());
        sub.setUserId(user.id());
        sub.setPlanCode("diy_plus");
        sub.setStatus("incomplete");
        sub.setCheckoutSession("cs_sub_1");

        SubscriptionRepository repo = mock(SubscriptionRepository.class);
        when(repo.findByCheckoutSession("cs_sub_1")).thenReturn(Optional.of(sub));
        when(repo.save(any())).thenAnswer(i -> i.getArgument(0));

        service(repo, mock(StripeClient.class)).activateBySession("cs_sub_1", "stripe_sub_123");

        assertThat(sub.getStatus()).isEqualTo("active");
        assertThat(sub.getStripeSubscriptionId()).isEqualTo("stripe_sub_123");
    }

    @Test
    void plans_catalogIsAvailableInStubMode() {
        List<BillingDtos.PlanView> plans = service(mock(SubscriptionRepository.class), mock(StripeClient.class)).plans();
        assertThat(plans).hasSize(4);
        assertThat(plans).allMatch(BillingDtos.PlanView::available); // stub mode → all subscribable
    }
}
