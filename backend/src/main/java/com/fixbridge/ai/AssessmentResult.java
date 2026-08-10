package com.fixbridge.ai;

import com.fixbridge.common.enums.AiUrgency;
import com.fixbridge.common.enums.Complexity;

import java.math.BigDecimal;
import java.util.List;

/**
 * Structured AI assessment — the ONLY thing the AI produces. It never sets price. Mirrors the schema
 * in spec §10.1.
 */
public record AssessmentResult(
        String category,
        String summary,
        AiUrgency urgency,
        BigDecimal confidence,
        String recommendedTrade,
        boolean professionalRequired,
        boolean safeDiyAllowed,
        List<String> immediateSafetySteps,
        List<String> visualFindings,
        BigDecimal estimatedLaborHoursMin,
        BigDecimal estimatedLaborHoursMax,
        Complexity complexity,
        List<String> questionsNeeded,
        String disclaimer
) {
    public static final String DEFAULT_DISCLAIMER =
            "AI-assisted assessment, not a professional diagnosis.";

    /**
     * Placeholder used when the assessment service is unavailable. The job is still recorded and
     * the assessment retried; until then nothing is claimed about the problem, and DIY is refused —
     * an unassessed job must never read as safe to attempt.
     */
    public static AssessmentResult pending() {
        return new AssessmentResult(
                null, "Assessment in progress — we'll update this shortly.", null, null, null,
                true, false, List.of(), List.of(), null, null, null, List.of(),
                "This issue has been recorded. An assessment will follow shortly.");
    }
}
