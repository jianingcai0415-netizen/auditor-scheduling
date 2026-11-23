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

/** Persistent audit order owned by one structure. */
@Entity
@Table(name = "audit_orders")
public class AuditOrderEntity extends PanacheEntityBase {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Integer id;
    @Column(name = "structure_id", nullable = false) public int structureId;
    @Column(name = "order_code", nullable = false) public String orderCode;
    @Column(name = "customer_name", nullable = false) public String customerName = "";
    @Column(name = "project_name", nullable = false) public String projectName = "";
    @Column(nullable = false) public String status = "DRAFT";
    @Column(nullable = false) public int priority = 3;
    @Column(name = "owner_name", nullable = false) public String ownerName = "";
    @Column(nullable = false) public String notes = "";
    /**
     * Approval trail. The order is the approval unit: DRAFT → SUBMITTED → APPROVED / REJECTED.
     * Only an APPROVED order may be scheduled — the plan layer no longer carries an approval step,
     * because allocation is what takes effect (assignment = commit).
     */
    @Column(name = "submitted_at") public String submittedAt;
    @Column(name = "approved_at") public String approvedAt;
    @Column(name = "approved_by") public String approvedBy;
    @Column(name = "reject_reason", nullable = false) public String rejectReason = "";
    @Column(name = "created_at", nullable = false) public String createdAt;
    @Column(name = "updated_at", nullable = false) public String updatedAt;
    @Version @JdbcTypeCode(SqlTypes.INTEGER) @Column(nullable = false) public int version;
}
