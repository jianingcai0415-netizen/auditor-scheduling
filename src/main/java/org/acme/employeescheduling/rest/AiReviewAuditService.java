package org.acme.employeescheduling.rest;

import java.util.Map;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

/** Records external AI calls in a short transaction, never around the network request itself. */
@ApplicationScoped
public class AiReviewAuditService {
    @Inject AuditTrailService auditTrail;

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void record(int structureId, String orderCode, String provider, String model,
            String responseId, int recommendationCount) {
        auditTrail.record(structureId, "AI_CANDIDATE_REVIEW_COMPLETED", "AUDIT_ORDER", orderCode,
                Map.of("provider", provider, "model", model, "responseId", responseId,
                        "recommendations", recommendationCount));
    }
}
