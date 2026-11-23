package org.acme.employeescheduling.persistence;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Append-only application audit trail. No update/delete endpoint is exposed. */
@Entity
@Table(name = "audit_events")
public class AuditEventEntity extends PanacheEntityBase {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Integer id;
    @Column(name = "structure_id") public Integer structureId;
    @Column(nullable = false) public String actor;
    @Column(nullable = false) public String action;
    @Column(name = "entity_type", nullable = false) public String entityType;
    @Column(name = "entity_id", nullable = false) public String entityId = "";
    @Column(name = "details_json", nullable = false) public String detailsJson = "{}";
    @Column(name = "created_at", nullable = false) public String createdAt;
}
