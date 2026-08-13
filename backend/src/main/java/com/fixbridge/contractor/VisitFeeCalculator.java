package com.fixbridge.contractor;

import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * Which visit fee applies, and why.
 *
 * <p>The homeowner is shown this amount before dispatch and authorises exactly it, so the reason is
 * returned alongside the number: "£120 — weekend rate" can be argued with, £120 on its own cannot.
 *
 * <p>Each rate is a total, not an increment. An emergency call-out on a Sunday night is charged at
 * one rate rather than three stacked surcharges — stacking is how a quote and a bill end up
 * disagreeing.
 *
 * <p>This is fee B only. The FixBridge coordination fee and the repair estimate are separate
 * amounts decided elsewhere; conflating them is what makes a customer feel overcharged even when
 * every individual figure was fair.
 */
@Component
public class VisitFeeCalculator {

    /** Work outside these hours is charged at the after-hours rate. */
    private static final LocalTime WORKDAY_START = LocalTime.of(8, 0);
    private static final LocalTime WORKDAY_END = LocalTime.of(18, 0);

    /** Quoted in the property's local time, not the server's. */
    private static final ZoneId ZONE = ZoneId.of("America/New_York");

    /** The fee, and the reason a homeowner can read. */
    public record VisitFee(long amountCents, String basis, String explanation) {}

    public VisitFee forJob(Contractor contractor, boolean emergency) {
        return forJob(contractor, emergency, ZonedDateTime.now(ZONE));
    }

    /** Time is a parameter so this can be tested without waiting for Sunday. */
    VisitFee forJob(Contractor contractor, boolean emergency, ZonedDateTime when) {
        // Most urgent first: an emergency is an emergency whatever the day.
        if (emergency && positive(contractor.getEmergencyFeeCents())) {
            return new VisitFee(contractor.getEmergencyFeeCents(), "EMERGENCY",
                    "Emergency call-out rate");
        }

        DayOfWeek day = when.getDayOfWeek();
        boolean weekend = day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY;
        if (weekend && positive(contractor.getWeekendFeeCents())) {
            return new VisitFee(contractor.getWeekendFeeCents(), "WEEKEND", "Weekend rate");
        }

        LocalTime time = when.toLocalTime();
        boolean afterHours = time.isBefore(WORKDAY_START) || !time.isBefore(WORKDAY_END);
        if (afterHours && positive(contractor.getAfterHoursFeeCents())) {
            return new VisitFee(contractor.getAfterHoursFeeCents(), "AFTER_HOURS",
                    "Outside working hours (8am–6pm)");
        }

        if (positive(contractor.getVisitFeeCents())) {
            return new VisitFee(contractor.getVisitFeeCents(), "STANDARD", "Standard visit fee");
        }

        // A contractor who has published no rate must not silently become free, nor be assigned a
        // number nobody agreed to. Zero with a stated basis lets the caller refuse to dispatch.
        return new VisitFee(0L, "NOT_SET", "This contractor has not published a visit fee");
    }

    private static boolean positive(Long cents) {
        return cents != null && cents > 0;
    }
}
