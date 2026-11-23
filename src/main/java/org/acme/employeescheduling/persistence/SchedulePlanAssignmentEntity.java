package org.acme.employeescheduling.persistence;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Immutable-at-publication assignment snapshot belonging to one schedule plan. */
@Entity
@Table(name = "schedule_plan_assignments")
public class SchedulePlanAssignmentEntity extends PanacheEntityBase {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Integer id;
    @Column(name = "plan_id", nullable = false) public int planId;
    @Column(name = "shift_id", nullable = false) public int shiftId;
    @Column(name = "phase_id") public Integer phaseId;
    @Column(name = "slot_index", nullable = false) public int slotIndex = 1;
    @Column(name = "employee_id") public Integer employeeId;
    @JdbcTypeCode(SqlTypes.INTEGER) @Column(nullable = false) public boolean locked;
    @Column(name = "assignment_role", nullable = false) public String assignmentRole = "AUDITOR";
    @Column(name = "risk_code", nullable = false) public String riskCode = "";
    @Column(name = "change_reason", nullable = false) public String changeReason = "";
    @Column(name = "shift_version", nullable = false) public int shiftVersion;
    @Column(name = "employee_status_updated_at", nullable = false) public String employeeStatusUpdatedAt = "";
}
