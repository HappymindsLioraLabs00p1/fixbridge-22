package com.fixbridge.job;

import com.fixbridge.job.dto.JobDtos;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the customer is told about the professional coming to their home.
 *
 * <p>The masking is the part worth testing rather than trusting. It runs on the server precisely so
 * the full number never reaches a browser cache or a network log — a mask applied in the UI would
 * hide the number from the reader while still shipping it to the client. If this method ever starts
 * returning more than four digits, the privacy property is gone and nothing else would notice.
 */
class AssignedProfessionalTest {

    /** The masking is a private static detail of JobService; reached directly rather than by
     *  standing up the whole service and its ten repositories to assert a string. */
    private static String mask(String phone) throws Exception {
        Method m = JobService.class.getDeclaredMethod("maskPhone", String.class);
        m.setAccessible(true);
        return (String) m.invoke(null, phone);
    }

    @Test
    void onlyTheLastFourDigitsSurviveMasking() throws Exception {
        String masked = mask("+1 (555) 867-5309");

        assertThat(masked).endsWith("5309");
        // The rest of the number must not appear anywhere in the output.
        assertThat(masked).doesNotContain("555").doesNotContain("867").doesNotContain("+1");
    }

    @Test
    void formattingDoesNotChangeWhatIsRevealed() throws Exception {
        // Same number, three ways a contractor might have typed it in.
        assertThat(mask("+1 (555) 867-5309")).isEqualTo(mask("15558675309"));
        assertThat(mask("555.867.5309")).endsWith("5309");
    }

    @Test
    void aMissingNumberStaysMissing() throws Exception {
        assertThat(mask(null)).isNull();
    }

    @Test
    void aNumberTooShortToMaskIsWithheldEntirely() throws Exception {
        // Rather than returning something that reveals most of a malformed entry.
        assertThat(mask("12")).isNull();
        assertThat(mask("")).isNull();
    }

    // ---- The view itself ----

    @Test
    void theCustomerViewCarriesNothingConfidential() {
        // The contractor record holds their email, payout account, rate card, address and net
        // pricing. None of that belongs to the customer, and a record's component list is the
        // guarantee — a field added later shows up here.
        assertThat(JobDtos.AssignedProfessionalView.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .containsExactlyInAnyOrder(
                        "contractorId", "businessName", "maskedPhone", "verified", "rating", "reviewCount")
                .doesNotContain("contactEmail", "contactPhone", "stripeAccountId",
                        "visitFeeCents", "minimumLaborCents", "latitude", "longitude");
    }

    @Test
    void theJobDetailViewStillEndsWithTheNewFieldSoOlderClientsAreUnaffected() {
        // Appended last and nullable: a client that ignores it decodes exactly as before.
        var components = JobDtos.JobDetailView.class.getRecordComponents();
        assertThat(components[components.length - 1].getName()).isEqualTo("professional");
    }
}
