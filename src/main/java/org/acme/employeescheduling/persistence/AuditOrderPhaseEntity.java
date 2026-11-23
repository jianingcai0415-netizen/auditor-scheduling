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
import jakarta.persistence.Version;

/** One dated stage of an audit order. */
@Entity
@Table(name = "audit_order_phases")
public class AuditOrderPhaseEntity extends PanacheEntityBase {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Integer id;
    @Column(name = "audit_order_id", nullable = false) public int auditOrderId;
    @Column(name = "phase_code", nullable = false) public String phaseCode;
    @Column(name = "phase_name", nullable = false) public String phaseName;
    @Column(name = "start_time", nullable = false) public String startTime;
    @Column(name = "end_time", nullable = false) public String endTime;
    @Column(name = "location_id", nullable = false) public int locationId;
    @Column(name = "required_headcount", nullable = false) public int requiredHeadcount = 1;
    @JdbcTypeCode(SqlTypes.INTEGER) @Column(name = "lead_required", nullable = false) public boolean leadRequired;
    @Column(name = "continuity_group", nullable = false) public String continuityGroup = "";
    @Column(name = "delivery_mode", nullable = false) public String deliveryMode = "ONSITE";
    @Column(nullable = false) public String status = "PLANNED";
    @Column(name = "created_at", nullable = false) public String createdAt;
    @Column(name = "updated_at", nullable = false) public String updatedAt;
    @Version @JdbcTypeCode(SqlTypes.INTEGER) @Column(nullable = false) public int version;
}
