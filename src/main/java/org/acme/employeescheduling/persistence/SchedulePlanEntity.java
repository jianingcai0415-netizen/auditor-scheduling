package org.acme.employeescheduling.persistence;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/** Versioned review/publish envelope around a schedule snapshot. */
@Entity
@Table(name = "schedule_plans")
public class SchedulePlanEntity extends PanacheEntityBase {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Integer id;
    @Column(name = "structure_id", nullable = false) public int structureId;
    @Column(nullable = false) public String name;
    @Column(name = "window_start", nullable = false) public String windowStart;
    @Column(name = "window_end", nullable = false) public String windowEnd;
    @Column(nullable = false) public String status = "DRAFT";
    @Column(nullable = false) public int revision = 1;
    @Column(name = "parent_plan_id") public Integer parentPlanId;
    /** How this plan came to exist: WINDOW (date-window snapshot), ORDER (per-order draft), REVISION (copy of an existing plan). */
    @Column(nullable = false) public String source = "WINDOW";
    @Column(name = "solver_score") public String solverScore;
    @Column(name = "created_by", nullable = false) public String createdBy;
    @Column(name = "submitted_by") public String submittedBy;
    @Column(name = "approved_by") public String approvedBy;
    @Column(name = "published_by") public String publishedBy;
    @Column(name = "rejection_reason", nullable = false) public String rejectionReason = "";
    /** Id of the plan whose publication superseded this one, if any. The successor only
     *  overwrites the shifts inside the window overlap: assignments the older plan placed
     *  outside the overlap keep running, so the UI needs the successor id to tell a full
     *  replacement from a partial one instead of treating SUPERSEDED as "inactive". */
    @Column(name = "superseded_by_plan_id") public Integer supersededByPlanId;
    @Column(name = "created_at", nullable = false) public String createdAt;
    @Column(name = "updated_at", nullable = false) public String updatedAt;
    @Column(name = "submitted_at") public String submittedAt;
    @Column(name = "approved_at") public String approvedAt;
    @Column(name = "published_at") public String publishedAt;
    @Version @JdbcTypeCode(SqlTypes.INTEGER) @Column(nullable = false) public int version;

    /**
     * @brief 把「分配到同一批班次」的已定稿方案标记为 SUPERSEDED。
     *
     * @details 两条落库路径共用同一段替代逻辑：手动定稿（{@code POST /schedule-plans/{id}/publish}）
     * 和求解器保存结果。只有一处定义，才不会出现"一套流程标了替代、另一套没标"的不一致。
     *
     * <p><b>什么才算"覆盖"</b>：定稿只写<b>本方案自己那些班次</b>的 {@code employee_id}。
     * 所以两个方案互为版本，当且仅当它们<b>分配到同一个班次</b>——只有这时后定稿的那份真的会盖掉
     * 前一份写进去的人。不同订单本来就该在同一时间由不同的人同时干活，哪怕时间窗完全重合，
     * 也各写各的班次，谈不上谁覆盖谁。</p>
     *
     * <p>这条规则一路上收紧过两次，都是因为判据选得比实际影响更宽：</p>
     * <ul>
     *   <li>最早按"时间窗重叠"判 —— 同月不同订单互相取代（2026-10-28 实测 6 份方案只剩 2 份
     *       EXECUTED，其余 4 份的班次一条都没少）；</li>
     *   <li>后来改成"覆盖同一张订单" —— 真实但仍是代理指标，订单→班次不是一一对应；</li>
     *   <li>现在直接按<b>班次交集</b>判 —— 就是"会不会真的覆盖"本身，不再有代理。</li>
     * </ul>
     *
     * @param now 调用方传入的时间串，避免实体层反向依赖 REST 工具类。
     * @return 被标记为 SUPERSEDED 的方案，供调用方写入审计。
     */
    public static List<SchedulePlanEntity> supersedeOverlapping(int structureId, SchedulePlanEntity plan, String now) {
        List<SchedulePlanEntity> previous = supersedeTargets(structureId, plan);
        for (SchedulePlanEntity old : previous) {
            old.status = "SUPERSEDED";
            old.supersededByPlanId = plan.id;
            old.updatedAt = now;
        }
        return previous;
    }

    /**
     * @brief 只<b>列出</b>定稿 {@code plan} 时会真正覆盖到哪些已定稿方案，不改库。
     *
     * @details 定稿确认弹窗要在用户点「确认」之前把影响面说清楚。如果前端另写一套判断
     * （原来是"时间窗重叠就算"），后端改了判据而前端没跟着改，弹窗就会吓人一跳却什么也不发生——
     * 2026-10-29 实测正是如此：弹窗说「4 个已定稿方案将被替代」，实际一个都不会被替代。
     * 因此预览与执行<b>共用这一个方法</b>，不可能再漂移。
     *
     * @return 会因本次定稿而变成 SUPERSEDED 的方案（可能是空集——那表示本次定稿不影响任何其他方案）。
     */
    public static List<SchedulePlanEntity> supersedeTargets(int structureId, SchedulePlanEntity plan) {
        Set<Integer> newShifts = coveredShiftIds(plan.id);
        if (newShifts.isEmpty()) {
            // 没有分配 = 定稿一个班次也不会写，自然覆盖不到别人。
            return new ArrayList<>();
        }
        List<SchedulePlanEntity> previous = new ArrayList<>();
        // 时间窗重叠只是**预筛**：共享同一班次必然共享时间段，反过来不成立，
        // 所以先用窗口把候选缩小，再用班次交集判定（一屏范围通常只剩个位数）。
        List<SchedulePlanEntity> overlapping = find(
                "structureId = ?1 and status = 'EXECUTED' and id <> ?2 and windowStart < ?3 and windowEnd > ?4",
                structureId, plan.id, plan.windowEnd, plan.windowStart).list();
        for (SchedulePlanEntity old : overlapping) {
            if (!Collections.disjoint(newShifts, coveredShiftIds(old.id))) {
                previous.add(old);
            }
        }
        return previous;
    }

    /** @brief 方案分配到哪些班次；这是"会不会覆盖"的唯一判据。 */
    private static Set<Integer> coveredShiftIds(int planId) {
        Set<Integer> shiftIds = new HashSet<>();
        for (SchedulePlanAssignmentEntity row
                : SchedulePlanAssignmentEntity.<SchedulePlanAssignmentEntity>list("planId", planId)) {
            shiftIds.add(row.shiftId);
        }
        return shiftIds;
    }

    /** @brief 方案覆盖的订单编号，供预览接口展示"被取代的是哪张单的方案"。 */
    public static List<String> coveredOrderCodes(int planId) {
        List<String> codes = new ArrayList<>();
        for (Integer orderId : coveredOrderIds(planId)) {
            AuditOrderEntity order = AuditOrderEntity.findById(orderId);
            if (order != null && order.orderCode != null) {
                codes.add(order.orderCode);
            }
        }
        Collections.sort(codes);
        return codes;
    }

    /** @brief 该方案覆盖了哪几张审核订单；纯整窗方案（班次没挂阶段）返回空集。 */
    private static Set<Integer> coveredOrderIds(int planId) {
        Set<Integer> phaseIds = new HashSet<>();
        for (SchedulePlanAssignmentEntity row
                : SchedulePlanAssignmentEntity.<SchedulePlanAssignmentEntity>list("planId", planId)) {
            if (row.phaseId != null) {
                phaseIds.add(row.phaseId);
            }
        }
        Set<Integer> orderIds = new HashSet<>();
        if (phaseIds.isEmpty()) {
            return orderIds;
        }
        for (AuditOrderPhaseEntity phase : AuditOrderPhaseEntity.<AuditOrderPhaseEntity>list("id in ?1", phaseIds)) {
            orderIds.add(phase.auditOrderId);
        }
        return orderIds;
    }
}
