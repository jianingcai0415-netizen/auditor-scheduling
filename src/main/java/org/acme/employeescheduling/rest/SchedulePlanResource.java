package org.acme.employeescheduling.rest;

import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.acme.employeescheduling.dto.WorkflowDtos.PlanAssignmentUpdate;
import org.acme.employeescheduling.dto.WorkflowDtos.SchedulePlanRequest;
import org.acme.employeescheduling.dto.WorkflowDtos.TransitionRequest;
import org.acme.employeescheduling.persistence.AuditOrderEntity;
import org.acme.employeescheduling.persistence.AuditOrderPhaseEntity;
import org.acme.employeescheduling.persistence.AuditPhaseSkillEntity;
import org.acme.employeescheduling.persistence.CandidateWeightProfileEntity;
import org.acme.employeescheduling.persistence.EmployeeDateEntity;
import org.acme.employeescheduling.persistence.EmployeeEntity;
import org.acme.employeescheduling.persistence.EmployeeSkillEntity;
import org.acme.employeescheduling.persistence.LocationEntity;
import org.acme.employeescheduling.persistence.SchedulePlanAssignmentEntity;
import org.acme.employeescheduling.persistence.SchedulePlanEntity;
import org.acme.employeescheduling.persistence.ShiftEntity;
import org.acme.employeescheduling.persistence.ShiftSkillEntity;
import org.acme.employeescheduling.persistence.SkillEntity;
import org.acme.employeescheduling.utils.TravelMatrix;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/** Versioned draft/review/approval/publication workflow for schedule snapshots. */
@Path("/schedule-plans")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed({"ADMIN", "CAPOSALA", "DISPATCHER", "APPROVER", "VIEWER"})
public class SchedulePlanResource {
    private static final Set<String> EDITABLE = Set.of("DRAFT");

    @Inject SecurityIdentity identity;
    @Inject AuditTrailService auditTrail;
    @Inject DispatchEventHub events;
    @Inject DatabaseBackupService backupService;
    @Inject CandidateAiProvider aiProvider;
    @Inject AiReviewAuditService aiReviewAudit;

    /**
     * @brief 任务结束后「仍认为人在任务所在地」的时长，超过即视为已返回常驻地。
     *
     * @details 业务规则（2026-10-21 用户确定）：审核员完成一个任务后，两天内按"还在当地"计算
     *          出发地与转场；超过两天按常驻地计算。硬约束与位置打分共用这一条规则，
     *          避免"能不能排"和"谁更近"用两套位置各说各话。
     *          做成配置项是为了不重建就能调整（比如改成 24 或 72 小时）。
     */
    @ConfigProperty(name = "app.dispatch.return-to-base-hours", defaultValue = "48")
    int returnToBaseHours;

    /**
     * @brief 位置分扣到 0 所需要的转场分钟数（位置分 = 100 − min(基准, 转场) × 100/基准）。
     *
     * @details 原来的基准是写死的 240 分钟（4 小时），后果是国内跨城动辄 1800~2800 分钟，
     *          全部越过 240 直接扣满 → 所有外地候选位置分都是 0，30% 的权重退化成
     *          「同城/不同城」二值开关（2026-10-27 复核实测：乌鲁木齐场景 45/45 人为 0）。
     *          默认值取 2880（48 小时，与返程空窗期同一把尺子），覆盖实测最远 2777 分钟，
     *          让 2000~2800 分钟的远距离差异仍能体现在分数上。按客户口径可随时调小或调大。
     */
    @ConfigProperty(name = "app.dispatch.location-score-baseline-minutes", defaultValue = "2880")
    int locationScoreBaselineMinutes;

    /** 风险判定与订单详情共用一份实现，避免两处口径漂移。 */
    @Inject
    AssignmentRiskService assignmentRiskService;

    @GET
    public List<Map<String, Object>> list(@QueryParam("structureId") int structureId) {
        return SchedulePlanEntity.<SchedulePlanEntity>find("structureId = ?1 order by updatedAt desc", structureId)
                .list().stream().map(plan -> planDto(plan, false)).toList();
    }

    @GET
    @Path("/{id}")
    public Response get(@PathParam("id") int id, @QueryParam("structureId") int structureId) {
        SchedulePlanEntity plan = plan(id, structureId);
        return plan == null ? ApiErrors.notFound("SCHEDULE_PLAN_NOT_FOUND") : Response.ok(planDto(plan, true)).build();
    }

    @GET
    @Path("/ai-status")
    public Map<String, Object> aiStatus() {
        return Map.of("configured", aiProvider.configured(), "provider", aiProvider.providerName(),
                "model", aiProvider.model(), "mode", "ADVISORY_ONLY");
    }

    /** Exports the reviewed snapshot without exposing employee contact or certificate details. */
    @GET
    @Path("/{id}/export.xlsx")
    @Produces("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    @RolesAllowed({"ADMIN", "CAPOSALA", "DISPATCHER", "APPROVER"})
    @Transactional
    public Response exportExcel(@PathParam("id") int id, @QueryParam("structureId") int structureId) {
        SchedulePlanEntity plan = plan(id, structureId);
        if (plan == null) return ApiErrors.notFound("SCHEDULE_PLAN_NOT_FOUND");
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("排程分配");
            sheet.createFreezePane(0, 1);
            String[] headers = {"方案", "版本", "状态", "订单", "阶段", "开始时间", "结束时间", "地点ID",
                    "名额", "审核员编号", "审核员姓名", "角色", "锁定", "风险", "调整原因"};
            CellStyle headerStyle = workbook.createCellStyle();
            headerStyle.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            var font = workbook.createFont(); font.setBold(true); font.setColor(IndexedColors.WHITE.getIndex());
            headerStyle.setFont(font);
            var header = sheet.createRow(0);
            for (int column = 0; column < headers.length; column++) {
                header.createCell(column).setCellValue(headers[column]); header.getCell(column).setCellStyle(headerStyle);
                sheet.setColumnWidth(column, Math.min(32, Math.max(12, headers[column].length() + 6)) * 256);
            }
            int rowIndex = 1;
            for (SchedulePlanAssignmentEntity assignment : assignments(plan.id)) {
                ShiftEntity shift = ShiftEntity.findById(assignment.shiftId);
                EmployeeEntity employee = assignment.employeeId == null ? null : EmployeeEntity.findById(assignment.employeeId);
                var row = sheet.createRow(rowIndex++);
                Object[] values = {plan.name, plan.revision, plan.status,
                        shift == null ? "" : shift.auditOrderCode, shift == null ? "" : shift.auditStage,
                        shift == null ? "" : shift.startTime, shift == null ? "" : shift.endTime,
                        shift == null ? "" : shift.locationId, assignment.slotIndex,
                        employee == null ? "" : employee.code,
                        employee == null ? "" : employee.firstName + " " + employee.lastName,
                        assignment.assignmentRole, assignment.locked ? "TRUE" : "FALSE",
                        assignment.riskCode, assignment.changeReason};
                for (int column = 0; column < values.length; column++) row.createCell(column).setCellValue(String.valueOf(values[column]));
            }
            sheet.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(0, Math.max(0, rowIndex - 1), 0, headers.length - 1));
            workbook.write(output);
            auditTrail.record(structureId, "SCHEDULE_PLAN_EXPORTED", "SCHEDULE_PLAN", plan.id,
                    Map.of("format", "XLSX", "rows", rowIndex - 1));
            return Response.ok(output.toByteArray())
                    .header("Content-Disposition", "attachment; filename=schedule-plan-" + plan.id + "-v" + plan.revision + ".xlsx")
                    .build();
        } catch (Exception exception) {
            return ApiErrors.serverError("SCHEDULE_PLAN_EXPORT_FAILED");
        }
    }

    /** Captures the current shift assignments in a date window as a reviewable draft. */
    @POST
    @RolesAllowed({"ADMIN", "CAPOSALA", "DISPATCHER"})
    @Transactional
    public Response create(@QueryParam("structureId") int structureId, SchedulePlanRequest request) {
        if (request == null || WorkflowSupport.text(request.name, 160).isBlank())
            return ApiErrors.badRequest("SCHEDULE_PLAN_NAME_REQUIRED");
        final String start;
        final String end;
        try {
            start = WorkflowSupport.dbDateTime(request.windowStart);
            end = WorkflowSupport.dbDateTime(request.windowEnd);
        } catch (Exception ignored) {
            return ApiErrors.badRequest("SCHEDULE_PLAN_WINDOW_INVALID");
        }
        if (start == null || end == null || !WorkflowSupport.parseDateTime(end).isAfter(WorkflowSupport.parseDateTime(start)))
            return ApiErrors.badRequest("SCHEDULE_PLAN_WINDOW_INVALID");
        // Idempotent by window: a dispatcher who clicks twice (or retries after a dropped
        // response) must land on the draft already under edit instead of forking a twin
        // draft of the same window on every click. A window snapshot is fully determined
        // by its window, so a same-window DRAFT is always the same content.
        SchedulePlanEntity existingDraft = SchedulePlanEntity.find(
                "structureId = ?1 and windowStart = ?2 and windowEnd = ?3 and status = 'DRAFT'",
                structureId, start, end).firstResult();
        if (existingDraft != null) return Response.ok(planDto(existingDraft, true)).build();
        List<ShiftEntity> shifts = shiftsForWindow(structureId, start, end);
        if (shifts.isEmpty()) return ApiErrors.badRequest("SCHEDULE_PLAN_EMPTY");

        SchedulePlanEntity plan = new SchedulePlanEntity();
        plan.structureId = structureId;
        plan.name = WorkflowSupport.text(request.name, 160);
        plan.windowStart = start;
        plan.windowEnd = end;
        plan.parentPlanId = request.parentPlanId;
        plan.source = "WINDOW";
        plan.solverScore = WorkflowSupport.text(request.solverScore, 200);
        plan.createdBy = actor();
        plan.createdAt = plan.updatedAt = WorkflowSupport.now();
        if (request.parentPlanId != null) {
            SchedulePlanEntity parent = plan(request.parentPlanId, structureId);
            if (parent == null) return ApiErrors.badRequest("SCHEDULE_PLAN_PARENT_INVALID");
            plan.revision = parent.revision + 1;
        }
        plan.persistAndFlush();
        snapshotAssignments(plan, shifts);
        auditTrail.record(structureId, "SCHEDULE_PLAN_CREATED", "SCHEDULE_PLAN", plan.id,
                Map.of("name", plan.name, "assignments", shifts.size()));
        emit(plan, "created");
        return Response.status(Response.Status.CREATED).entity(planDto(plan, true)).build();
    }

    /** Creates a draft containing only the slots of one order, avoiding unrelated shifts in the same date window. */
    @POST
    @Path("/from-order/{orderId}")
    @RolesAllowed({"ADMIN", "CAPOSALA", "DISPATCHER"})
    @Transactional
    public Response createFromOrder(@PathParam("orderId") int orderId,
            @QueryParam("structureId") int structureId, SchedulePlanRequest request) {
        AuditOrderEntity order = AuditOrderEntity.find(
                "id = ?1 and structureId = ?2", orderId, structureId).firstResult();
        if (order == null) return ApiErrors.notFound("AUDIT_ORDER_NOT_FOUND");
        // Only an approved order may be scheduled: approval lives on the order, not on the plan.
        if (!"APPROVED".equals(order.status)) return ApiErrors.conflict("AUDIT_ORDER_NOT_APPROVED");
        List<ShiftEntity> shifts = ShiftEntity.<ShiftEntity>find(
                "auditOrderPhaseId in (select p.id from AuditOrderPhaseEntity p where p.auditOrderId = ?1 and p.status = 'READY') order by startTime, id",
                order.id).list();
        if (shifts.isEmpty()) return ApiErrors.conflict("AUDIT_ORDER_NO_READY_PHASES");

        SchedulePlanEntity plan = new SchedulePlanEntity();
        plan.structureId = structureId;
        String requestedName = request == null ? "" : WorkflowSupport.text(request.name, 160);
        plan.name = requestedName.isBlank() ? order.orderCode + " 排程草案" : requestedName;
        plan.windowStart = shifts.stream().map(shift -> shift.startTime).min(String::compareTo).orElseThrow();
        plan.windowEnd = shifts.stream().map(shift -> shift.endTime).max(String::compareTo).orElseThrow();
        plan.createdBy = actor();
        plan.createdAt = plan.updatedAt = WorkflowSupport.now();
        plan.source = "ORDER";
        plan.persistAndFlush();
        snapshotAssignments(plan, shifts, false);
        auditTrail.record(structureId, "SCHEDULE_PLAN_CREATED_FROM_ORDER", "SCHEDULE_PLAN", plan.id,
                Map.of("name", plan.name, "orderId", order.id, "orderCode", order.orderCode,
                        "assignments", shifts.size()));
        emit(plan, "created-from-order");
        return Response.status(Response.Status.CREATED).entity(planDto(plan, true)).build();
    }

    /** Creates a new editable revision without modifying a published/approved plan. */
    @POST
    @Path("/{id}/revise")
    @RolesAllowed({"ADMIN", "CAPOSALA", "DISPATCHER"})
    @Transactional
    public Response revise(@PathParam("id") int id, @QueryParam("structureId") int structureId) {
        SchedulePlanEntity source = plan(id, structureId);
        if (source == null) return ApiErrors.notFound("SCHEDULE_PLAN_NOT_FOUND");
        // Idempotent by parent: a dispatcher who clicks again (or retries after a dropped
        // response) must land on the draft already under edit instead of forking a new
        // sibling revision on every click.
        SchedulePlanEntity openDraft = SchedulePlanEntity.find(
                "structureId = ?1 and parentPlanId = ?2 and status = 'DRAFT'", structureId, id)
                .firstResult();
        if (openDraft != null) return Response.ok(planDto(openDraft, true)).build();
        SchedulePlanEntity copy = new SchedulePlanEntity();
        copy.structureId = structureId;
        copy.name = source.name;
        copy.windowStart = source.windowStart;
        copy.windowEnd = source.windowEnd;
        copy.status = "DRAFT";
        copy.revision = source.revision + 1;
        copy.parentPlanId = source.id;
        copy.solverScore = source.solverScore;
        copy.source = "REVISION";
        copy.createdBy = actor();
        copy.createdAt = copy.updatedAt = WorkflowSupport.now();
        copy.persistAndFlush();
        for (SchedulePlanAssignmentEntity row : assignments(source.id)) {
            SchedulePlanAssignmentEntity cloned = copyAssignment(row, copy.id);
            ShiftEntity shift = ShiftEntity.findById(row.shiftId);
            EmployeeEntity employee = row.employeeId == null ? null : EmployeeEntity.findById(row.employeeId);
            if (shift != null) cloned.shiftVersion = shift.version;
            cloned.employeeStatusUpdatedAt = employee == null || employee.statusUpdatedAt == null ? "" : employee.statusUpdatedAt;
            cloned.persist();
        }
        auditTrail.record(structureId, "SCHEDULE_PLAN_REVISED", "SCHEDULE_PLAN", copy.id,
                Map.of("parentPlanId", source.id, "revision", copy.revision));
        emit(copy, "revised");
        return Response.status(Response.Status.CREATED).entity(planDto(copy, true)).build();
    }

    @PUT
    @Path("/{id}/assignments/{assignmentId}")
    @RolesAllowed({"ADMIN", "CAPOSALA", "DISPATCHER"})
    @Transactional
    public Response updateAssignment(@PathParam("id") int id, @PathParam("assignmentId") int assignmentId,
            @QueryParam("structureId") int structureId, PlanAssignmentUpdate update) {
        SchedulePlanEntity plan = plan(id, structureId);
        if (plan == null) return ApiErrors.notFound("SCHEDULE_PLAN_NOT_FOUND");
        if (!EDITABLE.contains(plan.status)) return ApiErrors.conflict("SCHEDULE_PLAN_NOT_EDITABLE");
        SchedulePlanAssignmentEntity assignment = SchedulePlanAssignmentEntity.find(
                "id = ?1 and planId = ?2", assignmentId, id).firstResult();
        if (assignment == null) return ApiErrors.notFound("SCHEDULE_ASSIGNMENT_NOT_FOUND");
        if (update == null || WorkflowSupport.text(update.changeReason, 500).isBlank())
            return ApiErrors.badRequest("SCHEDULE_CHANGE_REASON_REQUIRED");
        if (update.employeeId != null) {
            EmployeeEntity employee = EmployeeEntity.findById(update.employeeId);
            if (employee == null || employee.structureId != structureId || !employee.active)
                return ApiErrors.badRequest("SCHEDULE_EMPLOYEE_INVALID");
            String risk = assignmentRisk(assignment.shiftId, update.employeeId, plan.id, assignment.id);
            if (!risk.isBlank()) return Response.status(Response.Status.CONFLICT)
                    .entity(Map.of("error", "SCHEDULE_ASSIGNMENT_INVALID", "risk", risk)).build();
            assignment.employeeStatusUpdatedAt = employee.statusUpdatedAt == null ? "" : employee.statusUpdatedAt;
        } else {
            assignment.employeeStatusUpdatedAt = "";
        }
        assignment.employeeId = update.employeeId;
        assignment.locked = update.locked;
        assignment.assignmentRole = update.assignmentRole == null ? "AUDITOR" : update.assignmentRole.toUpperCase();
        assignment.changeReason = WorkflowSupport.text(update.changeReason, 500);
        ShiftEntity shift = ShiftEntity.findById(assignment.shiftId);
        assignment.shiftVersion = shift == null ? assignment.shiftVersion : shift.version;
        assignment.riskCode = assignment.employeeId == null ? "UNASSIGNED" : "";
        plan.updatedAt = WorkflowSupport.now();
        auditTrail.record(structureId, "SCHEDULE_ASSIGNMENT_CHANGED", "SCHEDULE_PLAN_ASSIGNMENT", assignment.id,
                Map.of("planId", plan.id, "employeeId", String.valueOf(assignment.employeeId),
                        "reason", assignment.changeReason));
        emit(plan, "assignment-changed");
        return Response.ok(assignmentDto(assignment)).build();
    }

    @GET
    @Path("/{id}/assignments/{assignmentId}/candidates")
    public Response candidates(@PathParam("id") int id, @PathParam("assignmentId") int assignmentId,
            @QueryParam("structureId") int structureId,
            @QueryParam("limit") @DefaultValue("5") int limit,
            @QueryParam("abilityWeight") Integer abilityWeight,
            @QueryParam("locationWeight") Integer locationWeight,
            @QueryParam("workloadWeight") Integer workloadWeight,
            @QueryParam("continuityWeight") Integer continuityWeight,
            @QueryParam("ageWeight") Integer ageWeight) {
        SchedulePlanEntity plan = plan(id, structureId);
        if (plan == null) return ApiErrors.notFound("SCHEDULE_PLAN_NOT_FOUND");
        SchedulePlanAssignmentEntity assignment = SchedulePlanAssignmentEntity.find(
                "id = ?1 and planId = ?2", assignmentId, id).firstResult();
        if (assignment == null) return ApiErrors.notFound("SCHEDULE_ASSIGNMENT_NOT_FOUND");
        ShiftEntity shift = ShiftEntity.findById(assignment.shiftId);
        if (shift == null) return ApiErrors.notFound("SHIFT_NOT_FOUND");
        CandidateWeights weights = candidateWeights(structureId, abilityWeight, locationWeight, workloadWeight,
                continuityWeight, ageWeight);
        if (limit < 1 || limit > 20 || !weights.valid()) return ApiErrors.badRequest("CANDIDATE_SCORING_INVALID");

        List<EmployeeEntity> employees = EmployeeEntity.<EmployeeEntity>find(
                "structureId = ?1 and active = ?2 order by lastName, firstName", structureId, true).list();
        List<Integer> employeeIds = employees.stream().map(employee -> employee.id).toList();
        Map<Integer, List<EmployeeSkillEntity>> qualifications = employeeIds.isEmpty() ? Map.of()
                : EmployeeSkillEntity.<EmployeeSkillEntity>list("employeeId in ?1", employeeIds).stream()
                        .collect(Collectors.groupingBy(row -> row.employeeId));
        List<Integer> requiredSkills = ShiftSkillEntity.<ShiftSkillEntity>list(
                "shiftId = ?1 and skillTypeId = 1", shift.id).stream().map(row -> row.skillId).toList();
        Map<Integer, Integer> plannedMinutes = modalPlannedMinutes(plan, structureId);
        Integer continuityEmployeeId = continuityEmployeeId(plan.id, assignment);
        // 出发地判定要看"这个人最近一次任务在哪结束"，所以按人分组一次性取回，避免每个候选各查一遍。
        Map<Integer, List<ShiftEntity>> assignmentsByEmployee = ShiftEntity.<ShiftEntity>find(
                        "employeeId is not null and locationId in (select l.id from LocationEntity l where l.structureId = ?1)",
                        structureId).list().stream()
                .collect(Collectors.groupingBy(row -> row.employeeId));

        List<Map<String, Object>> result = employees.stream()
                .map(employee -> candidateDto(plan.id, assignment.id, shift, employee, weights,
                        qualifications.getOrDefault(employee.id, List.of()), requiredSkills,
                        plannedMinutes.getOrDefault(employee.id, 0), continuityEmployeeId,
                        assignmentsByEmployee.getOrDefault(employee.id, List.of())))
                .filter(candidate -> (Boolean) candidate.get("eligible"))
                .sorted(candidateComparator())
                .limit(limit)
                .toList();
        for (int index = 0; index < result.size(); index++) result.get(index).put("rank", index + 1);
        // 只提示不拦截：标出被其他草案锁定占用的人，交由调度员判断。
        Map<Integer, Map<String, Object>> preemptions = draftPreemptions(structureId, shift, plan.id);
        for (Map<String, Object> candidate : result) {
            Map<String, Object> preempted = preemptions.get((Integer) candidate.get("employeeId"));
            if (preempted != null) candidate.put("preemptedBy", preempted);
        }
        return Response.ok(result).build();
    }

    /**
     * Exclusion drill-down for one slot of the order-candidates view: which auditors fail the
     * clicked hard constraint, and the concrete reason for each (missing certificates, the
     * conflicting order, the overlapping unavailable window, transfer/buffer minutes...).
     * Read-only; recomputes the same evaluation pipeline as order-candidates for a single slot.
     */
    @GET
    @Path("/order-candidates/exclusions")
    public Response orderCandidateExclusions(@QueryParam("structureId") int structureId,
            @QueryParam("orderCode") String orderCode,
            @QueryParam("phaseId") int phaseId,
            @QueryParam("slotIndex") int slotIndex,
            @QueryParam("risk") String risk) {
        String normalizedCode = orderCode == null ? "" : orderCode.trim();
        String normalizedRisk = risk == null ? "" : risk.trim();
        if (structureId <= 0 || normalizedCode.isBlank() || phaseId <= 0 || slotIndex < 1
                || normalizedRisk.isBlank()) return ApiErrors.badRequest("EXCLUSION_QUERY_INVALID");
        AuditOrderEntity order = AuditOrderEntity.find(
                "structureId = ?1 and lower(orderCode) = ?2", structureId, normalizedCode.toLowerCase()).firstResult();
        if (order == null) return ApiErrors.notFound("AUDIT_ORDER_NOT_FOUND");
        AuditOrderPhaseEntity phase = AuditOrderPhaseEntity.findById(phaseId);
        if (phase == null || phase.auditOrderId != order.id) return ApiErrors.notFound("AUDIT_PHASE_NOT_FOUND");
        List<ShiftEntity> slots = ShiftEntity.<ShiftEntity>find(
                "auditOrderPhaseId = ?1 order by id", phase.id).list();
        if (slotIndex > slots.size()) return ApiErrors.notFound("AUDIT_PHASE_SLOT_NOT_FOUND");
        ShiftEntity shift = slots.get(slotIndex - 1);
        CandidateWeights weights = candidateWeights(structureId, null, null, null, null, null);
        List<EmployeeEntity> employees = EmployeeEntity.<EmployeeEntity>find(
                "structureId = ?1 and active = ?2 order by lastName, firstName", structureId, true).list();
        List<Integer> employeeIds = employees.stream().map(employee -> employee.id).toList();
        Map<Integer, List<EmployeeSkillEntity>> qualifications = employeeIds.isEmpty() ? Map.of()
                : EmployeeSkillEntity.<EmployeeSkillEntity>list("employeeId in ?1", employeeIds).stream()
                        .collect(Collectors.groupingBy(row -> row.employeeId));
        Map<Integer, List<EmployeeDateEntity>> employeeDates = employeeIds.isEmpty() ? Map.of()
                : EmployeeDateEntity.<EmployeeDateEntity>list("employeeId in ?1", employeeIds).stream()
                        .collect(Collectors.groupingBy(row -> row.employeeId));
        List<ShiftEntity> existingAssignments = ShiftEntity.<ShiftEntity>find(
                "employeeId is not null and locationId in (select l.id from LocationEntity l where l.structureId = ?1)",
                structureId).list();
        Map<Integer, List<ShiftEntity>> assignmentsByEmployee = existingAssignments.stream()
                .collect(Collectors.groupingBy(row -> row.employeeId));
        Map<Integer, Integer> plannedMinutes = new LinkedHashMap<>();
        for (ShiftEntity assigned : existingAssignments) {
            long minutes = Duration.between(WorkflowSupport.parseDateTime(assigned.startTime),
                    WorkflowSupport.parseDateTime(assigned.endTime)).toMinutes();
            plannedMinutes.merge(assigned.employeeId, (int) Math.max(0, minutes), Integer::sum);
        }
        List<Integer> requiredSkills = ShiftSkillEntity.<ShiftSkillEntity>list(
                "shiftId = ?1 and skillTypeId = 1", shift.id).stream().map(row -> row.skillId).toList();
        Integer continuityEmployeeId = directContinuityEmployeeId(order.orderCode, phase, slotIndex);
        Map<Integer, String> skillNames = SkillEntity.<SkillEntity>listAll().stream()
                .collect(Collectors.toMap(skill -> skill.id, skill -> skill.name, (a, b) -> a, LinkedHashMap::new));

        List<Map<String, Object>> employeesOut = new ArrayList<>();
        for (EmployeeEntity employee : employees) {
            Map<String, Object> evaluated = directCandidateDto(shift, employee, weights,
                    qualifications.getOrDefault(employee.id, List.of()), requiredSkills,
                    plannedMinutes.getOrDefault(employee.id, 0), continuityEmployeeId,
                    employeeDates.getOrDefault(employee.id, List.of()),
                    assignmentsByEmployee.getOrDefault(employee.id, List.of()));
            if (Boolean.TRUE.equals(evaluated.get("eligible"))) continue;
            if (!normalizedRisk.equals(evaluated.get("risk"))) continue;
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("employeeId", employee.id);
            entry.put("code", employee.code);
            entry.put("name", employee.firstName + " " + employee.lastName);
            entry.put("detail", exclusionDetail(shift, employee,
                    qualifications.getOrDefault(employee.id, List.of()), requiredSkills,
                    employeeDates.getOrDefault(employee.id, List.of()),
                    assignmentsByEmployee.getOrDefault(employee.id, List.of()),
                    normalizedRisk, skillNames));
            employeesOut.add(entry);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("risk", normalizedRisk);
        result.put("count", employeesOut.size());
        result.put("employees", employeesOut);
        return Response.ok(result).build();
    }

    /** Concrete reason an employee failed one hard constraint, for the exclusion drill-down. */
    private String exclusionDetail(ShiftEntity target, EmployeeEntity employee,
            List<EmployeeSkillEntity> qualifications, List<Integer> requiredSkills,
            List<EmployeeDateEntity> employeeDates, List<ShiftEntity> existingAssignments,
            String risk, Map<Integer, String> skillNames) {
        LocalDateTime start = WorkflowSupport.parseDateTime(target.startTime);
        LocalDateTime end = WorkflowSupport.parseDateTime(target.endTime);
        switch (risk) {
            case "QUALIFICATION_MISSING" -> {
                List<String> missing = requiredSkills.stream()
                        .filter(skillId -> qualifications.stream().noneMatch(row -> row.skillId == skillId))
                        .map(skillId -> skillNames.getOrDefault(skillId, "#" + skillId)).toList();
                return "缺少所需资格：" + String.join("、", missing);
            }
            case "QUALIFICATION_EXPIRED" -> {
                List<String> expired = qualifications.stream()
                        .filter(row -> requiredSkills.contains(row.skillId))
                        .filter(row -> !qualificationUsableOn(row, start.toLocalDate())
                                || !qualificationUsableOn(row, end.toLocalDate()))
                        .map(row -> skillNames.getOrDefault(row.skillId, "#" + row.skillId)
                                + "（有效期 " + (row.validFrom == null ? "?" : row.validFrom) + " ~ "
                                + (row.validUntil == null ? "?" : row.validUntil) + "）")
                        .toList();
                return "资格无效或不在任务期间内：" + (expired.isEmpty() ? "见资格档案" : String.join("、", expired));
            }
            case "LEAD_QUALIFICATION_REQUIRED" -> {
                return "不具备有效的主审核员资质（任务首个名额要求主审核员）";
            }
            case "EMPLOYEE_UNAVAILABLE" -> {
                for (EmployeeDateEntity date : employeeDates) {
                    if (date.dateTypeId != EmployeeDateEntity.TYPE_UNAVAILABLE) continue;
                    LocalDateTime unavailableStart = EmployeeDateEntity.parseDbDateTime(date.dateStart);
                    LocalDateTime unavailableEnd = EmployeeDateEntity.parseDbDateTime(date.dateEnd);
                    if (date.dateEnd != null && date.dateEnd.trim().length() == 10) unavailableEnd = unavailableEnd.plusDays(1);
                    if (unavailableStart != null && unavailableEnd != null
                            && start.isBefore(unavailableEnd) && end.isAfter(unavailableStart)) {
                        return "不可用时段 " + date.dateStart + " ~ " + date.dateEnd + " 与任务时间重叠";
                    }
                }
                return "与登记的不可用时段重叠";
            }
            case "TIME_OVERLAP" -> {
                for (ShiftEntity other : existingAssignments) {
                    if (other.id.equals(target.id)) continue;
                    LocalDateTime otherStart = WorkflowSupport.parseDateTime(other.startTime);
                    LocalDateTime otherEnd = WorkflowSupport.parseDateTime(other.endTime);
                    if (start.isBefore(otherEnd) && end.isAfter(otherStart)) {
                        return "与 " + other.auditOrderCode + " · " + other.auditStage + "（"
                                + other.startTime + " ~ " + other.endTime + "）时间重叠";
                    }
                }
                return "与其他已分配任务时间重叠";
            }
            case "TRANSFER_BUFFER_INSUFFICIENT" -> {
                for (ShiftEntity other : existingAssignments) {
                    if (other.id.equals(target.id)) continue;
                    LocalDateTime otherEnd = WorkflowSupport.parseDateTime(other.endTime);
                    LocalDateTime otherStart = WorkflowSupport.parseDateTime(other.startTime);
                    if (!otherEnd.isAfter(start)) {
                        long needed = (long) employee.bufferAfterMinutes
                                + travelMinutes(other.locationId, target.locationId) + employee.bufferBeforeMinutes;
                        if (otherEnd.plusMinutes(needed).isAfter(start)) {
                            return "前一任务 " + other.auditOrderCode + " 结束后需 " + needed
                                    + " 分钟转场+缓冲，距本任务开始不足";
                        }
                    } else if (!end.isAfter(otherStart)) {
                        long needed = (long) employee.bufferAfterMinutes
                                + travelMinutes(target.locationId, other.locationId) + employee.bufferBeforeMinutes;
                        if (end.plusMinutes(needed).isAfter(otherStart)) {
                            return "本任务结束后需 " + needed + " 分钟转场+缓冲，接下一任务 "
                                    + other.auditOrderCode + " 时间不足";
                        }
                    }
                }
                return "与相邻任务的转场或缓冲时间不足";
            }
            case "EMPLOYEE_BUSY" -> {
                if (employee.busyUntil != null && !employee.busyUntil.isBlank())
                    return "外派/忙碌至 " + employee.busyUntil + "（含转场与缓冲后仍晚于任务开始）";
                return "当前状态为 " + (employee.operationalStatus == null ? "未知" : employee.operationalStatus);
            }
            case "EMPLOYEE_INACTIVE" -> {
                return "员工已停用";
            }
            default -> {
                return risk;
            }
        }
    }

    /**
     * Shifts currently committed to one employee (the "已承担任务" block of the profile modal).
     * Complements the declared dates: declarations are input claims, these are real schedule
     * commitments — shown side by side, never merged.
     */
    @GET
    @Path("/employee-shifts")
    public Response employeeShifts(@QueryParam("structureId") int structureId,
            @QueryParam("employeeId") int employeeId) {
        if (structureId <= 0 || employeeId <= 0) return ApiErrors.badRequest("EMPLOYEE_ID_REQUIRED");
        EmployeeEntity employee = EmployeeEntity.findById(employeeId);
        if (employee == null || employee.structureId != structureId) return ApiErrors.notFound("EMPLOYEE_NOT_FOUND");
        List<ShiftEntity> shifts = ShiftEntity.<ShiftEntity>find("employeeId = ?1 order by startTime", employeeId).list();
        List<Map<String, Object>> shiftDtos = new ArrayList<>();
        for (ShiftEntity shift : shifts) {
            Map<String, Object> dto = new LinkedHashMap<>();
            dto.put("shiftId", shift.id); dto.put("startTime", shift.startTime); dto.put("endTime", shift.endTime);
            dto.put("locationId", shift.locationId); dto.put("auditOrderCode", shift.auditOrderCode);
            dto.put("auditStage", shift.auditStage); dto.put("publishedPlanId", shift.publishedPlanId);
            shiftDtos.add(dto);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("employeeId", employeeId);
        result.put("count", shiftDtos.size());
        result.put("shifts", shiftDtos);
        return Response.ok(result).build();
    }

    /**
     * Direct order-number lookup for the dispatcher's primary workflow. This is intentionally
     * read-only: it evaluates every materialized slot using current employee status, certificates,
     * unavailable periods, existing assignments, buffers and transfer time, then ranks only the
     * feasible people. A schedule plan is not required to obtain the shortlist.
     */
    @GET
    @Path("/order-candidates")
    public Response orderCandidates(@QueryParam("structureId") int structureId,
            @QueryParam("orderCode") String orderCode,
            @QueryParam("limit") @DefaultValue("5") int limit,
            @QueryParam("abilityWeight") Integer abilityWeight,
            @QueryParam("locationWeight") Integer locationWeight,
            @QueryParam("workloadWeight") Integer workloadWeight,
            @QueryParam("continuityWeight") Integer continuityWeight,
            @QueryParam("ageWeight") Integer ageWeight) {
        String normalizedCode = orderCode == null ? "" : orderCode.trim();
        if (structureId <= 0 || normalizedCode.isBlank()) return ApiErrors.badRequest("AUDIT_ORDER_CODE_REQUIRED");
        CandidateWeights weights = candidateWeights(structureId, abilityWeight, locationWeight, workloadWeight,
                continuityWeight, ageWeight);
        if (limit < 1 || limit > 20 || !weights.valid()) return ApiErrors.badRequest("CANDIDATE_SCORING_INVALID");

        AuditOrderEntity order = AuditOrderEntity.find(
                "structureId = ?1 and lower(orderCode) = ?2", structureId, normalizedCode.toLowerCase()).firstResult();
        if (order == null) return ApiErrors.notFound("AUDIT_ORDER_NOT_FOUND");
        if (!"APPROVED".equals(order.status)) return ApiErrors.conflict("AUDIT_ORDER_NOT_APPROVED");
        List<AuditOrderPhaseEntity> phases = AuditOrderPhaseEntity.<AuditOrderPhaseEntity>find(
                "auditOrderId = ?1 and status = 'READY' order by startTime, id", order.id).list();
        if (phases.isEmpty()) return ApiErrors.conflict("AUDIT_ORDER_NO_READY_PHASES");
        List<EmployeeEntity> employees = EmployeeEntity.<EmployeeEntity>find(
                "structureId = ?1 and active = ?2 order by lastName, firstName", structureId, true).list();
        List<Integer> employeeIds = employees.stream().map(employee -> employee.id).toList();
        Map<Integer, List<EmployeeSkillEntity>> qualifications = employeeIds.isEmpty() ? Map.of()
                : EmployeeSkillEntity.<EmployeeSkillEntity>list("employeeId in ?1", employeeIds).stream()
                        .collect(Collectors.groupingBy(row -> row.employeeId));
        Map<Integer, List<EmployeeDateEntity>> employeeDates = employeeIds.isEmpty() ? Map.of()
                : EmployeeDateEntity.<EmployeeDateEntity>list("employeeId in ?1", employeeIds).stream()
                        .collect(Collectors.groupingBy(row -> row.employeeId));
        List<ShiftEntity> existingAssignments = ShiftEntity.<ShiftEntity>find(
                "employeeId is not null and locationId in (select l.id from LocationEntity l where l.structureId = ?1)",
                structureId).list();
        Map<Integer, List<ShiftEntity>> assignmentsByEmployee = existingAssignments.stream()
                .collect(Collectors.groupingBy(row -> row.employeeId));
        Map<Integer, Integer> plannedMinutes = new LinkedHashMap<>();
        for (ShiftEntity assigned : existingAssignments) {
            long minutes = Duration.between(WorkflowSupport.parseDateTime(assigned.startTime),
                    WorkflowSupport.parseDateTime(assigned.endTime)).toMinutes();
            plannedMinutes.merge(assigned.employeeId, (int) Math.max(0, minutes), Integer::sum);
        }

        // 名额班次只取一次：既用于逐个名额评估，也用于识别「本单别的名额已经排了谁」。
        // 为什么需要后者：候选只对**已落库**的班次判冲突，同一订单里还没保存的空名额互相看不见，
        // 于是同一个人会同时霸占几个名额的候选首位（2 人名额的订单，两张表往往完全重合）。
        // 这里把人标出来交给前端置灰提示，避免调度员把同一个人点到两个名额上。
        List<List<ShiftEntity>> slotsByPhase = new ArrayList<>();
        Map<Integer, Map<String, Object>> orderHolders = new LinkedHashMap<>();
        for (AuditOrderPhaseEntity phase : phases) {
            List<ShiftEntity> slots = ShiftEntity.<ShiftEntity>find(
                    "auditOrderPhaseId = ?1 order by id", phase.id).list();
            slotsByPhase.add(slots);
            for (int slotIndex = 0; slotIndex < slots.size(); slotIndex++) {
                ShiftEntity slot = slots.get(slotIndex);
                if (slot.employeeId == null) continue;
                Map<String, Object> holder = new LinkedHashMap<>();
                holder.put("shiftId", slot.id);
                holder.put("phaseCode", phase.phaseCode);
                holder.put("phaseName", phase.phaseName);
                holder.put("slotIndex", slotIndex + 1);
                holder.put("startTime", slot.startTime);
                holder.put("endTime", slot.endTime);
                orderHolders.put(slot.employeeId, holder);
            }
        }

        List<Map<String, Object>> phaseDtos = new ArrayList<>();
        for (int phaseIndex = 0; phaseIndex < phases.size(); phaseIndex++) {
            AuditOrderPhaseEntity phase = phases.get(phaseIndex);
            Map<String, Object> phaseDto = new LinkedHashMap<>();
            phaseDto.put("phaseId", phase.id); phaseDto.put("phaseCode", phase.phaseCode);
            phaseDto.put("phaseName", phase.phaseName); phaseDto.put("startTime", phase.startTime);
            phaseDto.put("endTime", phase.endTime); phaseDto.put("locationId", phase.locationId);
            phaseDto.put("requiredHeadcount", phase.requiredHeadcount); phaseDto.put("leadRequired", phase.leadRequired);
            List<ShiftEntity> slots = slotsByPhase.get(phaseIndex);
            List<Map<String, Object>> slotDtos = new ArrayList<>();
            for (int slotIndex = 0; slotIndex < slots.size(); slotIndex++) {
                ShiftEntity shift = slots.get(slotIndex);
                List<Integer> requiredSkills = ShiftSkillEntity.<ShiftSkillEntity>list(
                        "shiftId = ?1 and skillTypeId = 1", shift.id).stream().map(row -> row.skillId).toList();
                Integer continuityEmployeeId = directContinuityEmployeeId(order.orderCode, phase, slotIndex + 1);
                List<Map<String, Object>> evaluated = employees.stream()
                        .map(employee -> directCandidateDto(shift, employee, weights,
                                qualifications.getOrDefault(employee.id, List.of()), requiredSkills,
                                plannedMinutes.getOrDefault(employee.id, 0), continuityEmployeeId,
                                employeeDates.getOrDefault(employee.id, List.of()),
                                assignmentsByEmployee.getOrDefault(employee.id, List.of())))
                        .toList();
                Map<String, Long> excludedCounts = evaluated.stream()
                        .filter(candidate -> !(Boolean) candidate.get("eligible"))
                        .collect(Collectors.groupingBy(candidate -> (String) candidate.get("risk"),
                                LinkedHashMap::new, Collectors.counting()));
                List<Map<String, Object>> candidates = evaluated.stream()
                        .filter(candidate -> (Boolean) candidate.get("eligible"))
                        .sorted(candidateComparator()).limit(limit).toList();
                for (int index = 0; index < candidates.size(); index++) candidates.get(index).put("rank", index + 1);
                // 同 candidates()：标出被其他草案锁定占用的人，只提示不拦截。
                Map<Integer, Map<String, Object>> preemptions = draftPreemptions(structureId, shift, null);
                for (Map<String, Object> candidate : candidates) {
                    Map<String, Object> preempted = preemptions.get((Integer) candidate.get("employeeId"));
                    if (preempted != null) candidate.put("preemptedBy", preempted);
                    // 本单其他名额已经排了他：只提示（前端置灰），不把人从候选里剔除——
                    // 调度员可能正打算把他从旧名额挪到当前名额。
                    Map<String, Object> sibling = orderHolders.get((Integer) candidate.get("employeeId"));
                    if (sibling != null && !sibling.get("shiftId").equals(shift.id)) {
                        candidate.put("siblingAssignment", sibling);
                    }
                }
                Map<String, Object> slotDto = new LinkedHashMap<>();
                slotDto.put("shiftId", shift.id); slotDto.put("slotIndex", slotIndex + 1);
                slotDto.put("requiredRole", shift.requiredRole); slotDto.put("requiredSkillIds", requiredSkills);
                slotDto.put("evaluatedCount", evaluated.size());
                slotDto.put("eligibleCount", evaluated.size() - excludedCounts.values().stream().mapToLong(Long::longValue).sum());
                slotDto.put("excludedCounts", excludedCounts);
                // The current holder may rank below the returned top-N (his own slot counts into his
                // booked hours), so surface him at slot level instead of relying on the ranking.
                if (shift.employeeId == null) {
                    slotDto.put("currentAssignee", null);
                } else {
                    EmployeeEntity current = EmployeeEntity.findById(shift.employeeId);
                    slotDto.put("currentAssignee", current == null ? null : Map.of(
                            "employeeId", current.id, "code", current.code,
                            "name", current.firstName + " " + current.lastName));
                }
                slotDto.put("candidates", candidates); slotDtos.add(slotDto);
            }
            phaseDto.put("slots", slotDtos); phaseDtos.add(phaseDto);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("orderId", order.id); result.put("orderCode", order.orderCode);
        result.put("customerName", order.customerName); result.put("projectName", order.projectName);
        result.put("status", order.status); result.put("priority", order.priority); result.put("topK", limit);
        result.put("weights", Map.of("ability", weights.ability, "location", weights.location,
                "workload", weights.workload, "continuity", weights.continuity, "age", weights.age));
        result.put("weightVersion", weights.version); result.put("weightSource", weights.source);
        result.put("phases", phaseDtos);
        return Response.ok(result).build();
    }

    /** AI advisory pass over the deterministic eligible shortlist; it cannot add or assign people. */
    @POST
    @Path("/order-candidates/ai-review")
    @RolesAllowed({"ADMIN", "CAPOSALA", "DISPATCHER", "APPROVER"})
    public Response aiReview(@QueryParam("structureId") int structureId,
            @QueryParam("orderCode") String orderCode,
            @QueryParam("limit") @DefaultValue("5") int limit) {
        if (!aiProvider.configured()) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE).entity(Map.of(
                    "error", "AI_NOT_CONFIGURED", "provider", aiProvider.providerName(),
                    "model", aiProvider.model(), "requiredEnvironment",
                    List.of("DISPATCH_AI_ENABLED=true", "DISPATCH_AI_API_KEY=..."))).build();
        }
        Response deterministic = orderCandidates(structureId, orderCode, limit,
                null, null, null, null, null);
        if (deterministic.getStatus() != Response.Status.OK.getStatusCode()) return deterministic;
        @SuppressWarnings("unchecked")
        Map<String, Object> candidateResult = (Map<String, Object>) deterministic.getEntity();
        Map<String, Object> context = aiCandidateContext(candidateResult);
        try {
            // Hard fuse: the provider has HTTP-level timeouts, but observed hangs (proxy stalls,
            // dribbled response bodies) can outlive them while this writer-gated request holds
            // WRITER_GATE and freezes every database write. Bound the wait here so the gate is
            // released on schedule regardless of what the HTTP layer does.
            CandidateAiProvider.AiReview review = reviewWithHardTimeout(context);
            if (!validAiReview(review, context)) return Response.status(Response.Status.BAD_GATEWAY)
                    .entity(Map.of("error", "AI_RESPONSE_OUT_OF_SCOPE")).build();
            aiReviewAudit.record(structureId, String.valueOf(candidateResult.get("orderCode")),
                    aiProvider.providerName(), aiProvider.model(), review.responseId(),
                    review.recommendations().size());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("orderId", candidateResult.get("orderId"));
            result.put("orderCode", candidateResult.get("orderCode"));
            result.put("provider", aiProvider.providerName()); result.put("model", aiProvider.model());
            result.put("responseId", review.responseId()); result.put("generatedAt", WorkflowSupport.now());
            result.put("advisoryOnly", true); result.put("overallSummary", review.overallSummary());
            result.put("recommendations", review.recommendations()); result.put("warnings", review.warnings());
            return Response.ok(result).build();
        } catch (CandidateAiProvider.ProviderException exception) {
            return Response.status(exception.httpStatus()).entity(Map.of("error", exception.code())).build();
        }
    }

    /** Hard-bounds the provider call; a hung HTTP exchange must not outlive the writer gate. */
    private CandidateAiProvider.AiReview reviewWithHardTimeout(Map<String, Object> context)
            throws CandidateAiProvider.ProviderException {
        int hardTimeoutSeconds = Math.min(aiProviderHardTimeoutSeconds() + 15, 90);
        try {
            return CompletableFuture
                    .supplyAsync(() -> aiProvider.review(context))
                    .get(hardTimeoutSeconds, TimeUnit.SECONDS);
        } catch (ExecutionException execution) {
            Throwable cause = execution.getCause();
            if (cause instanceof CandidateAiProvider.ProviderException providerException) throw providerException;
            throw new CandidateAiProvider.ProviderException("AI_PROVIDER_ERROR", 502);
        } catch (TimeoutException timeout) {
            throw new CandidateAiProvider.ProviderException("AI_PROVIDER_TIMEOUT", 504);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new CandidateAiProvider.ProviderException("AI_PROVIDER_ERROR", 502);
        }
    }

    private int aiProviderHardTimeoutSeconds() {
        try {
            return Integer.parseInt(System.getenv().getOrDefault("DISPATCH_AI_TIMEOUT_SECONDS", "45"));
        } catch (NumberFormatException ignored) {
            return 45;
        }
    }

    /**
     * @brief 定稿影响预览：这次定稿会替代哪些已定稿方案。只读，不改库。
     *
     * @details 与 {@code POST /{id}/publish} 共用 {@link SchedulePlanEntity#supersedeTargets}，
     * 所以弹窗里的数字永远等于真正发生的事——前端不再自己按时间窗重叠估算。
     * 返回 {@code targets} 为空 = 定稿不会影响任何其他方案（不同订单的草案各管各的）。
     */
    @GET
    @Path("/{id}/supersede-preview")
    public Response supersedePreview(@PathParam("id") int id, @QueryParam("structureId") int structureId) {
        SchedulePlanEntity plan = plan(id, structureId);
        if (plan == null) return ApiErrors.notFound("SCHEDULE_PLAN_NOT_FOUND");
        List<Map<String, Object>> targets = new ArrayList<>();
        for (SchedulePlanEntity old : SchedulePlanEntity.supersedeTargets(structureId, plan)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", old.id);
            row.put("name", old.name);
            row.put("windowStart", old.windowStart);
            row.put("windowEnd", old.windowEnd);
            row.put("orderCodes", SchedulePlanEntity.coveredOrderCodes(old.id));
            targets.add(row);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("planId", plan.id);
        body.put("status", plan.status);
        body.put("orderCodes", SchedulePlanEntity.coveredOrderCodes(plan.id));
        body.put("targets", targets);
        return Response.ok(body).build();
    }

    /**
     * @brief 定稿：把方案里的分配写进班次并封存快照（DRAFT → EXECUTED）。
     *
     * @details 这是方案唯一的"产生后果"动作，等价于「保存结果」。审批已上移到订单层
     * （{@code POST /audit-orders/{id}/approve}），方案层不再有提交/批准/退回，
     * 因此状态机收敛为 DRAFT → EXECUTED，外加 SUPERSEDED（被更新的定稿替代）。
     *
     * <p>定稿后：班次写入 employee_id（此刻生效）、旧的重叠方案标为 SUPERSEDED、
     * 快照保留当时的人员安排供追溯。求解器保存结果时也会调用同一段逻辑。</p>
     */
    @POST
    @Path("/{id}/publish")
    @RolesAllowed({"ADMIN", "APPROVER", "DISPATCHER"})
    @Transactional
    public Response publish(@PathParam("id") int id, @QueryParam("structureId") int structureId) {
        SchedulePlanEntity plan = plan(id, structureId);
        if (plan == null) return ApiErrors.notFound("SCHEDULE_PLAN_NOT_FOUND");
        if (!"DRAFT".equals(plan.status)) return ApiErrors.conflict("SCHEDULE_PLAN_TRANSITION_INVALID");
        Response validationFailure = validateReadyForReview(plan);
        if (validationFailure != null) return validationFailure;
        List<SchedulePlanAssignmentEntity> rows = assignments(plan.id);
        SafetyBackupOutcome backup = backupService.safetyBackup("prepublish");
        if (!backup.isOk()) return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                .entity(Map.of("error", backup.errorCode())).build();

        for (SchedulePlanAssignmentEntity row : rows) {
            ShiftEntity shift = ShiftEntity.findById(row.shiftId);
            shift.employeeId = row.employeeId;
            shift.pinned = row.locked;
            shift.publishedPlanId = plan.id;
        }
        // The writes above bump shift.version (optimistic locking). Flush so the incremented
        // versions materialize, then rewind each snapshot to the new version — otherwise every
        // row of the plan just published would read as SCHEDULE_PLAN_STALE ("订单阶段已被修改").
        SchedulePlanAssignmentEntity.getEntityManager().flush();
        for (SchedulePlanAssignmentEntity row : rows) {
            ShiftEntity synced = ShiftEntity.findById(row.shiftId);
            row.shiftVersion = synced.version;
        }
        List<SchedulePlanEntity> previous = SchedulePlanEntity.supersedeOverlapping(structureId, plan, WorkflowSupport.now());
        plan.status = "EXECUTED";
        plan.publishedBy = actor();
        plan.publishedAt = plan.updatedAt = WorkflowSupport.now();
        auditTrail.record(structureId, "SCHEDULE_PLAN_FINALIZED", "SCHEDULE_PLAN", plan.id,
                Map.of("assignments", rows.size(), "superseded", previous.stream().map(old -> old.id).toList()));
        emit(plan, "finalized");
        return Response.ok(planDto(plan, true)).build();
    }

    /** Deletes an abandoned draft together with its assignment snapshot.
     *  Only DRAFT is removable: an EXECUTED plan documents what actually took effect,
     *  and a superseded one documents what was in force before — both must stay traceable. */
    @DELETE
    @Path("/{id}")
    @RolesAllowed({"ADMIN", "CAPOSALA", "DISPATCHER"})
    @Transactional
    public Response delete(@PathParam("id") int id, @QueryParam("structureId") int structureId) {
        SchedulePlanEntity plan = plan(id, structureId);
        if (plan == null) return ApiErrors.notFound("SCHEDULE_PLAN_NOT_FOUND");
        if (!"DRAFT".equals(plan.status)) return ApiErrors.conflict("SCHEDULE_PLAN_DELETE_NOT_ALLOWED");
        long assignmentCount = SchedulePlanAssignmentEntity.delete("planId = ?1", plan.id);
        auditTrail.record(structureId, "SCHEDULE_PLAN_DELETED", "SCHEDULE_PLAN", plan.id,
                Map.of("name", plan.name, "revision", plan.revision, "assignments", assignmentCount));
        plan.delete();
        emit(plan, "deleted");
        return Response.ok(Map.of("deleted", true)).build();
    }

    private void snapshotAssignments(SchedulePlanEntity plan, List<ShiftEntity> shifts) {
        snapshotAssignments(plan, shifts, true);
    }

    private void snapshotAssignments(SchedulePlanEntity plan, List<ShiftEntity> shifts, boolean preserveAssignments) {
        Map<Integer, Integer> phaseSlots = new LinkedHashMap<>();
        for (ShiftEntity shift : shifts) {
            SchedulePlanAssignmentEntity row = new SchedulePlanAssignmentEntity();
            row.planId = plan.id;
            row.shiftId = shift.id;
            row.phaseId = shift.auditOrderPhaseId;
            row.slotIndex = shift.auditOrderPhaseId == null ? 1
                    : phaseSlots.merge(shift.auditOrderPhaseId, 1, Integer::sum);
            row.employeeId = preserveAssignments ? shift.employeeId : null;
            row.locked = preserveAssignments && shift.pinned;
            row.assignmentRole = shift.requiredRole == null ? "AUDITOR" : shift.requiredRole;
            row.riskCode = row.employeeId == null ? "UNASSIGNED"
                    : assignmentRisk(shift.id, row.employeeId, plan.id, null);
            row.shiftVersion = shift.version;
            EmployeeEntity employee = row.employeeId == null ? null : EmployeeEntity.findById(row.employeeId);
            row.employeeStatusUpdatedAt = employee == null || employee.statusUpdatedAt == null ? "" : employee.statusUpdatedAt;
            row.persist();
        }
    }

    private Map<String, Object> planDto(SchedulePlanEntity plan, boolean includeAssignments) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("id", plan.id); dto.put("structureId", plan.structureId); dto.put("name", plan.name);
        dto.put("windowStart", plan.windowStart); dto.put("windowEnd", plan.windowEnd); dto.put("status", plan.status);
        dto.put("revision", plan.revision); dto.put("parentPlanId", plan.parentPlanId); dto.put("solverScore", plan.solverScore);
        dto.put("source", plan.source == null || plan.source.isBlank() ? "WINDOW" : plan.source);
        dto.put("createdBy", plan.createdBy); dto.put("submittedBy", plan.submittedBy); dto.put("approvedBy", plan.approvedBy);
        dto.put("publishedBy", plan.publishedBy); dto.put("rejectionReason", plan.rejectionReason);
        dto.put("supersededByPlanId", plan.supersededByPlanId);
        dto.put("createdAt", plan.createdAt); dto.put("updatedAt", plan.updatedAt); dto.put("publishedAt", plan.publishedAt);
        List<SchedulePlanAssignmentEntity> rows = assignments(plan.id);
        // Superseded plans are immutable history and cannot be acted on: live risk signals there
        // are noise (every later publish over the same shifts bumps shift versions, which would
        // otherwise mark every historical row as SCHEDULE_PLAN_STALE forever).
        Map<Integer, String> currentRisks = "SUPERSEDED".equals(plan.status) ? Map.of()
                : rows.stream().collect(Collectors.toMap(row -> row.id,
                        row -> currentAssignmentRisk(plan.id, row),
                        (left, right) -> left, LinkedHashMap::new));
        dto.put("assignmentCount", rows.size());
        dto.put("gapCount", rows.stream().filter(row -> row.employeeId == null).count());
        dto.put("riskCount", rows.stream().filter(row -> row.employeeId != null
                && !currentRisks.getOrDefault(row.id, "").isBlank()).count());
        if (includeAssignments) dto.put("assignments", rows.stream()
                .map(row -> assignmentDto(row, currentRisks.getOrDefault(row.id, ""))).toList());
        return dto;
    }

    /**
     * @brief 其他**草案**里已锁定、且与本班次时间重叠的人员（只读提示，不参与判定）。
     *
     * @details 草案里的「选择并锁定」只写方案快照、不动 {@code shifts}，而候选筛选只认
     *          {@code shifts}——于是 A 草案锁了某人，B 草案查候选时他仍然显示"可调度"，
     *          两份草案都定稿时第二份才炸 TIME_OVERLAP。这里把这类预占捞出来交给前端标注。
     *
     *          只查 DRAFT：EXECUTED 已落库（候选会直接判重叠），SUPERSEDED 是历史存档。
     *          只查 locked：未锁定的是"暂存"，随时会改，提示出来只是噪音。
     *          只做提示不拦截：草案是半成品，可能是别人随手建的或准备废弃的，
     *          直接把人从候选池里剔除会让调度员找不到人却不知道为什么。
     */
    private Map<Integer, Map<String, Object>> draftPreemptions(int structureId, ShiftEntity target,
            Integer excludePlanId) {
        List<Integer> draftPlanIds = SchedulePlanEntity.<SchedulePlanEntity>find(
                        "structureId = ?1 and status = 'DRAFT'", structureId).list()
                .stream().map(plan -> plan.id)
                .filter(planId -> excludePlanId == null || !planId.equals(excludePlanId))
                .toList();
        if (draftPlanIds.isEmpty()) return Map.of();

        Map<Integer, Map<String, Object>> preemptions = new LinkedHashMap<>();
        for (SchedulePlanAssignmentEntity row : SchedulePlanAssignmentEntity
                .<SchedulePlanAssignmentEntity>find("locked = true and employeeId is not null and planId in ?1",
                        draftPlanIds).list()) {
            if (preemptions.containsKey(row.employeeId)) continue;
            ShiftEntity other = ShiftEntity.findById(row.shiftId);
            if (other == null) continue;
            // 时间列是固定格式文本，字典序与时间序一致，可直接字符串比较。
            if (target.startTime.compareTo(other.endTime) >= 0 || target.endTime.compareTo(other.startTime) <= 0)
                continue;
            SchedulePlanEntity planning = SchedulePlanEntity.findById(row.planId);
            if (planning == null) continue;
            preemptions.put(row.employeeId, Map.of(
                    "planId", planning.id, "planName", planning.name,
                    "startTime", other.startTime, "endTime", other.endTime));
        }
        return preemptions;
    }

    private Map<String, Object> assignmentDto(SchedulePlanAssignmentEntity row) {
        String risk = row.employeeId == null ? "UNASSIGNED" : row.riskCode;
        return assignmentDto(row, risk);
    }

    private Map<String, Object> assignmentDto(SchedulePlanAssignmentEntity row, String risk) {
        Map<String, Object> dto = new LinkedHashMap<>();
        ShiftEntity shift = ShiftEntity.findById(row.shiftId);
        EmployeeEntity employee = row.employeeId == null ? null : EmployeeEntity.findById(row.employeeId);
        dto.put("id", row.id); dto.put("shiftId", row.shiftId); dto.put("phaseId", row.phaseId);
        dto.put("slotIndex", row.slotIndex); dto.put("employeeId", row.employeeId);
        dto.put("employeeName", employee == null ? null : employee.firstName + " " + employee.lastName);
        dto.put("locked", row.locked); dto.put("assignmentRole", row.assignmentRole);
        dto.put("riskCode", risk); dto.put("changeReason", row.changeReason);
        dto.put("startTime", shift == null ? null : shift.startTime); dto.put("endTime", shift == null ? null : shift.endTime);
        dto.put("locationId", shift == null ? null : shift.locationId);
        dto.put("auditOrderCode", shift == null ? "" : shift.auditOrderCode);
        dto.put("auditStage", shift == null ? "" : shift.auditStage);
        return dto;
    }

    /** Rejects incomplete or stale plans before they enter or leave human review. */
    private Response validateReadyForReview(SchedulePlanEntity plan) {
        List<SchedulePlanAssignmentEntity> rows = assignments(plan.id);
        if (rows.isEmpty() || rows.stream().anyMatch(row -> row.employeeId == null))
            return ApiErrors.conflict("SCHEDULE_PLAN_HAS_GAPS");
        for (SchedulePlanAssignmentEntity row : rows) {
            ShiftEntity shift = ShiftEntity.findById(row.shiftId);
            EmployeeEntity employee = EmployeeEntity.findById(row.employeeId);
            if (shift == null || employee == null || shift.version != row.shiftVersion)
                return ApiErrors.conflict("SCHEDULE_PLAN_STALE");
            String revision = employee.statusUpdatedAt == null ? "" : employee.statusUpdatedAt;
            if (!revision.equals(row.employeeStatusUpdatedAt))
                return ApiErrors.conflict("EMPLOYEE_STATUS_CHANGED");
            String risk = assignmentRisk(row.shiftId, row.employeeId, plan.id, row.id);
            if (!risk.isBlank()) return Response.status(Response.Status.CONFLICT)
                    .entity(Map.of("error", "SCHEDULE_PLAN_INVALID", "assignmentId", row.id, "risk", risk)).build();
        }
        return null;
    }

    private String currentAssignmentRisk(int planId, SchedulePlanAssignmentEntity row) {
        if (row.employeeId == null) return "UNASSIGNED";
        ShiftEntity shift = ShiftEntity.findById(row.shiftId);
        EmployeeEntity employee = EmployeeEntity.findById(row.employeeId);
        if (shift == null || employee == null || shift.version != row.shiftVersion) return "SCHEDULE_PLAN_STALE";
        String revision = employee.statusUpdatedAt == null ? "" : employee.statusUpdatedAt;
        if (!revision.equals(row.employeeStatusUpdatedAt)) return "EMPLOYEE_STATUS_CHANGED";
        return assignmentRisk(row.shiftId, row.employeeId, planId, row.id);
    }

    private Map<String, Object> candidateDto(int planId, int assignmentId, ShiftEntity shift, EmployeeEntity employee,
            CandidateWeights weights, List<EmployeeSkillEntity> qualifications, List<Integer> requiredSkills,
            int plannedMinutes, Integer continuityEmployeeId, List<ShiftEntity> assignments) {
        String risk = assignmentRisk(shift.id, employee.id, planId, assignmentId);
        SchedulePlanAssignmentEntity row = SchedulePlanAssignmentEntity.findById(assignmentId);
        boolean currentAssignee = row != null && row.employeeId != null && row.employeeId.equals(employee.id);
        return scoredCandidateDto(risk, shift, employee, weights, qualifications, requiredSkills,
                plannedMinutes, continuityEmployeeId, planMinutesForEmployee(planId, employee.id), currentAssignee,
                assignments);
    }

    private Map<String, Object> directCandidateDto(ShiftEntity shift, EmployeeEntity employee,
            CandidateWeights weights, List<EmployeeSkillEntity> qualifications, List<Integer> requiredSkills,
            int plannedMinutes, Integer continuityEmployeeId, List<EmployeeDateEntity> employeeDates,
            List<ShiftEntity> existingAssignments) {
        String risk = directCandidateRisk(shift, employee, qualifications, requiredSkills,
                employeeDates, existingAssignments);
        boolean currentAssignee = shift.employeeId != null && shift.employeeId.equals(employee.id);
        return scoredCandidateDto(risk, shift, employee, weights, qualifications, requiredSkills,
                plannedMinutes, continuityEmployeeId, null, currentAssignee, existingAssignments);
    }

    private Map<String, Object> scoredCandidateDto(String risk, ShiftEntity shift, EmployeeEntity employee,
            CandidateWeights weights, List<EmployeeSkillEntity> qualifications, List<Integer> requiredSkills,
            int plannedMinutes, Integer continuityEmployeeId, Integer ownPlanMinutes, boolean currentAssignee,
            List<ShiftEntity> assignments) {
        LocalDate shiftDate = WorkflowSupport.parseDateTime(shift.startTime).toLocalDate();
        List<EmployeeSkillEntity> validQualifications = qualifications.stream()
                .filter(row -> qualificationUsableOn(row, shiftDate)).toList();
        long matchingQualifications = validQualifications.stream()
                .filter(row -> requiredSkills.contains(row.skillId)).count();
        boolean leadQualified = validQualifications.stream().anyMatch(this::isLeadQualification);
        double abilityScore = requiredSkills.isEmpty() ? 70.0
                : 70.0 * matchingQualifications / requiredSkills.size();
        abilityScore = Math.min(100.0, abilityScore + Math.min(20.0, validQualifications.size() * 5.0)
                + (leadQualified ? 10.0 : 0.0));

        // 出发地与硬约束同源：48 小时空窗期内按上一任务所在地，超过按常驻地。
        Departure departure = departureFor(employee, shift, assignments);
        int travelMinutes = departure.locationId() <= 0 ? 0 : travelMinutes(departure.locationId(), shift.locationId);
        double locationScore = locationScore(travelMinutes, locationScoreBaselineMinutes);
        double workloadHours = plannedMinutes / 60.0;
        double workloadScore = Math.max(0.0, 100.0 - Math.min(80.0, workloadHours) * 100.0 / 80.0);
        double continuityScore = continuityEmployeeId == null ? 50.0
                : continuityEmployeeId.equals(employee.id) ? 100.0 : 0.0;
        double ageScore = 50.0; // No age field yet: neutral and non-discriminating until real policy is approved.
        double totalScore = risk.isBlank() ? weights.total(abilityScore, locationScore, workloadScore,
                continuityScore, ageScore) : 0.0;

        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("employeeId", employee.id); dto.put("code", employee.code);
        dto.put("name", employee.firstName + " " + employee.lastName);
        dto.put("eligible", risk.isBlank()); dto.put("risk", risk);
        dto.put("currentAssignee", currentAssignee);
        dto.put("operationalStatus", employee.operationalStatus);
        dto.put("travelMinutes", travelMinutes);
        LocationEntity taskLocation = LocationEntity.findById(shift.locationId);
        String taskLocationName = taskLocation == null ? "未知地点" : taskLocation.name;
        LocationEntity currentLocation = employee.currentLocationId == null ? null
                : LocationEntity.findById(employee.currentLocationId);
        // currentLocationName 保持"常驻地"语义（前端用来显示「常驻 X」）；
        // 出发点另给一组字段，避免把"这次从哪出发"和"家在哪"混成一个值。
        String currentLocationName = currentLocation == null ? null : currentLocation.name;
        LocationEntity departureLocation = departure.locationId() <= 0 ? null
                : LocationEntity.findById(departure.locationId());
        String departureLocationName = departureLocation == null ? null : departureLocation.name;
        // 说明里写清"从哪出发、为什么是这里"，调度员才能核对这个位置假设是否符合实际。
        String originLabel = departure.fromPreviousTask()
                ? "从上一任务所在地 " + (departureLocationName == null ? "未知" : departureLocationName)
                        + " 出发（该任务 " + departure.previousEndTime() + " 结束，"
                        + departure.gapHours() + " 小时前，仍在 " + returnToBaseHours + " 小时返程空窗期内）"
                : "从常驻地 " + (departureLocationName == null ? "未设置" : departureLocationName) + " 出发";
        String locationDetail;
        if (departure.locationId() <= 0) {
            locationDetail = "未设置常驻地且无近期任务，转场按 0 分钟估算（补录位置可得更准确的转场评估）";
        } else if (travelMinutes == 0) {
            locationDetail = originLabel + "，与任务地点同城，转场 0 分钟";
        } else if (taskLocation == null || departureLocation == null || taskLocation.latitude == null
                || taskLocation.longitude == null || departureLocation.latitude == null
                || departureLocation.longitude == null) {
            locationDetail = originLabel + " → 任务地点 " + taskLocationName + "，坐标缺失按 120 分钟保守估算";
        } else {
            locationDetail = originLabel + " → 任务地点 " + taskLocationName
                    + "，预计转场 " + travelMinutes + " 分钟"
                    // 转场超过基准即扣满 0 分。不写清楚的话，「位置 0 分 + 2777 分钟」看起来像算错了。
                    + (travelMinutes >= locationScoreBaselineMinutes
                            ? "（已超过 " + round1(locationScoreBaselineMinutes / 60.0) + " 小时基准，位置分按 0 计）"
                            : "");
        }
        dto.put("taskLocationName", taskLocationName);
        dto.put("currentLocationName", currentLocationName);
        dto.put("departureLocationName", departureLocationName);
        dto.put("departureFromPreviousTask", departure.fromPreviousTask());
        dto.put("totalScore", round1(totalScore));
        dto.put("abilityScore", round1(abilityScore));
        dto.put("locationScore", round1(locationScore));
        dto.put("workloadScore", round1(workloadScore));
        dto.put("continuityScore", round1(continuityScore));
        dto.put("ageScore", round1(ageScore));
        dto.put("ageDataAvailable", false);
        dto.put("plannedHours", round1(workloadHours));
        // 评分理由同时给「人读的中文句子」和「结构化条目」：界面要中英双语，但句子里的
        // 数字与分支（同城/跨城/坐标缺失/超基准）由后端算，不能让前端再猜一遍。
        // reasons 保留给老调用方；reasonItems 是前端渲染的首选。
        Map<String, Object> abilityItem = new LinkedHashMap<>();
        abilityItem.put("code", "ability");
        abilityItem.put("score", round1(abilityScore));
        abilityItem.put("validCount", validQualifications.size());
        abilityItem.put("matched", matchingQualifications);
        abilityItem.put("required", requiredSkills.size());

        Map<String, Object> locationItem = new LinkedHashMap<>();
        locationItem.put("code", "location");
        locationItem.put("score", round1(locationScore));
        locationItem.put("noHome", departure.locationId() <= 0);
        locationItem.put("fromPreviousTask", departure.fromPreviousTask());
        locationItem.put("origin", departureLocationName);
        locationItem.put("task", taskLocationName);
        locationItem.put("minutes", travelMinutes);
        locationItem.put("sameCity", travelMinutes == 0);
        locationItem.put("missingCoords", taskLocation == null || departureLocation == null
                || taskLocation.latitude == null || taskLocation.longitude == null
                || departureLocation.latitude == null || departureLocation.longitude == null);
        locationItem.put("overBaseline", travelMinutes >= locationScoreBaselineMinutes);
        locationItem.put("baselineHours", round1(locationScoreBaselineMinutes / 60.0));
        locationItem.put("gapHours", departure.gapHours());
        locationItem.put("previousEnd", departure.previousEndTime());
        locationItem.put("returnToBaseHours", returnToBaseHours);

        Map<String, Object> workloadItem = new LinkedHashMap<>();
        workloadItem.put("code", "workload");
        workloadItem.put("score", round1(workloadScore));
        workloadItem.put("hours", round1(workloadHours));
        workloadItem.put("ownHours", ownPlanMinutes != null && ownPlanMinutes > 0
                ? round1(ownPlanMinutes / 60.0) : null);
        workloadItem.put("saturated", workloadHours >= 80);

        Map<String, Object> continuityItem = new LinkedHashMap<>();
        continuityItem.put("code", "continuity");
        continuityItem.put("score", round1(continuityScore));
        continuityItem.put("state", continuityEmployeeId == null ? "NONE"
                : continuityEmployeeId.equals(employee.id) ? "SAME" : "OTHER");

        Map<String, Object> ageItem = new LinkedHashMap<>();
        ageItem.put("code", "age");
        ageItem.put("score", round1(ageScore));

        dto.put("reasonItems", List.of(abilityItem, locationItem, workloadItem, continuityItem, ageItem));

        dto.put("reasons", List.of(
                "能力 " + round1(abilityScore) + "：有效资格 " + validQualifications.size()
                        + "，命中必需资格 " + matchingQualifications + "/" + requiredSkills.size(),
                "位置 " + round1(locationScore) + "：" + locationDetail,
                "空闲度 " + round1(workloadScore) + "：排班表已占用 " + round1(workloadHours) + " 小时（含历史与其他方案）"
                        + (ownPlanMinutes != null && ownPlanMinutes > 0
                                ? "，其中本方案 " + round1(ownPlanMinutes / 60.0) + " 小时" : "")
                        + (workloadHours >= 80 ? "，已达 80 小时基准按满载计" : ""),
                "连续性 " + round1(continuityScore) + "：" + (continuityEmployeeId == null
                        ? "无可继承人员，按中性分" : continuityEmployeeId.equals(employee.id) ? "延续同组人员" : "未延续同组人员"),
                "年龄 " + round1(ageScore) + "：尚未采集，按中性分且仅占5%"));
        return dto;
    }

    private Comparator<Map<String, Object>> candidateComparator() {
        return Comparator.<Map<String, Object>, Double>comparing(candidate -> (Double) candidate.get("totalScore"))
                .reversed()
                .thenComparingInt(candidate -> (Integer) candidate.get("travelMinutes"))
                .thenComparing(candidate -> (String) candidate.get("name"));
    }

    /** @brief 硬约束判定统一委托给 {@link AssignmentRiskService}，两处消费方共用同一套规则。 */
    private String directCandidateRisk(ShiftEntity target, EmployeeEntity employee,
            List<EmployeeSkillEntity> qualifications, List<Integer> requiredSkills,
            List<EmployeeDateEntity> employeeDates, List<ShiftEntity> existingAssignments) {
        return assignmentRiskService.directRisk(target, employee, qualifications, requiredSkills,
                employeeDates, existingAssignments);
    }

    private Integer directContinuityEmployeeId(String orderCode, AuditOrderPhaseEntity target, int slotIndex) {
        if (target.continuityGroup == null || target.continuityGroup.isBlank()) return null;
        List<AuditOrderPhaseEntity> related = AuditOrderPhaseEntity.<AuditOrderPhaseEntity>find(
                "auditOrderId = ?1 and continuityGroup = ?2 order by startTime, id",
                target.auditOrderId, target.continuityGroup).list();
        for (AuditOrderPhaseEntity phase : related) {
            if (phase.id.equals(target.id)) continue;
            List<ShiftEntity> slots = ShiftEntity.<ShiftEntity>find(
                    "auditOrderPhaseId = ?1 order by id", phase.id).list();
            if (slots.size() >= slotIndex && orderCode.equals(slots.get(slotIndex - 1).auditOrderCode)
                    && slots.get(slotIndex - 1).employeeId != null) return slots.get(slotIndex - 1).employeeId;
        }
        return null;
    }

    /**
     * Candidate workload for the in-plan modal. Global scope by decision (2026-10-15): the same
     * committed-hours base as the order-candidates view, so both lists rank consistently; the
     * plan's own draft rows (assigned but whose shifts are not yet materialized) are added on
     * top so in-plan balancing feedback survives before publishing. Rows whose shifts already
     * carry an employee are counted once, through the global pass.
     */
    private Map<Integer, Integer> modalPlannedMinutes(SchedulePlanEntity plan, int structureId) {
        Map<Integer, Integer> result = new LinkedHashMap<>();
        for (ShiftEntity assigned : ShiftEntity.<ShiftEntity>find(
                "employeeId is not null and locationId in (select l.id from LocationEntity l where l.structureId = ?1)",
                structureId).list()) {
            long minutes = Duration.between(WorkflowSupport.parseDateTime(assigned.startTime),
                    WorkflowSupport.parseDateTime(assigned.endTime)).toMinutes();
            result.merge(assigned.employeeId, (int) Math.max(0, minutes), Integer::sum);
        }
        for (SchedulePlanAssignmentEntity row : assignments(plan.id)) {
            if (row.employeeId == null) continue;
            ShiftEntity rowShift = ShiftEntity.findById(row.shiftId);
            if (rowShift == null) continue;
            // Skip only when the shift already carries THIS row's employee (counted by the global
            // pass). A different occupant (another plan holds the same shift) must not hide this
            // plan's own commitment.
            if (row.employeeId.equals(rowShift.employeeId)) continue;
            long minutes = Duration.between(WorkflowSupport.parseDateTime(rowShift.startTime),
                    WorkflowSupport.parseDateTime(rowShift.endTime)).toMinutes();
            result.merge(row.employeeId, (int) Math.max(0, minutes), Integer::sum);
        }
        return result;
    }

    /** How many minutes this plan itself allocates to one employee — display-only companion. */
    private int planMinutesForEmployee(int planId, int employeeId) {
        int total = 0;
        for (SchedulePlanAssignmentEntity row : assignments(planId)) {
            if (row.employeeId == null || row.employeeId != employeeId) continue;
            ShiftEntity rowShift = ShiftEntity.findById(row.shiftId);
            if (rowShift == null) continue;
            long minutes = Duration.between(WorkflowSupport.parseDateTime(rowShift.startTime),
                    WorkflowSupport.parseDateTime(rowShift.endTime)).toMinutes();
            total += (int) Math.max(0, minutes);
        }
        return total;
    }

    private Integer continuityEmployeeId(int planId, SchedulePlanAssignmentEntity assignment) {
        if (assignment.phaseId == null) return null;
        AuditOrderPhaseEntity target = AuditOrderPhaseEntity.findById(assignment.phaseId);
        if (target == null || target.continuityGroup == null || target.continuityGroup.isBlank()) return null;
        for (SchedulePlanAssignmentEntity other : assignments(planId)) {
            if (other.id.equals(assignment.id) || other.employeeId == null || other.phaseId == null
                    || other.slotIndex != assignment.slotIndex) continue;
            AuditOrderPhaseEntity phase = AuditOrderPhaseEntity.findById(other.phaseId);
            if (phase != null && target.continuityGroup.equals(phase.continuityGroup)) return other.employeeId;
        }
        return null;
    }

    private boolean qualificationUsableOn(EmployeeSkillEntity qualification, LocalDate date) {
        if (!Set.of("ACTIVE", "EXPIRING").contains(qualification.qualificationStatus == null
                ? "" : qualification.qualificationStatus.toUpperCase())) return false;
        LocalDate from = qualification.validFrom == null || qualification.validFrom.isBlank()
                ? null : LocalDate.parse(qualification.validFrom);
        LocalDate until = qualification.validUntil == null || qualification.validUntil.isBlank()
                ? null : LocalDate.parse(qualification.validUntil);
        return (from == null || !from.isAfter(date)) && (until == null || !until.isBefore(date));
    }

    private boolean isLeadQualification(EmployeeSkillEntity qualification) {
        String level = qualification.qualificationLevel == null ? "" : qualification.qualificationLevel.toUpperCase();
        return level.contains("LEAD") || level.contains("组长") || level.contains("主任");
    }

    private static double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    /**
     * @brief 位置分：同城 100，转场达到基准即 0，中间线性。
     *
     * @details 抽成静态方法是为了能被测试直接钉住——这条公式的标定曾经写死成 240 分钟，
     *          导致跨城候选全是 0 分（见 {@code LocationScoreTest}）。
     *          基准由 {@code app.dispatch.location-score-baseline-minutes} 给出，默认 2880（48 小时）。
     */
    static double locationScore(int travelMinutes, int baselineMinutes) {
        if (baselineMinutes <= 0) return travelMinutes <= 0 ? 100.0 : 0.0;
        return Math.max(0.0, 100.0 - Math.min(baselineMinutes, Math.max(0, travelMinutes))
                * 100.0 / baselineMinutes);
    }

    /** Removes names, contact details and certificates before candidate data leaves the company system. */
    private Map<String, Object> aiCandidateContext(Map<String, Object> source) {
        Map<String, Object> context = new LinkedHashMap<>();
        for (String key : List.of("orderId", "orderCode", "priority", "topK", "weights",
                "weightVersion", "weightSource")) context.put(key, source.get(key));
        List<Map<String, Object>> sanitizedPhases = new ArrayList<>();
        for (Map<String, Object> phase : mapList(source.get("phases"))) {
            Map<String, Object> sanitizedPhase = new LinkedHashMap<>();
            for (String key : List.of("phaseId", "phaseCode", "phaseName", "startTime", "endTime",
                    "locationId", "requiredHeadcount", "leadRequired")) sanitizedPhase.put(key, phase.get(key));
            List<Map<String, Object>> sanitizedSlots = new ArrayList<>();
            for (Map<String, Object> slot : mapList(phase.get("slots"))) {
                Map<String, Object> sanitizedSlot = new LinkedHashMap<>();
                for (String key : List.of("shiftId", "slotIndex", "requiredRole", "requiredSkillIds"))
                    sanitizedSlot.put(key, slot.get(key));
                List<Map<String, Object>> sanitizedCandidates = new ArrayList<>();
                for (Map<String, Object> candidate : mapList(slot.get("candidates"))) {
                    Map<String, Object> sanitized = new LinkedHashMap<>();
                    for (String key : List.of("employeeId", "code", "operationalStatus", "rank",
                            "totalScore", "abilityScore", "locationScore", "workloadScore",
                            "continuityScore", "ageScore", "ageDataAvailable", "plannedHours",
                            "travelMinutes", "reasons", "reasonItems")) sanitized.put(key, candidate.get(key));
                    sanitizedCandidates.add(sanitized);
                }
                sanitizedSlot.put("candidates", sanitizedCandidates); sanitizedSlots.add(sanitizedSlot);
            }
            sanitizedPhase.put("slots", sanitizedSlots); sanitizedPhases.add(sanitizedPhase);
        }
        context.put("phases", sanitizedPhases);
        return context;
    }

    static boolean validAiReview(CandidateAiProvider.AiReview review, Map<String, Object> context) {
        if (review == null || review.recommendations() == null || review.warnings() == null
                || review.overallSummary() == null) return false;
        Map<Integer, Set<Integer>> allowed = new LinkedHashMap<>();
        for (Map<String, Object> phase : mapList(context.get("phases"))) {
            for (Map<String, Object> slot : mapList(phase.get("slots"))) {
                Integer shiftId = integer(slot.get("shiftId"));
                if (shiftId == null) return false;
                Set<Integer> employees = mapList(slot.get("candidates")).stream()
                        .map(candidate -> integer(candidate.get("employeeId")))
                        .filter(java.util.Objects::nonNull).collect(Collectors.toSet());
                allowed.put(shiftId, employees);
            }
        }
        Set<Integer> shiftsWithCandidates = allowed.entrySet().stream().filter(entry -> !entry.getValue().isEmpty())
                .map(Map.Entry::getKey).collect(Collectors.toSet());
        Set<Integer> recommendedShifts = new java.util.HashSet<>();
        Set<String> uniqueRanks = new java.util.HashSet<>();
        for (CandidateAiProvider.Recommendation recommendation : review.recommendations()) {
            if (recommendation == null || recommendation.aiRank() < 1 || recommendation.aiRank() > 3
                    || recommendation.confidence() < 0 || recommendation.confidence() > 100
                    || recommendation.rationale() == null || recommendation.rationale().isBlank()
                    || recommendation.cautions() == null
                    || !allowed.getOrDefault(recommendation.shiftId(), Set.of()).contains(recommendation.employeeId())
                    || !uniqueRanks.add(recommendation.shiftId() + ":" + recommendation.aiRank())) return false;
            recommendedShifts.add(recommendation.shiftId());
        }
        return recommendedShifts.containsAll(shiftsWithCandidates);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> mapList(Object value) {
        return value instanceof List<?> list ? (List<Map<String, Object>>) (List<?>) list : List.of();
    }

    private static Integer integer(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }

    private CandidateWeights candidateWeights(int structureId, Integer ability, Integer location,
            Integer workload, Integer continuity, Integer age) {
        CandidateWeightProfileEntity profile = CandidateWeightResource.latest(structureId);
        int baseAbility = profile == null ? CandidateWeightResource.DEFAULT_ABILITY : profile.abilityWeight;
        int baseLocation = profile == null ? CandidateWeightResource.DEFAULT_LOCATION : profile.locationWeight;
        int baseWorkload = profile == null ? CandidateWeightResource.DEFAULT_WORKLOAD : profile.workloadWeight;
        int baseContinuity = profile == null ? CandidateWeightResource.DEFAULT_CONTINUITY : profile.continuityWeight;
        int baseAge = profile == null ? CandidateWeightResource.DEFAULT_AGE : profile.ageWeight;
        boolean override = ability != null || location != null || workload != null || continuity != null || age != null;
        return new CandidateWeights(ability == null ? baseAbility : ability,
                location == null ? baseLocation : location, workload == null ? baseWorkload : workload,
                continuity == null ? baseContinuity : continuity, age == null ? baseAge : age,
                profile == null ? 0 : profile.profileVersion,
                override ? "QUERY_OVERRIDE" : profile == null ? "DEFAULT" : "PROFILE");
    }

    private record CandidateWeights(int ability, int location, int workload, int continuity, int age,
            int version, String source) {
        boolean valid() {
            return ability >= 0 && location >= 0 && workload >= 0 && continuity >= 0 && age >= 0
                    && ability + location + workload + continuity + age > 0;
        }

        double total(double abilityScore, double locationScore, double workloadScore,
                double continuityScore, double ageScore) {
            int totalWeight = ability + location + workload + continuity + age;
            return (abilityScore * ability + locationScore * location + workloadScore * workload
                    + continuityScore * continuity + ageScore * age) / totalWeight;
        }
    }

    private String assignmentRisk(int shiftId, int employeeId, int planId, Integer ignoredAssignmentId) {
        ShiftEntity shift = ShiftEntity.findById(shiftId);
        EmployeeEntity employee = EmployeeEntity.findById(employeeId);
        if (shift == null || employee == null) return "EMPLOYEE_INACTIVE";
        LocalDateTime start = WorkflowSupport.parseDateTime(shift.startTime);
        LocalDateTime end = WorkflowSupport.parseDateTime(shift.endTime);
        List<Integer> required = ShiftSkillEntity.<ShiftSkillEntity>list("shiftId = ?1 and skillTypeId = 1", shiftId)
                .stream().map(link -> link.skillId).toList();
        List<EmployeeSkillEntity> qualifications = EmployeeSkillEntity.<EmployeeSkillEntity>list("employeeId", employeeId);
        String directRisk = directCandidateRisk(shift, employee, qualifications, required,
                EmployeeDateEntity.<EmployeeDateEntity>list("employeeId", employeeId),
                ShiftEntity.<ShiftEntity>list("employeeId", employeeId));
        if (!directRisk.isBlank()) return directRisk;
        for (SchedulePlanAssignmentEntity other : assignments(planId)) {
            if (other.employeeId == null || other.employeeId != employeeId
                    || (ignoredAssignmentId != null && other.id.equals(ignoredAssignmentId))) continue;
            ShiftEntity otherShift = ShiftEntity.findById(other.shiftId);
            if (otherShift == null) continue;
            LocalDateTime otherStart = WorkflowSupport.parseDateTime(otherShift.startTime);
            LocalDateTime otherEnd = WorkflowSupport.parseDateTime(otherShift.endTime);
            if (start.isBefore(otherEnd) && end.isAfter(otherStart)) return "TIME_OVERLAP";
        }
        return "";
    }

    private int travelMinutes(EmployeeEntity employee, ShiftEntity shift) {
        return employee.currentLocationId == null ? 0 : travelMinutes(employee.currentLocationId, shift.locationId);
    }

    /**
     * @brief 候选人的出发地及其依据。
     *
     * @param locationId         出发地地点 id；0 表示未知（未登记常驻地且无近期任务）。
     * @param fromPreviousTask   true = 沿用上一任务所在地（两天内的往返假设内）；false = 常驻地。
     * @param previousEndTime    上一任务的结束时间，仅 fromPreviousTask 时非空。
     * @param gapHours           上一任务结束到目标班次开始的小时数，仅 fromPreviousTask 时有效。
     */
    private record Departure(int locationId, boolean fromPreviousTask, String previousEndTime, long gapHours) {
    }

    /**
     * @brief 判定出发地：任务结束两天内按任务所在地，超过按常驻地。
     *
     * @details 系统没有实时定位，"人在哪"只能推断。业务规则是：
     *          完成一个任务后给 {@code app.dispatch.return-to-base-hours}（默认 48 小时）的空窗期，
     *          期内认为人还在任务所在地，之后认为已返回常驻地。
     *          取"目标班次开始前最后结束的那条班次"作为上一任务——更早结束的班次
     *          时间余量只会更大，不可能更严格。
     */
    private Departure departureFor(EmployeeEntity employee, ShiftEntity target, List<ShiftEntity> assignments) {
        ShiftEntity previous = latestFinishedBefore(target, assignments);
        if (previous != null) {
            LocalDateTime end = WorkflowSupport.parseDateTime(previous.endTime);
            LocalDateTime start = WorkflowSupport.parseDateTime(target.startTime);
            if (end != null && start != null) {
                long gapHours = Duration.between(end, start).toHours();
                if (gapHours <= returnToBaseHours) {
                    return new Departure(previous.locationId, true, previous.endTime, Math.max(0, gapHours));
                }
            }
        }
        return new Departure(employee.currentLocationId == null ? 0 : employee.currentLocationId,
                false, null, 0);
    }

    /** @brief 目标班次开始前最后结束的那条班次；更早结束的余量更大，不可能更严格。 */
    private static ShiftEntity latestFinishedBefore(ShiftEntity target, List<ShiftEntity> assignments) {
        ShiftEntity previous = null;
        for (ShiftEntity other : assignments) {
            if (other.id != null && other.id.equals(target.id)) continue;
            if (other.endTime.compareTo(target.startTime) > 0) continue;
            if (previous == null || other.endTime.compareTo(previous.endTime) > 0) previous = other;
        }
        return previous;
    }

    private int travelMinutes(int fromLocationId, int toLocationId) {
        TravelMatrix.ensure();
        Integer cached = TravelMatrix.get(fromLocationId, toLocationId);
        if (cached != null) return cached;
        return TravelMatrix.fallback(fromLocationId, toLocationId);
    }

    private List<ShiftEntity> shiftsForWindow(int structureId, String start, String end) {
        return ShiftEntity.<ShiftEntity>find(
                "locationId in (select l.id from LocationEntity l where l.structureId = ?1) and startTime < ?2 and endTime > ?3 order by startTime, id",
                structureId, end, start).list();
    }

    private static SchedulePlanAssignmentEntity copyAssignment(SchedulePlanAssignmentEntity source, int planId) {
        SchedulePlanAssignmentEntity copy = new SchedulePlanAssignmentEntity();
        copy.planId = planId; copy.shiftId = source.shiftId; copy.phaseId = source.phaseId;
        copy.slotIndex = source.slotIndex; copy.employeeId = source.employeeId; copy.locked = source.locked;
        copy.assignmentRole = source.assignmentRole; copy.riskCode = source.riskCode;
        copy.changeReason = "Revision of plan " + source.planId; copy.shiftVersion = source.shiftVersion;
        copy.employeeStatusUpdatedAt = source.employeeStatusUpdatedAt;
        return copy;
    }

    private List<SchedulePlanAssignmentEntity> assignments(int planId) {
        return SchedulePlanAssignmentEntity.<SchedulePlanAssignmentEntity>find("planId = ?1 order by shiftId", planId).list();
    }

    private SchedulePlanEntity plan(int id, int structureId) {
        return SchedulePlanEntity.find("id = ?1 and structureId = ?2", id, structureId).firstResult();
    }

    private String actor() {
        return identity == null || identity.isAnonymous() ? "system" : identity.getPrincipal().getName();
    }

    private void emit(SchedulePlanEntity plan, String action) {
        events.publish("schedule-plan", Map.of("structureId", plan.structureId, "planId", plan.id,
                "status", plan.status, "action", action, "updatedAt", plan.updatedAt));
    }
}
