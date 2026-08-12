package com.fixbridge.catalog;

import com.fixbridge.catalog.dto.CatalogDtos;
import com.fixbridge.common.enums.ContractorStatus;
import com.fixbridge.contractor.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * The browsable service catalogue: what FixBridge does, what it typically costs, and who is
 * available to do it.
 *
 * <p>Prices are derived from jobs actually priced through the platform, never from a table of
 * aspirational numbers. A trade nobody has booked yet has no honest price to show, so it says so
 * and offers a quote instead — quoting a made-up range would be the one thing guaranteed to lose
 * a customer's trust the first time a real bill arrived.
 *
 * <p>The same applies to ratings and availability: a trade with no reviews reads as "new", and a
 * trade with no compliant contractor is shown as unavailable rather than bookable.
 */
@Service
public class CatalogService {

    /**
     * Below this many priced jobs, an average is an anecdote rather than a range. Three is low, but
     * a marketplace has to start somewhere, and the sample size is published alongside the price so
     * a customer can judge it for themselves.
     */
    private static final int MIN_JOBS_FOR_A_PRICE = 3;

    private static final double UNRATED_BASELINE_HIDDEN = -1;

    private final JdbcTemplate jdbc;
    private final ContractorRepository contractors;
    private final ContractorSkillRepository skills;
    private final ContractorReviewRepository reviews;
    private final ComplianceService compliance;

    public CatalogService(JdbcTemplate jdbc, ContractorRepository contractors,
                          ContractorSkillRepository skills, ContractorReviewRepository reviews,
                          ComplianceService compliance) {
        this.jdbc = jdbc;
        this.contractors = contractors;
        this.skills = skills;
        this.reviews = reviews;
        this.compliance = compliance;
    }

    @Transactional(readOnly = true)
    public List<CatalogDtos.ServiceCard> browse() {
        Map<String, long[]> priced = pricedRanges();
        Map<String, double[]> rated = ratingsByTrade();
        Map<String, Integer> available = availableByTrade();

        List<CatalogDtos.ServiceCard> cards = new ArrayList<>();
        for (Map<String, Object> row : jdbc.queryForList("SELECT code, name FROM trades ORDER BY name")) {
            String code = String.valueOf(row.get("code"));
            String name = String.valueOf(row.get("name"));
            String key = code.toLowerCase();

            long[] price = priced.get(key);
            boolean enoughHistory = price != null && price[2] >= MIN_JOBS_FOR_A_PRICE;

            double[] rating = rated.get(key);
            int contractorCount = available.getOrDefault(key, 0);

            cards.add(new CatalogDtos.ServiceCard(
                    code,
                    name,
                    // Null rather than a guess. The client renders "Get a quote" for these.
                    enoughHistory ? price[0] : null,
                    enoughHistory ? price[1] : null,
                    price == null ? 0 : (int) price[2],
                    rating == null ? null : Math.round((rating[0] / rating[1]) * 10) / 10.0,
                    rating == null ? 0 : (int) rating[1],
                    contractorCount,
                    contractorCount > 0));
        }
        return cards;
    }

    /** Low, high and sample size per category, from jobs this platform actually priced. */
    private Map<String, long[]> pricedRanges() {
        Map<String, long[]> out = new HashMap<>();
        String sql = """
                SELECT lower(ai_category) AS category,
                       round(avg(customer_retail_low))  AS lo,
                       round(avg(customer_retail_high)) AS hi,
                       count(*)                         AS jobs
                FROM job_pricing
                WHERE customer_retail_low IS NOT NULL AND ai_category IS NOT NULL
                GROUP BY lower(ai_category)
                """;
        for (Map<String, Object> r : jdbc.queryForList(sql)) {
            out.put(String.valueOf(r.get("category")), new long[]{
                    ((Number) r.get("lo")).longValue(),
                    ((Number) r.get("hi")).longValue(),
                    ((Number) r.get("jobs")).longValue()});
        }
        return out;
    }

    /** Rating sum and count per trade, via the contractors who declare it. */
    private Map<String, double[]> ratingsByTrade() {
        Map<UUID, String> tradeOf = new HashMap<>();
        for (ContractorSkill s : skills.findAll()) {
            tradeOf.put(s.getContractorId(), s.getTrade().toLowerCase());
        }
        Map<String, double[]> out = new HashMap<>();
        for (ContractorReview r : reviews.findAll()) {
            String trade = tradeOf.get(r.getContractorId());
            if (trade == null) continue;
            double[] agg = out.computeIfAbsent(trade, k -> new double[2]);
            agg[0] += r.getRating();
            agg[1] += 1;
        }
        return out;
    }

    /** Contractors who could actually be sent: approved, compliant, payouts enabled. */
    private Map<String, Integer> availableByTrade() {
        Set<UUID> bookable = new HashSet<>();
        for (Contractor c : contractors.findAll()) {
            if (c.getStatus() == ContractorStatus.approved
                    && c.isPayoutsEnabled()
                    && compliance.isCompliant(c.getId())) {
                bookable.add(c.getId());
            }
        }
        Map<String, Integer> out = new HashMap<>();
        for (ContractorSkill s : skills.findAll()) {
            if (bookable.contains(s.getContractorId())) {
                out.merge(s.getTrade().toLowerCase(), 1, Integer::sum);
            }
        }
        return out;
    }
}
