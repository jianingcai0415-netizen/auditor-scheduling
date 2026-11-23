package org.acme.employeescheduling.dto;

import java.time.LocalDateTime;
import java.util.List;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * @brief Represents an employee in the scheduling system.
 *
 * @details This data transfer object holds all information about an employee,
 *          including
 *          personal details (name, code), skills, and date preferences
 *          (desired, undesired,
 *          and unavailable dates). It is used by the Timefold Solver as a
 *          problem fact
 *          to determine optimal shift assignments.
 *
 * @author acme
 * @version 1.0
 */

public class Employee {

    /** @brief Unique identifier for the employee. */
    @JsonProperty("id")
    private int id;

    /**
     * @brief Short code identifying the employee (e.g., badge number or employee
     *        code).
     */
    @JsonProperty("code")
    private String code;

    /** @brief Employee's first name. */
    @JsonProperty("firstName")
    private String firstName;

    /** @brief Employee's last name. */
    @JsonProperty("lastName")
    private String lastName;

    /** @brief Employee's email address. */
    @JsonProperty("email")
    private String email;

    /** @brief Employee's full name, composed from first and last name. */
    private String fullName;

    /** @brief List of skills possessed by the employee. */
    @JsonProperty("skills")
    private List<Skill> skills;

    /** @brief List of date intervals during which the employee is unavailable. */
    @JsonProperty("unavailableDates")
    private List<EmployeeDate> unavailableDates;

    /**
     * @brief List of date intervals during which the employee prefers not to work.
     */
    @JsonProperty("undesiredDates")
    private List<EmployeeDate> undesiredDates;

    /** @brief List of date intervals during which the employee prefers to work. */
    @JsonProperty("desiredDates")
    private List<EmployeeDate> desiredDates;

    /**
     * @brief If false, the employee is disabled: excluded from Shift Management and the
     *        solver.
     */
    @JsonProperty("active")
    private boolean active = true;

    /** Dated professional qualifications. Each record corresponds to one skill/scheme. */
    @JsonProperty("qualifications")
    private List<AuditorQualification> qualifications = List.of();

    /** Live operational state supplied by dispatch or an external workforce system. */
    @JsonProperty("operationalStatus")
    private String operationalStatus = "AVAILABLE";

    @JsonProperty("busyUntil")
    private LocalDateTime busyUntil;

    @JsonProperty("statusUpdatedAt")
    private LocalDateTime statusUpdatedAt;

    @JsonProperty("currentLocationId")
    private Integer currentLocationId;

    /** Coordinates are hydrated for solving and are not edited directly on an employee. */
    @JsonProperty("currentLatitude")
    private Double currentLatitude;

    @JsonProperty("currentLongitude")
    private Double currentLongitude;

    @JsonProperty("bufferBeforeMinutes")
    private int bufferBeforeMinutes = 30;

    @JsonProperty("bufferAfterMinutes")
    private int bufferAfterMinutes = 30;

    /** 简历附件文件名；null 或空表示尚未上传。上传接口本期未开放，仅占位展示。 */
    @JsonProperty("resumeFileName")
    private String resumeFileName;

    /** 简历上传时间 {@code yyyy-MM-dd HH:mm:ss}；与 resumeFileName 同时为空或同时有值。 */
    @JsonProperty("resumeUploadedAt")
    private String resumeUploadedAt;

    /**
     * @brief Default no-argument constructor.
     *
     * @details Required by Jackson for JSON deserialization.
     */
    public Employee() {
    }

    /** @brief Returns true if the employee is active. */
    public boolean isActive() {
        return active;
    }

    /** @brief Sets the active/disabled state. */
    public void setActive(boolean active) {
        this.active = active;
    }

    public List<AuditorQualification> getQualifications() { return qualifications; }
    public void setQualifications(List<AuditorQualification> qualifications) {
        this.qualifications = qualifications == null ? List.of() : qualifications;
    }

    public String getOperationalStatus() { return operationalStatus; }
    public void setOperationalStatus(String operationalStatus) { this.operationalStatus = operationalStatus; }

    public LocalDateTime getBusyUntil() { return busyUntil; }
    public void setBusyUntil(LocalDateTime busyUntil) { this.busyUntil = busyUntil; }

    public LocalDateTime getStatusUpdatedAt() { return statusUpdatedAt; }
    public void setStatusUpdatedAt(LocalDateTime statusUpdatedAt) { this.statusUpdatedAt = statusUpdatedAt; }

    public Integer getCurrentLocationId() { return currentLocationId; }
    public void setCurrentLocationId(Integer currentLocationId) { this.currentLocationId = currentLocationId; }

    public Double getCurrentLatitude() { return currentLatitude; }
    public void setCurrentLatitude(Double currentLatitude) { this.currentLatitude = currentLatitude; }

    public Double getCurrentLongitude() { return currentLongitude; }
    public void setCurrentLongitude(Double currentLongitude) { this.currentLongitude = currentLongitude; }

    public int getBufferBeforeMinutes() { return bufferBeforeMinutes; }
    public void setBufferBeforeMinutes(int bufferBeforeMinutes) { this.bufferBeforeMinutes = bufferBeforeMinutes; }

    public int getBufferAfterMinutes() { return bufferAfterMinutes; }
    public void setBufferAfterMinutes(int bufferAfterMinutes) { this.bufferAfterMinutes = bufferAfterMinutes; }

    public String getResumeFileName() { return resumeFileName; }
    public void setResumeFileName(String resumeFileName) { this.resumeFileName = resumeFileName; }

    public String getResumeUploadedAt() { return resumeUploadedAt; }
    public void setResumeUploadedAt(String resumeUploadedAt) { this.resumeUploadedAt = resumeUploadedAt; }

    /** True when a matching qualification covers the complete audit interval. */
    public boolean hasUsableQualification(int skillId, LocalDateTime start, LocalDateTime end) {
        if (qualifications == null) return false;
        return qualifications.stream().anyMatch(q -> q != null && q.getSkillId() == skillId
                && q.isUsableOn(start.toLocalDate(), end.toLocalDate()));
    }

    public boolean hasUsableLeadQualification(LocalDateTime start, LocalDateTime end) {
        if (qualifications == null) return false;
        return qualifications.stream().anyMatch(q -> q != null
                && q.isLeadUsableOn(start.toLocalDate(), end.toLocalDate()));
    }

    /**
     * @brief Constructs a fully specified Employee.
     *
     * @param id               The unique identifier of the employee.
     * @param code             The employee code.
     * @param firstName        The employee's first name.
     * @param lastName         The employee's last name.
     * @param desiredDates     The list of desired date intervals.
     * @param undesiredDates   The list of undesired date intervals.
     * @param unavailableDates The list of unavailable date intervals.
     * @param skills           The list of skills the employee possesses.
     */
    public Employee(int id, String code, String firstName, String lastName, List<EmployeeDate> desiredDates,
            List<EmployeeDate> undesiredDates, List<EmployeeDate> unavailableDates, List<Skill> skills) {
        this.id = id;
        this.code = code;
        this.firstName = firstName;
        this.lastName = lastName;
        this.fullName = firstName + " " + lastName;
        this.desiredDates = desiredDates;
        this.undesiredDates = undesiredDates;
        this.unavailableDates = unavailableDates;
        this.skills = skills;
    }

    /**
     * @brief Gets the employee ID.
     *
     * @return The employee ID.
     */
    public int getId() {
        return id;
    }

    /**
     * @brief Sets the employee ID.
     *
     * @param id The employee ID to set.
     */
    public void setId(int id) {
        this.id = id;
    }

    /**
     * @brief Gets the employee code.
     *
     * @return The employee code.
     */
    public String getCode() {
        return code;
    }

    /**
     * @brief Sets the employee code.
     *
     * @param code The employee code to set.
     */
    public void setCode(String code) {
        this.code = code;
    }

    /**
     * @brief Gets the employee's first name.
     *
     * @return The first name.
     */
    public String getFirstName() {
        return firstName;
    }

    /**
     * @brief Sets the employee's first name.
     *
     * @param firstName The first name to set.
     */
    public void setFirstName(String firstName) {
        this.firstName = firstName;
    }

    /**
     * @brief Gets the employee's last name.
     *
     * @return The last name.
     */
    public String getLastName() {
        return lastName;
    }

    /**
     * @brief Sets the employee's last name.
     *
     * @param lastName The last name to set.
     */
    public void setLastName(String lastName) {
        this.lastName = lastName;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    /**
     * @brief Gets the employee's full name.
     *
     * @return The full name.
     */
    public String getFullName() {
        return fullName;
    }

    /**
     * @brief Sets the employee's full name.
     *
     * @param fullName The full name to set.
     */
    public void setFullName(String fullName) {
        this.fullName = fullName;
    }

    /**
     * @brief Gets the list of skills possessed by the employee.
     *
     * @return The list of skills.
     */
    public List<Skill> getSkills() {
        return skills;
    }

    /**
     * @brief Sets the list of skills possessed by the employee.
     *
     * @param skills The list of skills to set.
     */
    public void setSkills(List<Skill> skills) {
        this.skills = skills;
    }

    /**
     * @brief Gets the list of unavailable date intervals.
     *
     * @return The list of unavailable dates.
     */
    public List<EmployeeDate> getUnavailableDates() {
        return unavailableDates;
    }

    /**
     * @brief Sets the list of unavailable date intervals.
     *
     * @param unavailableDates The list of unavailable dates to set.
     */
    public void setUnavailableDates(List<EmployeeDate> unavailableDates) {
        this.unavailableDates = unavailableDates;
    }

    /**
     * @brief Gets the list of undesired date intervals.
     *
     * @return The list of undesired dates.
     */
    public List<EmployeeDate> getUndesiredDates() {
        return undesiredDates;
    }

    /**
     * @brief Sets the list of undesired date intervals.
     *
     * @param undesiredDates The list of undesired dates to set.
     */
    public void setUndesiredDates(List<EmployeeDate> undesiredDates) {
        this.undesiredDates = undesiredDates;
    }

    /**
     * @brief Gets the list of desired date intervals.
     *
     * @return The list of desired dates.
     */
    public List<EmployeeDate> getDesiredDates() {
        return desiredDates;
    }

    /**
     * @brief Sets the list of desired date intervals.
     *
     * @param desiredDates The list of desired dates to set.
     */
    public void setDesiredDates(List<EmployeeDate> desiredDates) {
        this.desiredDates = desiredDates;
    }

    /**
     * @brief Two Employee objects are equal if they have the same ID.
     * @details Required because the solver payload makes a round trip through JSON:
     *          already-assigned shifts contain the nested employee, and Jackson deserializes
     *          it into an instance distinct from the one in the list
     *          `employees`
     *          (the value range). Without ID-based equality, constraints that join on
     *          Employee — unavailableEmployee, noOverlappingShifts, oneShiftPerDay,
     *          balancing — never trigger for preassigned shifts.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (!(o instanceof Employee))
            return false;
        return id == ((Employee) o).id;
    }

    @Override
    public int hashCode() {
        return Integer.hashCode(id);
    }

    /**
     * @brief Returns a string representation of this employee.
     *
     * @details Includes all fields: id, code, names, skills, and date preferences.
     *
     * @return A human-readable string describing the employee.
     */
    @Override
    public String toString() {
        return "Employee{" +
                "id=" + id +
                ", code='" + code + '\'' +
                ", firstName='" + firstName + '\'' +
                ", lastName='" + lastName + '\'' +
                ", email='" + email + '\'' +
                ", fullName='" + fullName + '\'' +
                ", skills=" + skills +
                ", unavailableDates=" + unavailableDates +
                ", undesiredDates=" + undesiredDates +
                ", desiredDates=" + desiredDates +
                '}';
    }
}
