package org.acme.employeescheduling.rest;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.acme.employeescheduling.persistence.CandidateWeightProfileEntity;
import org.acme.employeescheduling.persistence.StructureEntity;

import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/** Stores explainable candidate-scoring weights as immutable department-scoped versions. */
@Path("/candidate-weights")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed({"ADMIN", "CAPOSALA", "DISPATCHER", "APPROVER", "VIEWER"})
public class CandidateWeightResource {
    static final int DEFAULT_ABILITY = 40;
    static final int DEFAULT_LOCATION = 30;
    static final int DEFAULT_WORKLOAD = 15;
    static final int DEFAULT_CONTINUITY = 10;
    static final int DEFAULT_AGE = 5;

    @Inject SecurityIdentity identity;
    @Inject AuditTrailService auditTrail;
    @Inject DispatchEventHub events;

    @GET
    public Response current(@QueryParam("structureId") int structureId) {
        if (StructureEntity.findById(structureId) == null) return ApiErrors.notFound("STRUCTURE_NOT_FOUND");
        CandidateWeightProfileEntity profile = latest(structureId);
        return Response.ok(profile == null ? defaults() : dto(profile)).build();
    }

    @GET
    @Path("/history")
    public Response history(@QueryParam("structureId") int structureId) {
        if (StructureEntity.findById(structureId) == null) return ApiErrors.notFound("STRUCTURE_NOT_FOUND");
        List<CandidateWeightProfileEntity> rows = CandidateWeightProfileEntity.find(
                "structureId = ?1 order by profileVersion desc", structureId).list();
        return Response.ok(rows.stream().map(this::dto).toList()).build();
    }

    @PUT
    @RolesAllowed({"ADMIN", "CAPOSALA", "DISPATCHER"})
    @Transactional
    public Response update(@QueryParam("structureId") int structureId, WeightRequest request) {
        if (StructureEntity.findById(structureId) == null) return ApiErrors.notFound("STRUCTURE_NOT_FOUND");
        if (request == null || !request.valid()) return ApiErrors.badRequest("CANDIDATE_WEIGHT_INVALID");
        String reason = WorkflowSupport.text(request.changeReason, 500);
        if (reason.isBlank()) return ApiErrors.badRequest("CANDIDATE_WEIGHT_REASON_REQUIRED");
        CandidateWeightProfileEntity previous = latest(structureId);
        CandidateWeightProfileEntity profile = new CandidateWeightProfileEntity();
        profile.structureId = structureId;
        profile.profileVersion = previous == null ? 1 : previous.profileVersion + 1;
        profile.abilityWeight = request.ability;
        profile.locationWeight = request.location;
        profile.workloadWeight = request.workload;
        profile.continuityWeight = request.continuity;
        profile.ageWeight = request.age;
        profile.changeReason = reason;
        profile.createdBy = identity == null || identity.isAnonymous()
                ? "system" : identity.getPrincipal().getName();
        profile.createdAt = WorkflowSupport.now();
        profile.persistAndFlush();
        auditTrail.record(structureId, "CANDIDATE_WEIGHTS_UPDATED", "CANDIDATE_WEIGHT_PROFILE", profile.id,
                Map.of("version", profile.profileVersion, "reason", reason));
        events.publish("candidate-weights", Map.of("structureId", structureId,
                "version", profile.profileVersion, "action", "updated"));
        return Response.ok(dto(profile)).build();
    }

    static CandidateWeightProfileEntity latest(int structureId) {
        return CandidateWeightProfileEntity.find(
                "structureId = ?1 order by profileVersion desc", structureId).firstResult();
    }

    private Map<String, Object> defaults() {
        Map<String, Object> result = weights(0, DEFAULT_ABILITY, DEFAULT_LOCATION, DEFAULT_WORKLOAD,
                DEFAULT_CONTINUITY, DEFAULT_AGE);
        result.put("source", "DEFAULT"); result.put("changeReason", "系统初始规则");
        result.put("createdBy", "system"); result.put("createdAt", "");
        return result;
    }

    private Map<String, Object> dto(CandidateWeightProfileEntity profile) {
        Map<String, Object> result = weights(profile.profileVersion, profile.abilityWeight,
                profile.locationWeight, profile.workloadWeight, profile.continuityWeight, profile.ageWeight);
        result.put("source", "PROFILE"); result.put("changeReason", profile.changeReason);
        result.put("createdBy", profile.createdBy); result.put("createdAt", profile.createdAt);
        return result;
    }

    private static Map<String, Object> weights(int version, int ability, int location, int workload,
            int continuity, int age) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("version", version); result.put("ability", ability); result.put("location", location);
        result.put("workload", workload); result.put("continuity", continuity); result.put("age", age);
        return result;
    }

    public static class WeightRequest {
        public Integer ability;
        public Integer location;
        public Integer workload;
        public Integer continuity;
        public Integer age;
        public String changeReason;

        boolean valid() {
            if (ability == null || location == null || workload == null || continuity == null || age == null)
                return false;
            if (ability < 0 || location < 0 || workload < 0 || continuity < 0 || age < 0) return false;
            return ability + location + workload + continuity + age == 100;
        }
    }
}
