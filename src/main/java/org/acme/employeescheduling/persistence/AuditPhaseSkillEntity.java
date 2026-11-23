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

/** Qualification coverage required by an audit phase. */
@Entity
@Table(name = "audit_phase_skills")
public class AuditPhaseSkillEntity extends PanacheEntityBase {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Integer id;
    @Column(name = "phase_id", nullable = false) public int phaseId;
    @Column(name = "skill_id", nullable = false) public int skillId;
    @Column(name = "minimum_count", nullable = false) public int minimumCount = 1;
    @JdbcTypeCode(SqlTypes.INTEGER) @Column(name = "lead_only", nullable = false) public boolean leadOnly;
}
