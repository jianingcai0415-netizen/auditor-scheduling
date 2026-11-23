package org.acme.employeescheduling.persistence;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Persisted preview token for a two-step XLSX import. */
@Entity
@Table(name = "import_jobs")
public class ImportJobEntity extends PanacheEntityBase {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Integer id;
    @Column(nullable = false, unique = true) public String token;
    @Column(name = "structure_id", nullable = false) public int structureId;
    @Column(name = "file_name", nullable = false) public String fileName;
    @Column(name = "file_sha256", nullable = false) public String fileSha256;
    @Column(nullable = false) public String status = "PREVIEW";
    @Column(name = "row_count", nullable = false) public int rowCount;
    @Column(name = "error_count", nullable = false) public int errorCount;
    @Column(name = "warning_count", nullable = false) public int warningCount;
    @Column(name = "summary_json", nullable = false) public String summaryJson = "{}";
    @Column(name = "payload_json", nullable = false) public String payloadJson = "{}";
    @Column(name = "errors_json", nullable = false) public String errorsJson = "[]";
    @Column(name = "created_by", nullable = false) public String createdBy;
    @Column(name = "created_at", nullable = false) public String createdAt;
    @Column(name = "confirmed_by") public String confirmedBy;
    @Column(name = "confirmed_at") public String confirmedAt;
}
