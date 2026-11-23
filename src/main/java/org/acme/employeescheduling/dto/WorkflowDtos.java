package org.acme.employeescheduling.dto;

import java.util.ArrayList;
import java.util.List;

/** JSON payloads for the internal audit-order and schedule-plan workflow. */
public final class WorkflowDtos {
    private WorkflowDtos() { }

    public static class AuditOrderRequest {
        public String orderCode;
        public String customerName;
        public String projectName;
        public String status;
        public Integer priority;
        public String ownerName;
        public String notes;
    }

    public static class SkillRequirement {
        public int skillId;
        public int minimumCount = 1;
        public boolean leadOnly;
    }

    public static class AuditPhaseRequest {
        public String phaseCode;
        public String phaseName;
        public String startTime;
        public String endTime;
        public int locationId;
        public int requiredHeadcount = 1;
        public boolean leadRequired;
        public String continuityGroup;
        public String deliveryMode;
        public String status;
        public List<SkillRequirement> requiredSkills = new ArrayList<>();
    }

    public static class SchedulePlanRequest {
        public String name;
        public String windowStart;
        public String windowEnd;
        public Integer parentPlanId;
        public String solverScore;
    }

    public static class PlanAssignmentUpdate {
        public Integer employeeId;
        public boolean locked;
        public String assignmentRole;
        public String changeReason;
    }

    public static class TransitionRequest {
        public String reason;
    }

    /**
     * 把一个审核员直接派到订单下的某个名额（班次）。
     *
     * <p>用于「候选名单里点选择」和排班页「待分配」块的快捷派工：只动 <b>一个</b> 班次，
     * 不走方案流程（方案是可选的复查容器）。硬约束由服务端 {@code AssignmentRiskService} 重算一遍，
     * 不信任前端传来的名单。</p>
     */
    public static class ShiftAssignRequest {
        public Integer shiftId;
        public Integer employeeId;
        /** 派工理由，进审计；留空按「候选人名单选择」记录。 */
        public String reason;
    }

    public record ImportIssue(String sheet, int row, String field, String severity, String code,
            String message) { }

    public record ImportPreview(String token, String fileName, int rowCount, int errorCount,
            int warningCount, Object summary, List<ImportIssue> issues, boolean confirmable) { }
}
