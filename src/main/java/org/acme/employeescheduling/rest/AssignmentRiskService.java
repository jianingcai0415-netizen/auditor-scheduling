package org.acme.employeescheduling.rest;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import org.acme.employeescheduling.persistence.EmployeeDateEntity;
import org.acme.employeescheduling.persistence.EmployeeEntity;
import org.acme.employeescheduling.persistence.EmployeeSkillEntity;
import org.acme.employeescheduling.persistence.ShiftEntity;
import org.acme.employeescheduling.persistence.ShiftSkillEntity;
import org.acme.employeescheduling.utils.TravelMatrix;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * @brief 单条「班次 × 审核员」的风险判定。
 * @details 抽成共享服务是因为它有两个消费方：排班方案（挑人 / 定稿）和订单详情（显示已排人员的
 *          实时风险）。两边必须用同一套规则，否则会出现「方案里说有风险、订单页说没问题」。
 *          规则与原来 SchedulePlanResource 里的实现逐条一致，未做任何放宽。
 */
@ApplicationScoped
public class AssignmentRiskService {

    /** 任务结束后多久之内仍按「上一任务所在地」算出发地（与候选打分共用）。 */
    @ConfigProperty(name = "app.dispatch.return-to-base-hours", defaultValue = "48")
    int returnToBaseHours;

    /**
     * @brief 按库里的现状算某个班次上某个人的风险。
     * @return 风险码；空串表示无风险。
     * @details 只能用于「已经写进班次」的分配（订单详情用）。方案里尚未落库的分配走 {@link #directRisk}。
     */
    public String riskOfShift(int shiftId, int employeeId) {
        ShiftEntity shift = ShiftEntity.findById(shiftId);
        EmployeeEntity employee = EmployeeEntity.findById(employeeId);
        if (shift == null || employee == null) return "EMPLOYEE_INACTIVE";
        List<Integer> required = ShiftSkillEntity.<ShiftSkillEntity>list("shiftId = ?1 and skillTypeId = 1", shiftId)
                .stream().map(link -> link.skillId).toList();
        return directRisk(shift, employee,
                EmployeeSkillEntity.<EmployeeSkillEntity>list("employeeId", employeeId), required,
                EmployeeDateEntity.<EmployeeDateEntity>list("employeeId", employeeId),
                ShiftEntity.<ShiftEntity>list("employeeId", employeeId));
    }

    /** @brief 硬约束判定：资格 / 等级 / 不可用 / 时间冲突 / 转场缓冲。 */
    public String directRisk(ShiftEntity target, EmployeeEntity employee,
            List<EmployeeSkillEntity> qualifications, List<Integer> requiredSkills,
            List<EmployeeDateEntity> employeeDates, List<ShiftEntity> existingAssignments) {
        if (!employee.active) return "EMPLOYEE_INACTIVE";
        LocalDateTime start = WorkflowSupport.parseDateTime(target.startTime);
        LocalDateTime end = WorkflowSupport.parseDateTime(target.endTime);
        if (employee.busyUntil != null && !employee.busyUntil.isBlank()) {
            LocalDateTime ready = WorkflowSupport.parseDateTime(employee.busyUntil)
                    .plusMinutes((long) employee.bufferAfterMinutes + travelMinutes(employee, target)
                            + employee.bufferBeforeMinutes);
            if (ready.isAfter(start)) return "EMPLOYEE_BUSY";
        } else if (employee.operationalStatus != null
                && !"AVAILABLE".equalsIgnoreCase(employee.operationalStatus)) {
            return "EMPLOYEE_BUSY";
        }
        for (Integer skillId : requiredSkills) {
            EmployeeSkillEntity qualification = qualifications.stream()
                    .filter(row -> row.skillId == skillId).findFirst().orElse(null);
            if (qualification == null) return "QUALIFICATION_MISSING";
            if (!qualificationUsableOn(qualification, start.toLocalDate())
                    || !qualificationUsableOn(qualification, end.toLocalDate())) return "QUALIFICATION_EXPIRED";
        }
        if ("LEAD_AUDITOR".equalsIgnoreCase(target.requiredRole)
                && qualifications.stream().filter(row -> qualificationUsableOn(row, start.toLocalDate()))
                        .noneMatch(this::isLeadQualification)) return "LEAD_QUALIFICATION_REQUIRED";
        for (EmployeeDateEntity date : employeeDates) {
            if (date.dateTypeId != EmployeeDateEntity.TYPE_UNAVAILABLE) continue;
            LocalDateTime unavailableStart = EmployeeDateEntity.parseDbDateTime(date.dateStart);
            LocalDateTime unavailableEnd = EmployeeDateEntity.parseDbDateTime(date.dateEnd);
            if (date.dateEnd != null && date.dateEnd.trim().length() == 10) unavailableEnd = unavailableEnd.plusDays(1);
            if (unavailableStart != null && unavailableEnd != null
                    && start.isBefore(unavailableEnd) && end.isAfter(unavailableStart)) return "EMPLOYEE_UNAVAILABLE";
        }
        for (ShiftEntity other : existingAssignments) {
            if (other.id == null || other.id.equals(target.id)) continue;
            LocalDateTime otherStart = WorkflowSupport.parseDateTime(other.startTime);
            LocalDateTime otherEnd = WorkflowSupport.parseDateTime(other.endTime);
            if (start.isBefore(otherEnd) && end.isAfter(otherStart)) return "TIME_OVERLAP";
            // 后向：做完本任务还要赶场去做 other，这段必须够时间（不受返程空窗期影响）。
            if (!end.isAfter(otherStart)) {
                LocalDateTime ready = end.plusMinutes((long) employee.bufferAfterMinutes
                        + travelMinutes(target.locationId, other.locationId) + employee.bufferBeforeMinutes);
                if (ready.isAfter(otherStart)) return "TRANSFER_BUFFER_INSUFFICIENT";
            }
        }
        // 前向：只检查最近一次结束的任务，且必须仍在"返程空窗期"内——
        // 超过空窗期就按常驻地出发，时间余量至少有 whole window，任何国内转场都够。
        ShiftEntity previous = latestFinishedBefore(target, existingAssignments);
        if (previous != null) {
            LocalDateTime previousEnd = WorkflowSupport.parseDateTime(previous.endTime);
            if (previousEnd != null && Duration.between(previousEnd, start).toHours() <= returnToBaseHours) {
                LocalDateTime ready = previousEnd.plusMinutes((long) employee.bufferAfterMinutes
                        + travelMinutes(previous.locationId, target.locationId) + employee.bufferBeforeMinutes);
                if (ready.isAfter(start)) return "TRANSFER_BUFFER_INSUFFICIENT";
            }
        }
        return "";
    }

    private int travelMinutes(EmployeeEntity employee, ShiftEntity shift) {
        return employee.currentLocationId == null ? 0 : travelMinutes(employee.currentLocationId, shift.locationId);
    }

    private int travelMinutes(int fromLocationId, int toLocationId) {
        TravelMatrix.ensure();
        Integer cached = TravelMatrix.get(fromLocationId, toLocationId);
        if (cached != null) return cached;
        return TravelMatrix.fallback(fromLocationId, toLocationId);
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
}
