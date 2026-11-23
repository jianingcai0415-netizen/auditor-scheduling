package org.acme.employeescheduling.rest;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.acme.employeescheduling.dto.WorkflowDtos;
import org.acme.employeescheduling.dto.WorkflowDtos.AuditOrderRequest;
import org.acme.employeescheduling.dto.WorkflowDtos.AuditPhaseRequest;
import org.acme.employeescheduling.dto.WorkflowDtos.SkillRequirement;
import org.acme.employeescheduling.persistence.AuditOrderEntity;
import org.acme.employeescheduling.persistence.AuditOrderPhaseEntity;
import org.acme.employeescheduling.persistence.AuditPhaseSkillEntity;
import org.acme.employeescheduling.persistence.EmployeeEntity;
import org.acme.employeescheduling.persistence.LocationEntity;
import org.acme.employeescheduling.persistence.SchedulePlanAssignmentEntity;
import org.acme.employeescheduling.persistence.SchedulePlanEntity;
import org.acme.employeescheduling.persistence.ShiftEntity;
import org.acme.employeescheduling.persistence.ShiftSkillEntity;
import org.acme.employeescheduling.persistence.SkillEntity;

import io.quarkus.security.identity.SecurityIdentity;

import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/** Audit-order CRUD and deterministic materialization of phase headcount into solver shifts. */
@Path("/audit-orders")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed({"ADMIN", "CAPOSALA", "DISPATCHER", "APPROVER", "VIEWER"})
public class AuditOrderResource {
    /**
     * Order lifecycle + approval chain in one field:
     * DRAFT → SUBMITTED → APPROVED / REJECTED, plus CANCELLED / COMPLETED for closed orders.
     * Only APPROVED orders may be scheduled; the former ACTIVE value is migrated to APPROVED (V24).
     */
    private static final Set<String> ORDER_STATUSES = Set.of(
            "DRAFT", "SUBMITTED", "APPROVED", "REJECTED", "CANCELLED", "COMPLETED");
    private static final Set<String> PHASE_STATUSES = Set.of("PLANNED", "READY", "CANCELLED", "COMPLETED");
    /** 执行方式枚举：现场 / 远程 / 混合。前端下拉与 ImportWorkbookService 默认值都用这三个。 */
    private static final Set<String> DELIVERY_MODES = Set.of("ONSITE", "REMOTE", "HYBRID");
    /** Statuses in which the order body (including its status) may still be edited freely. */
    private static final Set<String> EDITABLE_ORDER_STATUSES = Set.of("DRAFT", "REJECTED");
    /** 已提交/已批准的订单仍允许被取消或标记完成，但不允许绕过审批改成其它状态。 */
    private static final Set<String> CLOSABLE_ORDER_STATUSES = Set.of("CANCELLED", "COMPLETED");

    @Inject AuditTrailService auditTrail;
    @Inject DispatchEventHub events;
    @Inject SecurityIdentity identity;
    @Inject AssignmentRiskService assignmentRiskService;

    @GET
    public List<Map<String, Object>> list(@QueryParam("structureId") int structureId) {
        return AuditOrderEntity.<AuditOrderEntity>find("structureId = ?1 order by updatedAt desc", structureId)
                .list().stream().map(this::orderDto).toList();
    }

    @GET
    @Path("/{id}")
    public Response get(@PathParam("id") int id, @QueryParam("structureId") int structureId) {
        AuditOrderEntity order = order(id, structureId);
        return order == null ? ApiErrors.notFound("AUDIT_ORDER_NOT_FOUND") : Response.ok(orderDto(order)).build();
    }

    @POST
    @RolesAllowed({"ADMIN", "CAPOSALA", "DISPATCHER"})
    @Transactional
    public Response create(@QueryParam("structureId") int structureId, AuditOrderRequest request) {
        Response validation = validateOrder(structureId, request, null);
        if (validation != null) return validation;
        AuditOrderEntity entity = new AuditOrderEntity();
        entity.structureId = structureId;
        applyOrder(entity, request, true);
        entity.createdAt = entity.updatedAt = WorkflowSupport.now();
        entity.persist();
        auditTrail.record(structureId, "AUDIT_ORDER_CREATED", "AUDIT_ORDER", entity.id,
                Map.of("orderCode", entity.orderCode));
        events.publish("audit-order", Map.of("structureId", structureId, "orderId", entity.id, "action", "created"));
        return Response.status(Response.Status.CREATED).entity(orderDto(entity)).build();
    }

    @PUT
    @Path("/{id}")
    @RolesAllowed({"ADMIN", "CAPOSALA", "DISPATCHER"})
    @Transactional
    public Response update(@PathParam("id") int id, @QueryParam("structureId") int structureId,
            AuditOrderRequest request) {
        AuditOrderEntity entity = order(id, structureId);
        if (entity == null) return ApiErrors.notFound("AUDIT_ORDER_NOT_FOUND");
        Response validation = validateOrder(structureId, request, id);
        if (validation != null) return validation;
        applyOrder(entity, request, EDITABLE_ORDER_STATUSES.contains(entity.status));
        entity.updatedAt = WorkflowSupport.now();
        auditTrail.record(structureId, "AUDIT_ORDER_UPDATED", "AUDIT_ORDER", entity.id,
                Map.of("orderCode", entity.orderCode));
        events.publish("audit-order", Map.of("structureId", structureId, "orderId", entity.id, "action", "updated"));
        return Response.ok(orderDto(entity)).build();
    }

    /**
     * @brief 提交审批：DRAFT / REJECTED → SUBMITTED。
     *
     * @details 审批单元是<b>订单</b>而不是排班方案：方案只是求解批次，
     * 承载"谁来做"；订单承载"要不要做"。审批上移后方案不再有审批环节。
     */
    @POST
    @Path("/{id}/submit")
    @RolesAllowed({"ADMIN", "CAPOSALA", "DISPATCHER"})
    @Transactional
    public Response submitOrder(@PathParam("id") int id, @QueryParam("structureId") int structureId) {
        return approvalTransition(id, structureId, Set.of("DRAFT", "REJECTED"), "SUBMITTED",
                "AUDIT_ORDER_SUBMITTED", null);
    }

    /** @brief 批准：SUBMITTED → APPROVED，此后该订单的班次才允许进入求解。 */
    @POST
    @Path("/{id}/approve")
    @RolesAllowed({"ADMIN", "APPROVER"})
    @Transactional
    public Response approveOrder(@PathParam("id") int id, @QueryParam("structureId") int structureId) {
        return approvalTransition(id, structureId, Set.of("SUBMITTED"), "APPROVED",
                "AUDIT_ORDER_APPROVED", null);
    }

    /** @brief 退回：SUBMITTED → REJECTED，退回原因随审计记录保留。 */
    @POST
    @Path("/{id}/reject")
    @RolesAllowed({"ADMIN", "APPROVER"})
    @Transactional
    public Response rejectOrder(@PathParam("id") int id, @QueryParam("structureId") int structureId,
            @QueryParam("reason") String reason) {
        return approvalTransition(id, structureId, Set.of("SUBMITTED"), "REJECTED",
                "AUDIT_ORDER_REJECTED", reason);
    }

    private Response approvalTransition(int id, int structureId, Set<String> from, String to,
            String auditEvent, String reason) {
        AuditOrderEntity order = order(id, structureId);
        if (order == null) return ApiErrors.notFound("AUDIT_ORDER_NOT_FOUND");
        // 非法跃迁一律 409：前端据此提示"当前状态不能这样做"，而不是静默改坏状态机。
        if (!from.contains(order.status)) return ApiErrors.conflict("AUDIT_ORDER_INVALID_TRANSITION");
        // 提交审批前必须是一张完整的「需求单」：审批人批的是"要不要做 + 要什么样的人"。
        // 空阶段 / 无资格 / 人数为 0 的订单即使批了也排不出班，只会把问题推到下游——
        // 更糟的是资格为空时候选筛选会整段跳过资格检查，谁都能被推荐。
        if ("SUBMITTED".equals(to)) {
            Response incomplete = validateReadyForSubmission(order);
            if (incomplete != null) return incomplete;
        }
        String now = WorkflowSupport.now();
        order.status = to;
        order.updatedAt = now;
        switch (to) {
            case "SUBMITTED" -> order.submittedAt = now;
            case "APPROVED" -> {
                order.approvedAt = now;
                order.approvedBy = actor();
                order.rejectReason = "";
            }
            case "REJECTED" -> {
                order.approvedAt = null;
                order.approvedBy = null;
                order.rejectReason = reason == null || reason.isBlank() ? "未填写退回原因" : reason.trim();
            }
            default -> { }
        }
        auditTrail.record(structureId, auditEvent, "AUDIT_ORDER", order.id,
                Map.of("orderCode", order.orderCode, "status", to));
        events.publish("audit-order", Map.of("structureId", structureId, "orderId", order.id, "action", "updated"));
        return Response.ok(orderDto(order)).build();
    }

    /**
     * @brief 提交审批的三道门禁。
     *
     * @details 只在 DRAFT/REJECTED → SUBMITTED 时调用，返回 null 表示可以提交。
     *          「就绪」阶段才要求资格：PLANNED/CANCELLED/COMPLETED 不参与候选筛选，
     *          强制它们填资格只会制造无意义的阻碍。人数校验则对所有阶段生效。
     */
    private Response validateReadyForSubmission(AuditOrderEntity order) {
        List<AuditOrderPhaseEntity> phases = AuditOrderPhaseEntity.<AuditOrderPhaseEntity>find(
                "auditOrderId = ?1 order by startTime, id", order.id).list();
        if (phases.isEmpty()) {
            return Response.status(Response.Status.CONFLICT)
                    .entity(Map.of("error", "AUDIT_ORDER_NO_PHASES")).build();
        }
        for (AuditOrderPhaseEntity phase : phases) {
            if (phase.requiredHeadcount < 1) {
                return Response.status(Response.Status.CONFLICT)
                        .entity(Map.of("error", "AUDIT_PHASE_HEADCOUNT_INVALID",
                                "phaseCode", phase.phaseCode, "phaseName", phase.phaseName)).build();
            }
            if (!"READY".equals(phase.status)) continue;
            if (AuditPhaseSkillEntity.count("phaseId", phase.id) == 0) {
                return Response.status(Response.Status.CONFLICT)
                        .entity(Map.of("error", "AUDIT_PHASE_SKILL_REQUIRED",
                                "phaseCode", phase.phaseCode, "phaseName", phase.phaseName)).build();
            }
        }
        return null;
    }

    private String actor() {
        return identity == null || identity.isAnonymous() ? "system" : identity.getPrincipal().getName();
    }

    @POST
    @Path("/{orderId}/phases")
    @RolesAllowed({"ADMIN", "CAPOSALA", "DISPATCHER"})
    @Transactional
    public Response createPhase(@PathParam("orderId") int orderId, @QueryParam("structureId") int structureId,
            AuditPhaseRequest request) {
        AuditOrderEntity order = order(orderId, structureId);
        if (order == null) return ApiErrors.notFound("AUDIT_ORDER_NOT_FOUND");
        Response validation = validatePhase(order, request, null);
        if (validation != null) return validation;
        AuditOrderPhaseEntity phase = new AuditOrderPhaseEntity();
        phase.auditOrderId = orderId;
        applyPhase(phase, request);
        phase.createdAt = phase.updatedAt = WorkflowSupport.now();
        phase.persistAndFlush();
        replaceRequirements(phase.id, request.requiredSkills);
        Response materialized = materialize(order, phase);
        if (materialized != null) return materialized;
        auditTrail.record(structureId, "AUDIT_PHASE_CREATED", "AUDIT_PHASE", phase.id,
                Map.of("orderId", orderId, "headcount", phase.requiredHeadcount));
        events.publish("audit-phase", Map.of("structureId", structureId, "phaseId", phase.id, "action", "created"));
        return Response.status(Response.Status.CREATED).entity(phaseDto(phase)).build();
    }

    @PUT
    @Path("/phases/{phaseId}")
    @RolesAllowed({"ADMIN", "CAPOSALA", "DISPATCHER"})
    @Transactional
    public Response updatePhase(@PathParam("phaseId") int phaseId, @QueryParam("structureId") int structureId,
            AuditPhaseRequest request) {
        AuditOrderPhaseEntity phase = AuditOrderPhaseEntity.findById(phaseId);
        if (phase == null) return ApiErrors.notFound("AUDIT_PHASE_NOT_FOUND");
        AuditOrderEntity order = order(phase.auditOrderId, structureId);
        if (order == null) return ApiErrors.notFound("AUDIT_PHASE_NOT_FOUND");
        Response validation = validatePhase(order, request, phaseId);
        if (validation != null) return validation;
        applyPhase(phase, request);
        phase.updatedAt = WorkflowSupport.now();
        replaceRequirements(phase.id, request.requiredSkills);
        Response materialized = materialize(order, phase);
        if (materialized != null) return materialized;
        auditTrail.record(structureId, "AUDIT_PHASE_UPDATED", "AUDIT_PHASE", phase.id,
                Map.of("orderId", order.id, "headcount", phase.requiredHeadcount));
        events.publish("audit-phase", Map.of("structureId", structureId, "phaseId", phase.id, "action", "updated"));
        return Response.ok(phaseDto(phase)).build();
    }

    private Response validateOrder(int structureId, AuditOrderRequest request, Integer id) {
        if (structureId <= 0 || request == null || WorkflowSupport.text(request.orderCode, 80).isBlank())
            return ApiErrors.badRequest("AUDIT_ORDER_CODE_REQUIRED");
        // 客户与项目名是派单和报表的最小识别信息；空着的话订单列表只剩一个编号，
        // 调度员无法判断这是谁的什么单子。导入路径不受此限（历史数据允许缺列）。
        if (WorkflowSupport.text(request.customerName, 160).isBlank())
            return ApiErrors.badRequest("AUDIT_ORDER_CUSTOMER_REQUIRED");
        if (WorkflowSupport.text(request.projectName, 160).isBlank())
            return ApiErrors.badRequest("AUDIT_ORDER_PROJECT_REQUIRED");
        String status = request.status == null ? "DRAFT" : request.status.toUpperCase();
        if (!ORDER_STATUSES.contains(status)) return ApiErrors.badRequest("AUDIT_ORDER_STATUS_INVALID");
        int priority = request.priority == null ? 3 : request.priority;
        if (priority < 1 || priority > 5) return ApiErrors.badRequest("AUDIT_ORDER_PRIORITY_INVALID");
        AuditOrderEntity duplicate = AuditOrderEntity.find("structureId = ?1 and orderCode = ?2", structureId,
                WorkflowSupport.text(request.orderCode, 80)).firstResult();
        return duplicate != null && !duplicate.id.equals(id) ? ApiErrors.conflict("AUDIT_ORDER_DUPLICATE") : null;
    }

    private Response validatePhase(AuditOrderEntity order, AuditPhaseRequest request, Integer phaseId) {
        if (request == null || WorkflowSupport.text(request.phaseCode, 80).isBlank()
                || WorkflowSupport.text(request.phaseName, 160).isBlank())
            return ApiErrors.badRequest("AUDIT_PHASE_FIELDS_REQUIRED");
        LocalDateTime start;
        LocalDateTime end;
        try {
            start = WorkflowSupport.parseDateTime(request.startTime);
            end = WorkflowSupport.parseDateTime(request.endTime);
        } catch (Exception ignored) {
            return ApiErrors.badRequest("AUDIT_PHASE_TIME_INVALID");
        }
        if (start == null || end == null || !end.isAfter(start)) return ApiErrors.badRequest("AUDIT_PHASE_TIME_INVALID");
        if (request.requiredHeadcount < 1 || request.requiredHeadcount > 100)
            return ApiErrors.badRequest("AUDIT_PHASE_HEADCOUNT_INVALID");
        LocationEntity location = LocationEntity.findById(request.locationId);
        if (location == null || location.structureId != order.structureId)
            return ApiErrors.badRequest("AUDIT_PHASE_LOCATION_INVALID");
        String status = request.status == null ? "PLANNED" : request.status.toUpperCase();
        if (!PHASE_STATUSES.contains(status)) return ApiErrors.badRequest("AUDIT_PHASE_STATUS_INVALID");
        // 执行方式是闭集枚举，前端下拉只认这三个值。写进别的值（历史上出现过中文「远程」）
        // 会让编辑弹窗的 select 匹配不上、显示成空白，所以在这里挡死。
        String deliveryMode = request.deliveryMode == null ? "ONSITE" : request.deliveryMode.trim().toUpperCase();
        if (!DELIVERY_MODES.contains(deliveryMode)) return ApiErrors.badRequest("AUDIT_PHASE_DELIVERY_MODE_INVALID");
        AuditOrderPhaseEntity duplicate = AuditOrderPhaseEntity.find(
                "auditOrderId = ?1 and phaseCode = ?2", order.id, WorkflowSupport.text(request.phaseCode, 80)).firstResult();
        if (duplicate != null && !duplicate.id.equals(phaseId)) return ApiErrors.conflict("AUDIT_PHASE_DUPLICATE");
        for (SkillRequirement requirement : safeRequirements(request.requiredSkills)) {
            SkillEntity skill = SkillEntity.findById(requirement.skillId);
            if (skill == null || !skill.structureId.equals(order.structureId) || requirement.minimumCount < 1)
                return ApiErrors.badRequest("AUDIT_PHASE_SKILL_INVALID");
        }
        return null;
    }

    /**
     * @brief 把请求写入订单。
     *
     * @param allowStatusChange 只有在 DRAFT / REJECTED 时才允许直接改状态；已提交或已批准的订单
     *        只能通过审批端点流转，否则「编辑」就成了绕过审批的后门。
     */
    private void applyOrder(AuditOrderEntity entity, AuditOrderRequest request, boolean allowStatusChange) {
        entity.orderCode = WorkflowSupport.text(request.orderCode, 80);
        entity.customerName = WorkflowSupport.text(request.customerName, 160);
        entity.projectName = WorkflowSupport.text(request.projectName, 160);
        String requestedStatus = request.status == null ? null : request.status.toUpperCase();
        if (allowStatusChange) {
            entity.status = requestedStatus == null ? "DRAFT" : requestedStatus;
        } else if (requestedStatus != null && CLOSABLE_ORDER_STATUSES.contains(requestedStatus)) {
            // 已提交/已批准的订单仍可取消或完成，但不能借此绕过审批改回其它状态。
            entity.status = requestedStatus;
        }
        entity.priority = request.priority == null ? 3 : request.priority;
        entity.ownerName = WorkflowSupport.text(request.ownerName, 120);
        entity.notes = WorkflowSupport.text(request.notes, 2000);
    }

    private void applyPhase(AuditOrderPhaseEntity entity, AuditPhaseRequest request) {
        entity.phaseCode = WorkflowSupport.text(request.phaseCode, 80);
        entity.phaseName = WorkflowSupport.text(request.phaseName, 160);
        entity.startTime = WorkflowSupport.dbDateTime(request.startTime);
        entity.endTime = WorkflowSupport.dbDateTime(request.endTime);
        entity.locationId = request.locationId;
        entity.requiredHeadcount = request.requiredHeadcount;
        entity.leadRequired = request.leadRequired;
        entity.continuityGroup = WorkflowSupport.text(request.continuityGroup, 80);
        entity.deliveryMode = request.deliveryMode == null ? "ONSITE" : request.deliveryMode.trim().toUpperCase();
        entity.status = request.status == null ? "PLANNED" : request.status.toUpperCase();
    }

    private void replaceRequirements(int phaseId, List<SkillRequirement> requirements) {
        AuditPhaseSkillEntity.delete("phaseId", phaseId);
        for (SkillRequirement request : safeRequirements(requirements)) {
            AuditPhaseSkillEntity entity = new AuditPhaseSkillEntity();
            entity.phaseId = phaseId;
            entity.skillId = request.skillId;
            entity.minimumCount = request.minimumCount;
            entity.leadOnly = request.leadOnly;
            entity.persist();
        }
    }

    /** Keeps exactly one solver shift per required person, preserving assigned/plan-linked rows. */
    private Response materialize(AuditOrderEntity order, AuditOrderPhaseEntity phase) {
        List<ShiftEntity> shifts = ShiftEntity.<ShiftEntity>find("auditOrderPhaseId = ?1 order by id", phase.id).list();
        if (shifts.size() > phase.requiredHeadcount) {
            List<ShiftEntity> extras = shifts.subList(phase.requiredHeadcount, shifts.size());
            boolean used = extras.stream().anyMatch(shift -> shift.employeeId != null
                    || SchedulePlanAssignmentEntity.count("shiftId", shift.id) > 0);
            if (used) return ApiErrors.conflict("AUDIT_PHASE_HEADCOUNT_IN_USE");
            extras.forEach(shift -> shift.delete());
            shifts = new ArrayList<>(shifts.subList(0, phase.requiredHeadcount));
        }
        while (shifts.size() < phase.requiredHeadcount) {
            ShiftEntity shift = new ShiftEntity();
            shift.auditOrderPhaseId = phase.id;
            shift.locationId = phase.locationId;
            shift.startTime = phase.startTime;
            shift.endTime = phase.endTime;
            shift.auditOrderCode = order.orderCode;
            shift.auditStage = phase.phaseName;
            shift.persist();
            shifts.add(shift);
        }
        List<AuditPhaseSkillEntity> requirements = AuditPhaseSkillEntity.<AuditPhaseSkillEntity>list("phaseId", phase.id);
        for (int index = 0; index < shifts.size(); index++) {
            ShiftEntity shift = shifts.get(index);
            shift.locationId = phase.locationId;
            shift.startTime = phase.startTime;
            shift.endTime = phase.endTime;
            shift.auditOrderCode = order.orderCode;
            shift.auditStage = phase.phaseName;
            shift.requiredRole = phase.leadRequired && index == 0 ? "LEAD_AUDITOR" : "AUDITOR";
            ShiftSkillEntity.delete("shiftId", shift.id);
            for (AuditPhaseSkillEntity requirement : requirements) {
                boolean appliesToSlot = requirement.leadOnly ? index == 0 : index < requirement.minimumCount;
                if (!appliesToSlot) continue;
                ShiftSkillEntity link = new ShiftSkillEntity();
                link.shiftId = shift.id;
                link.skillId = requirement.skillId;
                link.skillTypeId = 1;
                link.persist();
            }
        }
        return null;
    }

    private Map<String, Object> orderDto(AuditOrderEntity order) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("id", order.id); dto.put("structureId", order.structureId); dto.put("orderCode", order.orderCode);
        dto.put("customerName", order.customerName); dto.put("projectName", order.projectName);
        dto.put("status", order.status); dto.put("priority", order.priority); dto.put("ownerName", order.ownerName);
        dto.put("notes", order.notes); dto.put("version", order.version); dto.put("updatedAt", order.updatedAt);
        dto.put("submittedAt", order.submittedAt); dto.put("approvedAt", order.approvedAt);
        dto.put("approvedBy", order.approvedBy); dto.put("rejectReason", order.rejectReason);
        dto.put("phases", AuditOrderPhaseEntity.<AuditOrderPhaseEntity>find(
                "auditOrderId = ?1 order by startTime, id", order.id).list().stream().map(this::phaseDto).toList());
        return dto;
    }

    /**
     * @brief 已排人员的实时风险（订单详情页展开时按需调用）。
     * @details 只返回有风险的行——无风险的班次不出现在结果里，前端按「缺失即正常」处理。
     *          按需计算是因为整页 600 多条分配全算会明显拖慢列表；单张订单只有几条，代价可忽略。
     */
    @GET
    @Path("/{id}/assignment-risks")
    public Response assignmentRisks(@PathParam("id") int id, @QueryParam("structureId") int structureId) {
        AuditOrderEntity order = order(id, structureId);
        if (order == null) return ApiErrors.notFound("AUDIT_ORDER_NOT_FOUND");
        List<Map<String, Object>> risks = new ArrayList<>();
        for (AuditOrderPhaseEntity phase : AuditOrderPhaseEntity.<AuditOrderPhaseEntity>find(
                "auditOrderId = ?1 order by startTime, id", order.id).list()) {
            for (ShiftEntity shift : ShiftEntity.<ShiftEntity>find(
                    "auditOrderPhaseId = ?1 order by id", phase.id).list()) {
                if (shift.employeeId == null) continue;
                String risk = assignmentRiskService.riskOfShift(shift.id, shift.employeeId);
                if (risk.isBlank()) continue;
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("shiftId", shift.id);
                row.put("phaseId", phase.id);
                row.put("employeeId", shift.employeeId);
                row.put("risk", risk);
                risks.add(row);
            }
        }
        return Response.ok(Map.of("risks", risks)).build();
    }

    private Map<String, Object> phaseDto(AuditOrderPhaseEntity phase) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("id", phase.id); dto.put("auditOrderId", phase.auditOrderId); dto.put("phaseCode", phase.phaseCode);
        dto.put("phaseName", phase.phaseName); dto.put("startTime", phase.startTime); dto.put("endTime", phase.endTime);
        dto.put("locationId", phase.locationId); dto.put("requiredHeadcount", phase.requiredHeadcount);
        dto.put("leadRequired", phase.leadRequired); dto.put("continuityGroup", phase.continuityGroup);
        dto.put("deliveryMode", phase.deliveryMode); dto.put("status", phase.status); dto.put("version", phase.version);
        dto.put("requiredSkills", AuditPhaseSkillEntity.<AuditPhaseSkillEntity>list("phaseId", phase.id).stream()
                .map(requirement -> Map.of("skillId", requirement.skillId, "minimumCount", requirement.minimumCount,
                        "leadOnly", requirement.leadOnly)).toList());
        // 班次只查一次：shiftIds / 已分配人数 / 人员名单都由这一份数据派生，避免三次查库。
        List<ShiftEntity> shifts = ShiftEntity.list("auditOrderPhaseId = ?1 order by id", phase.id);
        dto.put("shiftIds", shifts.stream().map(s -> s.id).toList());
        List<ShiftEntity> assigned = shifts.stream().filter(s -> s.employeeId != null).toList();
        // 已分配名额数：前端据此在"改地点/改时间"时提示——这些安排会随阶段一起被覆盖，
        // 但系统不会自动重新核对人员是否还适合新地点/新时段。
        dto.put("assignedCount", assigned.size());
        // 排了谁：只给人数的话，界面上"已排 5/5"看不出是哪些人，调度员还得去排班表里翻。
        // 顺带把工号带上——项目里 `code + " " + 姓 + " " + 名` 才是全名，只有名字时不便核对。
        Map<Integer, EmployeeEntity> employeeById = assigned.isEmpty() ? Map.of()
                : EmployeeEntity.<EmployeeEntity>find("id in ?1",
                        assigned.stream().map(s -> s.employeeId).distinct().toList()).list().stream()
                        .collect(java.util.stream.Collectors.toMap((EmployeeEntity row) -> row.id, row -> row, (a, b) -> a));
        dto.put("assignees", assigned.stream().map(shift -> {
            EmployeeEntity employee = employeeById.get(shift.employeeId);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("shiftId", shift.id);
            item.put("employeeId", shift.employeeId);
            item.put("code", employee == null ? null : employee.code);
            item.put("name", employee == null ? null
                    : ((employee.firstName == null ? "" : employee.firstName) + " "
                            + (employee.lastName == null ? "" : employee.lastName)).trim());
            return item;
        }).toList());
        return dto;
    }

    /**
     * @brief 按订单号取最小状态信息（排班页「待分配」块要判断"能不能派工"）。
     *
     * @details 排班页只拿到 {@code auditOrderCode}，拿不到订单 id 与状态；为了一个提示去拉
     * 200+ 张订单的全部阶段太重，所以单独开这条只回三行的接口。
     */
    @GET
    @Path("/lookup")
    public Response lookup(@QueryParam("structureId") int structureId,
            @QueryParam("orderCode") String orderCode) {
        if (orderCode == null || orderCode.isBlank()) return ApiErrors.badRequest("AUDIT_ORDER_CODE_REQUIRED");
        AuditOrderEntity order = AuditOrderEntity
                .find("structureId = ?1 and orderCode = ?2", structureId, orderCode.trim()).firstResult();
        if (order == null) return ApiErrors.notFound("AUDIT_ORDER_NOT_FOUND");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", order.id);
        body.put("orderCode", order.orderCode);
        body.put("status", order.status);
        return Response.ok(body).build();
    }

    /**
     * @brief 把一个审核员直接派到订单下的某个名额（班次）。
     *
     * @details 「分配即生效」的最小写入单元：只改 {@code shifts.employee_id} 一个格子。
     * 存在的意义是补齐一个断点——排班页的「待分配」块和候选名单过去都只能看不能点，
     * 想派人必须绕到「方案 → 逐个锁定 → 定稿」，而大多数单子根本没有方案。
     *
     * <p>三道闸门：订单必须 APPROVED（与查候选同一条规则）、班次必须属于这张订单、
     * 硬约束由 {@link AssignmentRiskService#riskOfShift} 服务端重算（资格/等级/不可用/冲突/转场），
     * 前端传来的候选名单不作数。</p>
     *
     * <p>若这张单已有待定稿方案，方案里对应的那一行会同步成同一个人，
     * 否则方案会显示"还缺口"、定稿时又报 {@code SCHEDULE_PLAN_STALE}。</p>
     */
    @POST
    @Path("/{orderId}/assign-slot")
    @RolesAllowed({"ADMIN", "CAPOSALA", "DISPATCHER"})
    @Transactional
    public Response assignSlot(@PathParam("orderId") int orderId, @QueryParam("structureId") int structureId,
            WorkflowDtos.ShiftAssignRequest request) {
        AuditOrderEntity order = order(orderId, structureId);
        if (order == null) return ApiErrors.notFound("AUDIT_ORDER_NOT_FOUND");
        if (!"APPROVED".equals(order.status)) return ApiErrors.conflict("AUDIT_ORDER_NOT_APPROVED");
        if (request == null || request.shiftId == null || request.employeeId == null)
            return ApiErrors.badRequest("SHIFT_ASSIGNMENT_INVALID");
        ShiftEntity shift = ShiftEntity.findById(request.shiftId);
        if (shift == null) return ApiErrors.notFound("SHIFT_NOT_FOUND");
        AuditOrderPhaseEntity phase = shift.auditOrderPhaseId == null
                ? null : AuditOrderPhaseEntity.findById(shift.auditOrderPhaseId);
        if (phase == null || phase.auditOrderId != order.id)
            return ApiErrors.badRequest("SHIFT_ASSIGNMENT_INVALID");
        EmployeeEntity employee = EmployeeEntity.findById(request.employeeId);
        if (employee == null || employee.structureId != structureId || !employee.active)
            return ApiErrors.badRequest("SCHEDULE_EMPLOYEE_INVALID");
        String risk = assignmentRiskService.riskOfShift(shift.id, employee.id);
        if (!risk.isBlank()) return Response.status(Response.Status.CONFLICT)
                .entity(Map.of("error", "SCHEDULE_ASSIGNMENT_INVALID", "risk", risk)).build();

        Integer previous = shift.employeeId;
        shift.employeeId = employee.id;
        syncDraftPlanRows(shift, employee);
        auditTrail.record(structureId, "SHIFT_ASSIGNED_DIRECT", "SHIFT", shift.id,
                Map.of("orderCode", order.orderCode, "phaseCode", phase.phaseCode,
                        "employeeId", String.valueOf(employee.id),
                        "previousEmployeeId", String.valueOf(previous),
                        "reason", WorkflowSupport.text(request.reason, 200).isBlank()
                                ? "候选名单直接选择" : WorkflowSupport.text(request.reason, 200)));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("shiftId", shift.id);
        body.put("employeeId", employee.id);
        body.put("employeeName", ((employee.firstName == null ? "" : employee.firstName) + " "
                + (employee.lastName == null ? "" : employee.lastName)).trim());
        body.put("previousEmployeeId", previous);
        return Response.ok(body).build();
    }

    /**
     * @brief 把班次上的人同步到"同一班次的待定稿方案行"，让方案与实际一致。
     *
     * @details 顺序不能反：先 {@code flush} 让班次的 {@code @Version} 递增，再把新版本号写进方案行。
     * 已定稿的方案不动——它是历史快照，改它等于篡改记录。
     */
    private void syncDraftPlanRows(ShiftEntity shift, EmployeeEntity employee) {
        List<SchedulePlanAssignmentEntity> rows = SchedulePlanAssignmentEntity
                .<SchedulePlanAssignmentEntity>list("shiftId", shift.id);
        if (rows.isEmpty()) return;
        ShiftEntity.getEntityManager().flush();
        for (SchedulePlanAssignmentEntity row : rows) {
            SchedulePlanEntity plan = SchedulePlanEntity.findById(row.planId);
            if (plan == null || !"DRAFT".equals(plan.status)) continue;
            row.employeeId = shift.employeeId;
            row.shiftVersion = shift.version;
            row.employeeStatusUpdatedAt = employee.statusUpdatedAt == null ? "" : employee.statusUpdatedAt;
            plan.updatedAt = WorkflowSupport.now();
        }
    }

    private AuditOrderEntity order(int id, int structureId) {
        return AuditOrderEntity.find("id = ?1 and structureId = ?2", id, structureId).firstResult();
    }

    private static List<SkillRequirement> safeRequirements(List<SkillRequirement> requirements) {
        return requirements == null ? List.of() : requirements;
    }
}
