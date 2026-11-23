package org.acme.employeescheduling.rest;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class CandidateAiValidationTest {
    private static final int SHIFT_ID = 101;
    private static final int EMPLOYEE_ID = 202;

    @Test
    void acceptsOnlyRecommendationsFromTheDeterministicShortlist() {
        Map<String, Object> context = Map.of("phases", List.of(Map.of(
                "slots", List.of(Map.of(
                        "shiftId", SHIFT_ID,
                        "candidates", List.of(Map.of("employeeId", EMPLOYEE_ID)))))));

        CandidateAiProvider.AiReview valid = review(new CandidateAiProvider.Recommendation(
                SHIFT_ID, EMPLOYEE_ID, 1, 88, "规则评分和转场时间均较优", List.of()));
        CandidateAiProvider.AiReview inventedEmployee = review(new CandidateAiProvider.Recommendation(
                SHIFT_ID, 999, 1, 90, "不存在于候选范围", List.of()));
        CandidateAiProvider.AiReview invalidRank = review(new CandidateAiProvider.Recommendation(
                SHIFT_ID, EMPLOYEE_ID, 4, 90, "超出最多三个建议", List.of()));
        CandidateAiProvider.AiReview missingRecommendation = new CandidateAiProvider.AiReview(
                "response-4", "没有覆盖有候选人的名额", List.of(), List.of());

        assertTrue(SchedulePlanResource.validAiReview(valid, context));
        assertFalse(SchedulePlanResource.validAiReview(inventedEmployee, context));
        assertFalse(SchedulePlanResource.validAiReview(invalidRank, context));
        assertFalse(SchedulePlanResource.validAiReview(missingRecommendation, context));
    }

    private static CandidateAiProvider.AiReview review(CandidateAiProvider.Recommendation recommendation) {
        return new CandidateAiProvider.AiReview("response-1", "建议人工复核", List.of(recommendation), List.of());
    }
}
