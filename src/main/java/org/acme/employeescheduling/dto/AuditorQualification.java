package org.acme.employeescheduling.dto;

import java.time.LocalDate;

/** A dated auditor qualification attached to one skill/scheme. */
public class AuditorQualification {

    private int skillId;
    private String certificateNumber = "";
    private String qualificationLevel = "AUDITOR";
    private LocalDate validFrom;
    private LocalDate validUntil;
    private String status = "ACTIVE";

    public int getSkillId() { return skillId; }
    public void setSkillId(int skillId) { this.skillId = skillId; }

    public String getCertificateNumber() { return certificateNumber; }
    public void setCertificateNumber(String certificateNumber) { this.certificateNumber = certificateNumber; }

    public String getQualificationLevel() { return qualificationLevel; }
    public void setQualificationLevel(String qualificationLevel) { this.qualificationLevel = qualificationLevel; }

    public LocalDate getValidFrom() { return validFrom; }
    public void setValidFrom(LocalDate validFrom) { this.validFrom = validFrom; }

    public LocalDate getValidUntil() { return validUntil; }
    public void setValidUntil(LocalDate validUntil) { this.validUntil = validUntil; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public boolean isUsableOn(LocalDate start, LocalDate end) {
        boolean usableStatus = "ACTIVE".equalsIgnoreCase(status) || "EXPIRING".equalsIgnoreCase(status);
        return usableStatus
                && (validFrom == null || !validFrom.isAfter(start))
                && (validUntil == null || !validUntil.isBefore(end));
    }

    public boolean isLeadUsableOn(LocalDate start, LocalDate end) {
        String level = qualificationLevel == null ? "" : qualificationLevel.trim().toUpperCase();
        return isUsableOn(start, end)
                && (level.contains("LEAD") || level.contains("组长") || level.contains("主任"));
    }
}
