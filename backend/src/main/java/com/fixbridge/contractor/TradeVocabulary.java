package com.fixbridge.contractor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Translates the assessment's trade names into the catalogue's.
 *
 * <p>Two vocabularies describe the same work. An assessment names the person — {@code
 * licensed_plumber} — because that is what it is deciding: who should attend. The catalogue names the
 * category — {@code plumbing} — because that is what a customer browses. Both are right for their own
 * purpose, and neither should be bent to match the other.
 *
 * <p>Left untranslated they silently fail to meet. {@code findByTradeIgnoreCase("licensed_plumber")}
 * matches no contractor, matching decides the trade filter is unusable, and falls back to every
 * compliant contractor — so a plumbing job ranks the roofer first and nothing anywhere reports a
 * problem. That failure is invisible precisely because the fallback is reasonable.
 *
 * <p>The pairing mirrors {@code TRADE_BY_CATEGORY} in the AI service, which is where it is already
 * written down; this is the same table read in the other direction.
 */
@Component
public class TradeVocabulary {

    private static final Logger log = LoggerFactory.getLogger(TradeVocabulary.class);

    /** Assessment trade → catalogue trade code. Add a row to extend; nothing else needs to change. */
    private static final Map<String, String> CATALOGUE_TRADE = Map.ofEntries(
            Map.entry("licensed_plumber", "plumbing"),
            Map.entry("licensed_electrician", "electrical"),
            Map.entry("hvac_technician", "hvac"),
            Map.entry("appliance_engineer", "appliance"),
            Map.entry("roofer", "roofing"),
            Map.entry("carpenter", "carpentry"),
            Map.entry("painter", "painting"),
            Map.entry("handyman", "handyman"));

    /**
     * The catalogue code for a trade, whichever vocabulary named it.
     *
     * <p>An unrecognised trade is returned as given rather than guessed at. Gas work is the reason:
     * the assessment can recommend {@code licensed_gas_engineer} and the catalogue has no gas
     * category, so any mapping would be a guess — {@code plumbing} for a pipe, {@code hvac} for a
     * boiler — and guessing wrong sends the wrong trade to a job already flagged as dangerous.
     * Passing it through unchanged lets matching apply its existing fallback, which is to offer every
     * compliant contractor rather than nobody.
     */
    public String toCatalogueTrade(String assessmentTrade) {
        if (assessmentTrade == null || assessmentTrade.isBlank()) return null;
        String key = assessmentTrade.trim().toLowerCase().replace('-', '_').replace(' ', '_');

        String mapped = CATALOGUE_TRADE.get(key);
        if (mapped != null) return mapped;

        // Already a catalogue code (matching is called directly from the catalogue too).
        if (CATALOGUE_TRADE.containsValue(key)) return key;

        log.info("No catalogue trade for assessment trade '{}' — matching will fall back to every "
                + "compliant contractor", assessmentTrade);
        return key;
    }
}
