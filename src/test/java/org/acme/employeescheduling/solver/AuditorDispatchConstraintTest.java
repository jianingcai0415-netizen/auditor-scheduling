package org.acme.employeescheduling.solver;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import ai.timefold.solver.test.api.score.stream.ConstraintVerifier;
import org.acme.employeescheduling.domain.EmployeeSchedule;
import org.acme.employeescheduling.domain.Shift;
import org.acme.employeescheduling.dto.AuditorQualification;
import org.acme.employeescheduling.dto.Employee;
import org.acme.employeescheduling.dto.Skill;
import org.junit.jupiter.api.Test;

class AuditorDispatchConstraintTest {

    private final ConstraintVerifier<EmployeeSchedulingConstraintProvider, EmployeeSchedule> verifier =
            ConstraintVerifier.build(new EmployeeSchedulingConstraintProvider(), EmployeeSchedule.class, Shift.class);

    private static Employee auditor() {
        Employee employee = new Employee(1, "AUD-001", "Test", "Auditor",
                new ArrayList<>(), new ArrayList<>(), new ArrayList<>(),
                List.of(new Skill(10, "QMS", 1, true, true)));
        employee.setBufferBeforeMinutes(30);
        employee.setBufferAfterMinutes(30);
        return employee;
    }

    private static Shift shift(int id, int locationId, LocalDateTime start, LocalDateTime end, Employee employee) {
        return new Shift(id, start, end, locationId, "Site " + locationId,
                List.of(new Skill(10, "QMS", 1, true, true)), List.of(), employee);
    }

    private static AuditorQualification qualification(LocalDate until) {
        AuditorQualification qualification = new AuditorQualification();
        qualification.setSkillId(10);
        qualification.setCertificateNumber("TEST-QMS-001");
        qualification.setQualificationLevel("LEAD_AUDITOR");
        qualification.setValidFrom(LocalDate.of(2026, 1, 1));
        qualification.setValidUntil(until);
        qualification.setStatus("ACTIVE");
        return qualification;
    }

    @Test
    void expiredQualificationCannotCoverAudit() {
        Employee employee = auditor();
        employee.setQualifications(List.of(qualification(LocalDate.of(2026, 9, 30))));
        Shift shift = shift(1, 1, LocalDateTime.of(2026, 10, 2, 9, 0),
                LocalDateTime.of(2026, 10, 2, 17, 0), employee);

        verifier.verifyThat(EmployeeSchedulingConstraintProvider::requiredSkill)
                .given(shift)
                .penalizesBy(480);
    }

    @Test
    void liveExternalAssignmentIncludesBothBuffers() {
        Employee employee = auditor();
        employee.setOperationalStatus("ASSIGNED");
        employee.setBusyUntil(LocalDateTime.of(2026, 10, 2, 9, 0));
        employee.setCurrentLocationId(1);
        Shift shift = shift(1, 1, LocalDateTime.of(2026, 10, 2, 9, 30),
                LocalDateTime.of(2026, 10, 2, 17, 0), employee);

        verifier.verifyThat(EmployeeSchedulingConstraintProvider::externalAssignmentAvailability)
                .given(employee, shift)
                .penalizesBy(30);
    }

    @Test
    void differentSitesRequireTravelInAdditionToBuffers() {
        Employee employee = auditor();
        Shift first = shift(1, 1, LocalDateTime.of(2026, 10, 2, 8, 0),
                LocalDateTime.of(2026, 10, 2, 10, 0), employee);
        Shift second = shift(2, 2, LocalDateTime.of(2026, 10, 2, 11, 0),
                LocalDateTime.of(2026, 10, 2, 13, 0), employee);

        verifier.verifyThat(EmployeeSchedulingConstraintProvider::transferAndPersonalBuffer)
                .given(first, second)
                .penalizesBy(120);
    }

    @Test
    void continuousStagesKeepTheSameAuditorInTheSameSlot() {
        Employee firstAuditor = auditor();
        Employee secondAuditor = auditor();
        secondAuditor.setId(2);
        Shift first = shift(1, 1, LocalDateTime.of(2026, 10, 2, 8, 0),
                LocalDateTime.of(2026, 10, 2, 12, 0), firstAuditor);
        Shift second = shift(2, 1, LocalDateTime.of(2026, 10, 3, 8, 0),
                LocalDateTime.of(2026, 10, 3, 12, 0), secondAuditor);
        first.setContinuityGroup("ORDER-1"); second.setContinuityGroup("ORDER-1");
        first.setPhaseSlotIndex(1); second.setPhaseSlotIndex(1);

        verifier.verifyThat(EmployeeSchedulingConstraintProvider::continuityGroupAssignment)
                .given(first, second)
                .penalizesBy(240);
    }
}
