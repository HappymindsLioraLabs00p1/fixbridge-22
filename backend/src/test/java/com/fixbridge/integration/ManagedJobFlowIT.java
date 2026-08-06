package com.fixbridge.integration;

import com.fixbridge.common.enums.UserRole;
import com.fixbridge.job.Bid;
import com.fixbridge.job.BidRepository;
import com.fixbridge.job.ChangeOrder;
import com.fixbridge.job.ChangeOrderRepository;
import com.fixbridge.user.UserRoleEntity;
import com.fixbridge.user.UserRoleRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end managed-job loop against a real PostgreSQL (Testcontainers), in stub integration mode.
 * Auto-skips when Docker is unavailable so `mvn test` still passes on machines without Docker; runs
 * in CI where Docker is present.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIf("dockerAvailable")
class ManagedJobFlowIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    static boolean dockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable t) {
            return false;
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("fixbridge.ai.stub-mode", () -> "true");
    }

    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {};

    @Autowired TestRestTemplate rest;
    @Autowired UserRoleRepository roles;
    @Autowired BidRepository bids;
    @Autowired ChangeOrderRepository changeOrders;

    @Test
    @SuppressWarnings("unchecked")
    void fullManagedLoop_fromReportIssueToContractorPayout() {
        String rnd = UUID.randomUUID().toString().substring(0, 8);

        // Customer + contractor (stub Connect completes onboarding) + admin (role granted server-side).
        String custToken = (String) post("/api/auth/register",
                json("email", "c" + rnd + "@ex.com", "password", "password123", "role", "customer"), null).get("accessToken");

        String conToken = (String) post("/api/auth/register",
                json("email", "k" + rnd + "@ex.com", "password", "password123", "role", "contractor"), null).get("accessToken");
        Map<String, Object> contractor = post("/api/contractor/onboard", json("businessName", "Ace Plumbing"), conToken);
        assertThat(contractor.get("payoutsEnabled")).isEqualTo(true);
        String contractorId = (String) contractor.get("id");

        Map<String, Object> adminReg = post("/api/auth/register",
                json("email", "a" + rnd + "@ex.com", "password", "password123", "role", "customer"), null);
        String adminId = (String) ((Map<String, Object>) adminReg.get("user")).get("id");
        roles.save(new UserRoleEntity(UUID.fromString(adminId), UserRole.admin));
        String adminToken = (String) post("/api/auth/login",
                json("email", "a" + rnd + "@ex.com", "password", "password123"), null).get("accessToken");

        // Property + report issue → AI assessment + server-side estimate.
        String propertyId = (String) post("/api/properties",
                json("line1", "12 Maple St", "city", "Hicksville", "state", "NY", "postalCode", "11801"), custToken).get("id");

        Map<String, Object> job = post("/api/jobs",
                json("propertyId", propertyId, "title", "Kitchen leak", "description", "Active water leak under the sink"), custToken);
        String jobId = (String) job.get("id");
        assertThat(job.get("status")).isEqualTo("awaiting_service_payment");
        Map<String, Object> assessment = (Map<String, Object>) job.get("assessment");
        assertThat(assessment.get("category")).isEqualTo("plumbing");
        assertThat(assessment.get("safeDiyAllowed")).isEqualTo(false); // safety gating on a leak
        assertThat(((Map<String, Object>) job.get("estimate")).get("priceAvailable")).isEqualTo(true);

        // Dispatch fee → verified idempotent webhook advances the job.
        Map<String, Object> checkout = post("/api/jobs/" + jobId + "/dispatch-checkout", json("serviceType", "same_day_priority"), custToken);
        assertThat(((Number) checkout.get("amountCents")).longValue()).isEqualTo(22_900L);
        String session = (String) checkout.get("sessionId");
        assertThat(postText("/api/webhooks/stripe", stripeEvent("evt_d_" + rnd, "checkout.session.completed", session))).isEqualTo("ok");
        assertThat(postText("/api/webhooks/stripe", stripeEvent("evt_d_" + rnd, "checkout.session.completed", session))).isEqualTo("already processed");
        assertThat(get("/api/jobs/" + jobId, custToken).get("status")).isEqualTo("awaiting_contractor");

        // Admin invites → contractor bids (confidential net) → admin builds retail proposal.
        post("/api/admin/jobs/" + jobId + "/invite", json("contractorId", contractorId), adminToken);
        post("/api/contractor/jobs/" + jobId + "/bid",
                json("laborCents", 40000, "materialsCents", 8000, "equipmentCents", 0, "travelCents", 5000, "permitCents", 0, "disposalCents", 0), conToken);
        List<Bid> jobBids = bids.findByJobId(UUID.fromString(jobId));
        assertThat(jobBids).hasSize(1);
        assertThat(jobBids.get(0).getNetTotalCents()).isEqualTo(53_000);
        String bidId = jobBids.get(0).getId().toString();

        Map<String, Object> proposal = post("/api/admin/jobs/" + jobId + "/proposal",
                json("bidId", bidId, "scope", "Replace P-trap and supply line"), adminToken);
        long net = ((Number) proposal.get("contractorNetCents")).longValue();
        long retail = ((Number) proposal.get("retailTotalCents")).longValue();
        long margin = ((Number) proposal.get("marginCents")).longValue();
        assertThat(retail).isEqualTo(90_888L);
        assertThat(margin).isEqualTo(retail - net);
        String proposalId = (String) proposal.get("proposalId");

        // Customer approves + pays → completion → payout.
        String rSession = (String) post("/api/proposals/" + proposalId + "/approve", null, custToken).get("sessionId");
        postText("/api/webhooks/stripe", stripeEvent("evt_r_" + rnd, "checkout.session.completed", rSession));
        assertThat(get("/api/jobs/" + jobId, custToken).get("status")).isEqualTo("scheduled");

        // Change order mid-job: contractor documents extra work -> job pauses -> admin prices -> customer approves.
        post("/api/contractor/jobs/" + jobId + "/change-orders",
                json("description", "Corroded shutoff valve behind wall", "addedNetCents", 20000, "addedDays", 1), conToken);
        assertThat(get("/api/jobs/" + jobId, custToken).get("status")).isEqualTo("change_order_pending");
        ChangeOrder co = changeOrders.findByJobIdOrderByCreatedAtAsc(UUID.fromString(jobId)).get(0);
        Map<String, Object> published = post("/api/admin/change-orders/" + co.getId() + "/publish", null, adminToken);
        long coNet = ((Number) published.get("addedNetCents")).longValue();
        long coRetail = ((Number) published.get("addedRetailCents")).longValue();
        assertThat(coRetail).isGreaterThan(coNet);
        assertThat(((Number) published.get("marginCents")).longValue()).isEqualTo(coRetail - coNet);
        post("/api/change-orders/" + co.getId() + "/approve", null, custToken);
        assertThat(get("/api/jobs/" + jobId, custToken).get("status")).isEqualTo("work_started");

        post("/api/contractor/jobs/" + jobId + "/completion", json("summary", "Replaced trap; tested, no leaks."), conToken);
        assertThat(get("/api/jobs/" + jobId, custToken).get("status")).isEqualTo("work_completed");

        Map<String, Object> payout = post("/api/admin/jobs/" + jobId + "/payout", null, adminToken);
        assertThat(payout.get("status")).isEqualTo("paid");
        assertThat(((Number) payout.get("amountCents")).longValue()).isEqualTo(53_000L); // the NET, not retail
        assertThat(get("/api/jobs/" + jobId, custToken).get("status")).isEqualTo("paid_out");
    }

    // ---- helpers ----

    private Map<String, Object> post(String path, String body, String token) {
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers(token)), MAP).getBody();
    }

    private String postText(String path, String body) {
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers(null)), String.class).getBody();
    }

    private Map<String, Object> get(String path, String token) {
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers(token)), MAP).getBody();
    }

    private HttpHeaders headers(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) h.setBearerAuth(token);
        return h;
    }

    private static String json(Object... kv) {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < kv.length; i += 2) {
            if (i > 0) sb.append(',');
            sb.append('"').append(kv[i]).append("\":");
            Object v = kv[i + 1];
            if (v instanceof Number || v instanceof Boolean) sb.append(v);
            else sb.append('"').append(v).append('"');
        }
        return sb.append('}').toString();
    }

    private static String stripeEvent(String id, String type, String objectId) {
        return "{\"id\":\"" + id + "\",\"type\":\"" + type + "\",\"data\":{\"object\":{\"id\":\"" + objectId + "\"}}}";
    }
}
