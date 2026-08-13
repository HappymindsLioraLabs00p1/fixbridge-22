package com.fixbridge.contractor;

import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The homeowner authorises this exact amount before anyone is dispatched, so the wrong rate is a
 * billing dispute rather than a display bug. These fix the precedence and, more importantly, what
 * happens when a contractor has published nothing.
 */
class VisitFeeCalculatorTest {

    private static final ZoneId NY = ZoneId.of("America/New_York");
    private final VisitFeeCalculator calculator = new VisitFeeCalculator();

    private Contractor rated() {
        Contractor c = new Contractor();
        c.setVisitFeeCents(9_900L);
        c.setEmergencyFeeCents(24_900L);
        c.setAfterHoursFeeCents(16_900L);
        c.setWeekendFeeCents(14_900L);
        return c;
    }

    /** Tuesday 10am — ordinary working hours. */
    private ZonedDateTime weekdayMorning() {
        return ZonedDateTime.of(2026, 8, 11, 10, 0, 0, 0, NY);
    }

    /** Sunday 10am. */
    private ZonedDateTime sundayMorning() {
        return ZonedDateTime.of(2026, 8, 16, 10, 0, 0, 0, NY);
    }

    /** Tuesday 9pm. */
    private ZonedDateTime weekdayNight() {
        return ZonedDateTime.of(2026, 8, 11, 21, 0, 0, 0, NY);
    }

    @Test
    void anOrdinaryWeekdayVisitIsTheStandardRate() {
        var fee = calculator.forJob(rated(), false, weekdayMorning());
        assertThat(fee.amountCents()).isEqualTo(9_900L);
        assertThat(fee.basis()).isEqualTo("STANDARD");
    }

    @Test
    void aWeekendVisitIsTheWeekendRate() {
        var fee = calculator.forJob(rated(), false, sundayMorning());
        assertThat(fee.amountCents()).isEqualTo(14_900L);
        assertThat(fee.basis()).isEqualTo("WEEKEND");
    }

    @Test
    void anEveningVisitIsTheAfterHoursRate() {
        var fee = calculator.forJob(rated(), false, weekdayNight());
        assertThat(fee.amountCents()).isEqualTo(16_900L);
        assertThat(fee.basis()).isEqualTo("AFTER_HOURS");
    }

    @Test
    void anEmergencyOutranksTheDayAndTheHour() {
        // One rate, not three stacked surcharges — a quote and a bill that disagree is the failure.
        assertThat(calculator.forJob(rated(), true, sundayMorning()).amountCents())
                .isEqualTo(24_900L);
        assertThat(calculator.forJob(rated(), true, weekdayNight()).basis())
                .isEqualTo("EMERGENCY");
    }

    @Test
    void anUnpublishedSurchargeFallsBackToTheStandardRate() {
        Contractor c = rated();
        c.setWeekendFeeCents(0L);   // does not charge extra at weekends
        var fee = calculator.forJob(c, false, sundayMorning());
        assertThat(fee.amountCents()).isEqualTo(9_900L);
        assertThat(fee.basis()).isEqualTo("STANDARD");
    }

    @Test
    void aContractorWithNoRateCardIsReportedRatherThanQuotedAtZero() {
        // Silently free would be worse than refusing to dispatch: the caller must be able to tell
        // "no charge" from "no rate published".
        var fee = calculator.forJob(new Contractor(), false, weekdayMorning());
        assertThat(fee.amountCents()).isZero();
        assertThat(fee.basis()).isEqualTo("NOT_SET");
        assertThat(fee.explanation()).contains("has not published");
    }

    @Test
    void everyFeeCarriesAReasonTheHomeownerCanRead() {
        for (var fee : new VisitFeeCalculator.VisitFee[]{
                calculator.forJob(rated(), false, weekdayMorning()),
                calculator.forJob(rated(), false, sundayMorning()),
                calculator.forJob(rated(), true, weekdayNight()),
        }) {
            assertThat(fee.explanation()).isNotBlank();
        }
    }

    @Test
    void sixInTheEveningIsAlreadyAfterHours() {
        // Boundary: the working day ends at 18:00, so 18:00 itself is out of hours.
        var fee = calculator.forJob(rated(), false,
                ZonedDateTime.of(2026, 8, 11, 18, 0, 0, 0, NY));
        assertThat(fee.basis()).isEqualTo("AFTER_HOURS");
    }

    @Test
    void eightInTheMorningIsAlreadyWorkingHours() {
        var fee = calculator.forJob(rated(), false,
                ZonedDateTime.of(2026, 8, 11, 8, 0, 0, 0, NY));
        assertThat(fee.basis()).isEqualTo("STANDARD");
    }
}
