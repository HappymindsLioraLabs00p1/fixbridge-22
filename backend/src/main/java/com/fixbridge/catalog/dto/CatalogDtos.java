package com.fixbridge.catalog.dto;

public final class CatalogDtos {

    private CatalogDtos() {}

    /**
     * One service on the browse screen.
     *
     * <p>{@code typicalLowCents} and {@code typicalHighCents} are null when too few jobs have been
     * priced to state a range honestly — the client shows "Get a quote" rather than a number
     * nobody can stand behind. {@code pricedJobs} is published alongside so the range can be
     * judged: an average over four jobs and an average over four hundred are different claims.
     *
     * <p>{@code averageRating} is null rather than zero for a trade with no reviews. New is not
     * the same as bad.
     */
    public record ServiceCard(
            String code,
            String name,
            Long typicalLowCents,
            Long typicalHighCents,
            int pricedJobs,
            Double averageRating,
            int reviewCount,
            int availableContractors,
            boolean bookable
    ) {}
}
