package org.acme.employeescheduling.persistence;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * @brief JPA/Panache entity for the {@code employees} table.
 *
 * @details Actual schema: id PK AUTOINCREMENT, code TEXT UNIQUE, first/last_name TEXT,
 *          structure_id INTEGER default 1, active INTEGER 0/1, email TEXT default ''.
 *          Skills and dates live in the {@link EmployeeSkillEntity}/{@link EmployeeDateEntity}
 *          bridges; the Employee DTO is composed in the resource (its lists have different
 *          shapes for list versus detail views).
 */
@Entity
@Table(name = "employees")
public class EmployeeEntity extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Integer id;

    @Column(name = "code", nullable = false, unique = true)
    public String code;

    @Column(name = "first_name", nullable = false)
    public String firstName;

    @Column(name = "last_name", nullable = false)
    public String lastName;

    @Column(name = "structure_id", nullable = false)
    public int structureId = 1;

    /** INTEGER 0/1 in the DB (SQLite convention) — see SkillEntity. */
    @JdbcTypeCode(SqlTypes.INTEGER)
    @Column(name = "active", nullable = false)
    public boolean active = true;

    @Column(name = "email", nullable = false)
    public String email = "";

    @Column(name = "operational_status", nullable = false)
    public String operationalStatus = "AVAILABLE";

    @Column(name = "busy_until")
    public String busyUntil;

    @Column(name = "status_updated_at")
    public String statusUpdatedAt;

    @Column(name = "current_location_id")
    public Integer currentLocationId;

    @Column(name = "buffer_before_minutes", nullable = false)
    public int bufferBeforeMinutes = 30;

    @Column(name = "buffer_after_minutes", nullable = false)
    public int bufferAfterMinutes = 30;

    /**
     * @brief 简历附件文件名（含扩展名），null 表示尚未上传。
     *
     * @details 二进制不入库：附件存文件系统，这里只留引用（见 V25 迁移）。
     *          上传能力尚未开放，本期仅作为展示位与后续接口的落点。
     */
    @Column(name = "resume_file_name")
    public String resumeFileName;

    /** @brief 简历上传时间 {@code yyyy-MM-dd HH:mm:ss}，null 表示尚未上传。 */
    @Column(name = "resume_uploaded_at")
    public String resumeUploadedAt;

    /**
     * @brief 磁盘上的文件名（服务端生成），null 表示尚未上传（V26）。
     *
     * @details 绝不能用 {@link #resumeFileName} 当路径：那是用户提交的原始名，
     *          可能重名或含路径分隔符。实际位置是
     *          {@code {app.resume.dir}/{employeeId}/{resumeStoredName}}。
     */
    @Column(name = "resume_stored_name")
    public String resumeStoredName;
}
