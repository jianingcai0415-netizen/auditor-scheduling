package org.acme.employeescheduling.rest;

import java.util.List;
import java.util.Map;

/** Replaceable boundary between deterministic scheduling and an external AI reviewer. */
public interface CandidateAiProvider {
    boolean configured();
    String providerName();
    String model();
    AiReview review(Map<String, Object> candidateContext);

    record AiReview(String responseId, String overallSummary,
            List<Recommendation> recommendations, List<String> warnings) { }

    record Recommendation(int shiftId, int employeeId, int aiRank, int confidence,
            String rationale, List<String> cautions) { }

    final class ProviderException extends RuntimeException {
        private final String code;
        private final int httpStatus;

        ProviderException(String code, int httpStatus) {
            super(code);
            this.code = code;
            this.httpStatus = httpStatus;
        }

        String code() { return code; }
        int httpStatus() { return httpStatus; }
    }
}
