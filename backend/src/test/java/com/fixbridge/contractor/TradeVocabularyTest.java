package com.fixbridge.contractor;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The assessment names the person; the catalogue names the category. Both are right for their own
 * purpose, and a job only reaches a contractor if the two vocabularies are reconciled.
 *
 * <p>The failure this prevents is silent rather than loud: an untranslated trade matches nobody,
 * matching decides its trade filter is unusable and falls back to every compliant contractor, and a
 * plumbing job quietly ranks the roofer first with nothing reporting a problem.
 */
class TradeVocabularyTest {

    private final TradeVocabulary vocabulary = new TradeVocabulary();

    @Test
    void anAssessmentTradeBecomesItsCatalogueTrade() {
        assertThat(vocabulary.toCatalogueTrade("licensed_plumber")).isEqualTo("plumbing");
        assertThat(vocabulary.toCatalogueTrade("licensed_electrician")).isEqualTo("electrical");
        assertThat(vocabulary.toCatalogueTrade("hvac_technician")).isEqualTo("hvac");
        assertThat(vocabulary.toCatalogueTrade("appliance_engineer")).isEqualTo("appliance");
        assertThat(vocabulary.toCatalogueTrade("roofer")).isEqualTo("roofing");
        assertThat(vocabulary.toCatalogueTrade("carpenter")).isEqualTo("carpentry");
    }

    @Test
    void aCatalogueTradePassesThroughUnchanged() {
        // Matching is called straight from the catalogue too, where the trade is already a code.
        assertThat(vocabulary.toCatalogueTrade("plumbing")).isEqualTo("plumbing");
        assertThat(vocabulary.toCatalogueTrade("roofing")).isEqualTo("roofing");
    }

    @Test
    void spellingIsNormalisedBeforeLookup() {
        assertThat(vocabulary.toCatalogueTrade("Licensed_Plumber")).isEqualTo("plumbing");
        assertThat(vocabulary.toCatalogueTrade("licensed-plumber")).isEqualTo("plumbing");
        assertThat(vocabulary.toCatalogueTrade("  licensed plumber  ")).isEqualTo("plumbing");
    }

    @Test
    void anUnknownTradeIsPassedThroughRatherThanGuessedAt() {
        // Gas is the real case: the assessment can recommend licensed_gas_engineer and the catalogue
        // has no gas category. Mapping it to plumbing or hvac would be a guess, and guessing wrong
        // sends the wrong trade to a job already flagged as dangerous. Matching's own fallback then
        // offers every compliant contractor, which is somebody rather than nobody.
        assertThat(vocabulary.toCatalogueTrade("licensed_gas_engineer")).isEqualTo("licensed_gas_engineer");
    }

    @Test
    void nothingIsReportedAsNothing() {
        assertThat(vocabulary.toCatalogueTrade(null)).isNull();
        assertThat(vocabulary.toCatalogueTrade("   ")).isNull();
    }
}
