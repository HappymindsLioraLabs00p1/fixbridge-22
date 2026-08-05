package com.fixbridge.ai;

import java.util.List;

/**
 * Abstraction over AI providers (OpenAI Responses API, Claude). Implementations must return a
 * schema-valid {@link AssessmentResult}; the provider and model are configuration. A stub
 * implementation is used during the frontend-first phase.
 */
public interface AiAssessmentClient {

    AssessmentResult assess(String description, List<String> mediaKeys);

    /** Provider label stored on the persisted assessment for audit. */
    String provider();

    /** Model name (from configuration) stored on the persisted assessment for audit. */
    String model();
}
