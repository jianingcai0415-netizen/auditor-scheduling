package org.acme.employeescheduling.rest;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.acme.employeescheduling.persistence.EmployeeEntity;
import org.acme.employeescheduling.persistence.EmployeeDateEntity;
import org.acme.employeescheduling.persistence.EmployeeSkillEntity;
import org.acme.employeescheduling.persistence.LocationEntity;
import org.acme.employeescheduling.persistence.ShiftEntity;
import org.acme.employeescheduling.persistence.SkillEntity;
import org.acme.employeescheduling.persistence.StructureEntity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;

/** End-to-end contract for order materialization and immutable schedule publication. */
@QuarkusTest
@TestSecurity(user = "workflow-admin", roles = {"ADMIN", "DISPATCHER", "APPROVER"})
@EnabledIfSystemProperty(named = "quarkus.test.profile", matches = "test-(sqlite|postgresql)")
class InternalDispatchWorkflowTest {
    private static final DateTimeFormatter DB = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Test
    void orderPhaseDraftApprovalAndPublicationFormOneTraceableFlow() {
        Fixture fixture = QuarkusTransaction.requiringNew().call(() -> fixture());
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String orderCode = "ORD-" + suffix;
        String start = LocalDateTime.now().plusDays(10).withHour(8).withMinute(0).withSecond(0).withNano(0).format(DB);
        String end = LocalDateTime.now().plusDays(10).withHour(17).withMinute(0).withSecond(0).withNano(0).format(DB);

        int orderId = given().contentType(ContentType.JSON).queryParam("structureId", fixture.structureId)
                .body(Map.of("orderCode", orderCode, "customerName", "Pilot Customer",
                        "projectName", "Pilot Audit", "status", "DRAFT", "priority", 2,
                        "ownerName", "Dispatcher", "notes", "workflow test"))
                .when().post("/audit-orders").then().statusCode(201)
                .body("status", equalTo("DRAFT"))
                .extract().jsonPath().getInt("id");

        // 阶段必须先配好（含资格）才能提交：提交门禁要求一张完整的"需求单"。
        // 顺序上「先配需求、再走审批」也更贴近真实业务——审批人批的是要不要做、要什么样的人。
        int phaseId = given().contentType(ContentType.JSON).queryParam("structureId", fixture.structureId)
                .body(Map.ofEntries(Map.entry("phaseCode", "P1"), Map.entry("phaseName", "现场审核"),
                        Map.entry("startTime", start), Map.entry("endTime", end),
                        Map.entry("locationId", fixture.locationId), Map.entry("requiredHeadcount", 1),
                        Map.entry("leadRequired", true), Map.entry("continuityGroup", "G1"),
                        Map.entry("deliveryMode", "ONSITE"), Map.entry("status", "READY"),
                        Map.entry("requiredSkills", List.of(Map.of(
                                "skillId", fixture.skillId, "minimumCount", 1, "leadOnly", false)))))
                .when().post("/audit-orders/{id}/phases", orderId).then().statusCode(201)
                .body("requiredHeadcount", equalTo(1)).extract().jsonPath().getInt("id");

        // 审批在订单层，只有 APPROVED 的订单才允许排班（方案层不再有提交/批准）。
        given().contentType(ContentType.JSON).queryParam("structureId", fixture.structureId).when()
                .post("/audit-orders/{id}/submit", orderId).then().statusCode(200)
                .body("status", equalTo("SUBMITTED"));
        given().contentType(ContentType.JSON).queryParam("structureId", fixture.structureId).when()
                .post("/audit-orders/{id}/approve", orderId).then().statusCode(200)
                .body("status", equalTo("APPROVED"));

        QuarkusTransaction.requiringNew().run(() -> {
            EmployeeDateEntity unavailable = new EmployeeDateEntity();
            unavailable.employeeId = fixture.unavailableEmployeeId; unavailable.dateStart = start;
            unavailable.dateEnd = end; unavailable.dateTypeId = EmployeeDateEntity.TYPE_UNAVAILABLE;
            unavailable.persist();
        });

        given().queryParam("structureId", fixture.structureId).when().get("/candidate-weights")
                .then().statusCode(200).body("version", equalTo(0)).body("source", equalTo("DEFAULT"));
        given().contentType(ContentType.JSON).queryParam("structureId", fixture.structureId)
                .body(Map.of("ability", 40, "location", 29, "workload", 15, "continuity", 10,
                        "age", 5, "changeReason", "合计错误"))
                .when().put("/candidate-weights").then().statusCode(400)
                .body("error", equalTo("CANDIDATE_WEIGHT_INVALID"));
        given().contentType(ContentType.JSON).queryParam("structureId", fixture.structureId)
                .body(Map.of("ability", 0, "location", 100, "workload", 0, "continuity", 0,
                        "age", 0, "changeReason", "端到端测试：仅按位置评分"))
                .when().put("/candidate-weights").then().statusCode(200)
                .body("version", equalTo(1)).body("source", equalTo("PROFILE"));

        given().queryParam("structureId", fixture.structureId).queryParam("orderCode", orderCode)
                .queryParam("limit", 1)
                .when().get("/schedule-plans/order-candidates").then().statusCode(200)
                .body("orderCode", equalTo(orderCode)).body("weightVersion", equalTo(1))
                .body("weightSource", equalTo("PROFILE")).body("weights.location", equalTo(100))
                .body("phases.size()", equalTo(1))
                .body("phases[0].slots.size()", equalTo(1))
                .body("phases[0].slots[0].evaluatedCount", equalTo(3))
                .body("phases[0].slots[0].eligibleCount", equalTo(1))
                .body("phases[0].slots[0].excludedCounts.EMPLOYEE_BUSY", equalTo(1))
                .body("phases[0].slots[0].excludedCounts.EMPLOYEE_UNAVAILABLE", equalTo(1))
                .body("phases[0].slots[0].candidates.size()", equalTo(1))
                .body("phases[0].slots[0].candidates[0].rank", equalTo(1))
                .body("phases[0].slots[0].candidates[0].employeeId", equalTo(fixture.employeeId))
                .body("phases[0].slots[0].candidates[0].travelMinutes", equalTo(0))
                .body("phases[0].slots[0].candidates[0].totalScore", equalTo(100.0f));

        given().when().get("/schedule-plans/ai-status").then().statusCode(200)
                .body("configured", equalTo(false)).body("mode", equalTo("ADVISORY_ONLY"));
        given().contentType(ContentType.JSON).body("{}")
                .queryParam("structureId", fixture.structureId).queryParam("orderCode", orderCode)
                .queryParam("limit", 1)
                .when().post("/schedule-plans/order-candidates/ai-review").then().statusCode(503)
                .body("error", equalTo("AI_NOT_CONFIGURED"));

        QuarkusTransaction.requiringNew().run(() -> {
            ShiftEntity shift = ShiftEntity.find("auditOrderPhaseId", phaseId).firstResult();
            shift.employeeId = fixture.employeeId;
            shift.pinned = true;
        });

        var planResponse = given().contentType(ContentType.JSON).queryParam("structureId", fixture.structureId)
                .body(Map.of("name", "Pilot plan " + suffix))
                .when().post("/schedule-plans/from-order/{orderId}", orderId).then().statusCode(201)
                .body("status", equalTo("DRAFT")).body("gapCount", equalTo(1))
                .body("riskCount", equalTo(0))
                .body("assignmentCount", equalTo(1)).body("assignments[0].auditOrderCode", equalTo(orderCode))
                .body("assignments[0].employeeId", equalTo(null)).body("assignments[0].locked", equalTo(false))
                .extract().response();
        int planId = planResponse.jsonPath().getInt("id");
        int assignmentId = planResponse.jsonPath().getInt("assignments[0].id");

        given().contentType(ContentType.JSON).body("{}").queryParam("structureId", fixture.structureId).when()
                .post("/schedule-plans/{id}/publish", planId).then().statusCode(409)
                .body("error", equalTo("SCHEDULE_PLAN_HAS_GAPS"));

        given().queryParam("structureId", fixture.structureId).queryParam("limit", 1)
                .queryParam("abilityWeight", 0).queryParam("locationWeight", 100)
                .queryParam("workloadWeight", 0).queryParam("continuityWeight", 0).queryParam("ageWeight", 0)
                .when().get("/schedule-plans/{id}/assignments/{assignmentId}/candidates", planId, assignmentId)
                .then().statusCode(200).body("size()", equalTo(1))
                .body("[0].rank", equalTo(1)).body("[0].employeeId", equalTo(fixture.employeeId))
                .body("[0].travelMinutes", equalTo(0)).body("[0].totalScore", equalTo(100.0f));

        given().contentType(ContentType.JSON).queryParam("structureId", fixture.structureId)
                .body(Map.of("employeeId", fixture.employeeId, "locked", true,
                        "assignmentRole", "LEAD_AUDITOR", "changeReason", "负责人确认：端到端测试"))
                .when().put("/schedule-plans/{id}/assignments/{assignmentId}", planId, assignmentId)
                .then().statusCode(200).body("employeeId", equalTo(fixture.employeeId))
                .body("locked", equalTo(true));

        QuarkusTransaction.requiringNew().run(() -> {
            EmployeeEntity employee = EmployeeEntity.findById(fixture.employeeId);
            employee.statusUpdatedAt = LocalDateTime.now().plusMinutes(1).format(DB);
        });
        given().queryParam("structureId", fixture.structureId).when().get("/schedule-plans/{id}", planId)
                .then().statusCode(200).body("riskCount", equalTo(1))
                .body("assignments[0].riskCode", equalTo("EMPLOYEE_STATUS_CHANGED"));
        given().contentType(ContentType.JSON).body("{}").queryParam("structureId", fixture.structureId).when()
                .post("/schedule-plans/{id}/publish", planId).then().statusCode(409)
                .body("error", equalTo("EMPLOYEE_STATUS_CHANGED"));

        given().contentType(ContentType.JSON).queryParam("structureId", fixture.structureId)
                .body(Map.of("employeeId", fixture.employeeId, "locked", true,
                        "assignmentRole", "LEAD_AUDITOR", "changeReason", "负责人确认：状态变化后重新确认"))
                .when().put("/schedule-plans/{id}/assignments/{assignmentId}", planId, assignmentId)
                .then().statusCode(200);

        // 定稿即生效：分配写入班次并封存快照，方案状态直接进入 EXECUTED。
        given().contentType(ContentType.JSON).body("{}").queryParam("structureId", fixture.structureId).when()
                .post("/schedule-plans/{id}/publish", planId).then().statusCode(200).body("status", equalTo("EXECUTED"));

        // A finalized assignment can be replaced only through a new, auditable revision.
        QuarkusTransaction.requiringNew().run(() -> EmployeeDateEntity.delete(
                "employeeId", fixture.unavailableEmployeeId));
        var revisionResponse = given().contentType(ContentType.JSON).body("{}")
                .queryParam("structureId", fixture.structureId).when()
                .post("/schedule-plans/{id}/revise", planId).then().statusCode(201)
                .body("status", equalTo("DRAFT")).body("parentPlanId", equalTo(planId))
                .body("assignments[0].employeeId", equalTo(fixture.employeeId)).extract().response();
        int revisionId = revisionResponse.jsonPath().getInt("id");
        int revisionAssignmentId = revisionResponse.jsonPath().getInt("assignments[0].id");
        given().contentType(ContentType.JSON).queryParam("structureId", fixture.structureId)
                .body(Map.of("employeeId", fixture.unavailableEmployeeId, "locked", true,
                        "assignmentRole", "LEAD_AUDITOR", "changeReason", "临时外派变化：启用替补"))
                .when().put("/schedule-plans/{id}/assignments/{assignmentId}", revisionId, revisionAssignmentId)
                .then().statusCode(200).body("employeeId", equalTo(fixture.unavailableEmployeeId));
        given().contentType(ContentType.JSON).body("{}").queryParam("structureId", fixture.structureId).when()
                .post("/schedule-plans/{id}/publish", revisionId).then().statusCode(200).body("status", equalTo("EXECUTED"));
        given().queryParam("structureId", fixture.structureId).when().get("/schedule-plans/{id}", planId)
                .then().statusCode(200).body("status", equalTo("SUPERSEDED"));

        Map<String, Object> phaseUpdate = new java.util.HashMap<>(Map.ofEntries(Map.entry("phaseCode", "P1"),
                Map.entry("phaseName", "现场审核"), Map.entry("startTime", start), Map.entry("endTime", end),
                Map.entry("locationId", fixture.locationId), Map.entry("requiredHeadcount", 1),
                Map.entry("leadRequired", true), Map.entry("continuityGroup", "G1"),
                Map.entry("deliveryMode", "ONSITE"), Map.entry("status", "CANCELLED"),
                Map.entry("requiredSkills", List.of(Map.of(
                        "skillId", fixture.skillId, "minimumCount", 1, "leadOnly", false)))));
        given().contentType(ContentType.JSON).queryParam("structureId", fixture.structureId).body(phaseUpdate)
                .when().put("/audit-orders/phases/{id}", phaseId).then().statusCode(200)
                .body("status", equalTo("CANCELLED"));
        given().queryParam("structureId", fixture.structureId).queryParam("orderCode", orderCode)
                .when().get("/schedule-plans/order-candidates").then().statusCode(409)
                .body("error", equalTo("AUDIT_ORDER_NO_READY_PHASES"));
        given().contentType(ContentType.JSON).queryParam("structureId", fixture.structureId)
                .body(Map.of("name", "must not be created")).when()
                .post("/schedule-plans/from-order/{orderId}", orderId).then().statusCode(409)
                .body("error", equalTo("AUDIT_ORDER_NO_READY_PHASES"));

        phaseUpdate.put("status", "READY");
        given().contentType(ContentType.JSON).queryParam("structureId", fixture.structureId).body(phaseUpdate)
                .when().put("/audit-orders/phases/{id}", phaseId).then().statusCode(200);
        given().contentType(ContentType.JSON).queryParam("structureId", fixture.structureId)
                .body(Map.of("orderCode", orderCode, "customerName", "Pilot Customer",
                        "projectName", "Pilot Audit", "status", "CANCELLED", "priority", 2,
                        "ownerName", "Dispatcher", "notes", "workflow test"))
                .when().put("/audit-orders/{id}", orderId).then().statusCode(200)
                .body("status", equalTo("CANCELLED"));
        given().queryParam("structureId", fixture.structureId).queryParam("orderCode", orderCode)
                .when().get("/schedule-plans/order-candidates").then().statusCode(409)
                .body("error", equalTo("AUDIT_ORDER_NOT_APPROVED"));
        given().contentType(ContentType.JSON).queryParam("structureId", fixture.structureId)
                .body(Map.of("name", "must not be created")).when()
                .post("/schedule-plans/from-order/{orderId}", orderId).then().statusCode(409)
                .body("error", equalTo("AUDIT_ORDER_NOT_APPROVED"));
        given().queryParam("structureId", fixture.structureId).when().get("/audit-events").then().statusCode(200);
    }

    private static Fixture fixture() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        StructureEntity structure = new StructureEntity(); structure.name = "Workflow " + suffix; structure.persistAndFlush();
        LocationEntity location = new LocationEntity(); location.structureId = structure.id; location.code = "WLOC-" + suffix;
        location.name = "Shanghai"; location.active = true; location.latitude = 31.2304; location.longitude = 121.4737;
        location.persistAndFlush();
        SkillEntity skill = new SkillEntity(); skill.structureId = structure.id; skill.name = "ISO-" + suffix;
        skill.skillOrder = 1; skill.active = true; skill.persistAndFlush();
        EmployeeEntity employee = new EmployeeEntity(); employee.structureId = structure.id; employee.code = "WAUD-" + suffix;
        employee.firstName = "Test"; employee.lastName = "Lead"; employee.email = ""; employee.active = true;
        employee.operationalStatus = "AVAILABLE"; employee.currentLocationId = location.id;
        employee.statusUpdatedAt = LocalDateTime.now().format(DB); employee.persistAndFlush();
        EmployeeSkillEntity qualification = new EmployeeSkillEntity(); qualification.employeeId = employee.id;
        qualification.skillId = skill.id; qualification.certificateNumber = "CERT-" + suffix;
        qualification.qualificationLevel = "LEAD_AUDITOR"; qualification.qualificationStatus = "ACTIVE";
        qualification.validFrom = "2020-01-01"; qualification.validUntil = "2099-12-31"; qualification.persist();

        LocationEntity farLocation = new LocationEntity(); farLocation.structureId = structure.id;
        farLocation.code = "FAR-" + suffix; farLocation.name = "Beijing"; farLocation.active = true;
        farLocation.latitude = 39.9042; farLocation.longitude = 116.4074; farLocation.persistAndFlush();
        EmployeeEntity farEmployee = new EmployeeEntity(); farEmployee.structureId = structure.id;
        farEmployee.code = "FAR-AUD-" + suffix; farEmployee.firstName = "Far"; farEmployee.lastName = "Lead";
        farEmployee.email = ""; farEmployee.active = true; farEmployee.operationalStatus = "ASSIGNED";
        farEmployee.busyUntil = "2099-12-31 17:00:00";
        farEmployee.currentLocationId = farLocation.id; farEmployee.statusUpdatedAt = LocalDateTime.now().format(DB);
        farEmployee.persistAndFlush();
        EmployeeSkillEntity farQualification = new EmployeeSkillEntity(); farQualification.employeeId = farEmployee.id;
        farQualification.skillId = skill.id; farQualification.certificateNumber = "FAR-CERT-" + suffix;
        farQualification.qualificationLevel = "LEAD_AUDITOR"; farQualification.qualificationStatus = "ACTIVE";
        farQualification.validFrom = "2020-01-01"; farQualification.validUntil = "2099-12-31";
        farQualification.persist();

        EmployeeEntity unavailableEmployee = new EmployeeEntity(); unavailableEmployee.structureId = structure.id;
        unavailableEmployee.code = "UNAVAILABLE-AUD-" + suffix; unavailableEmployee.firstName = "Unavailable";
        unavailableEmployee.lastName = "Lead"; unavailableEmployee.email = ""; unavailableEmployee.active = true;
        unavailableEmployee.operationalStatus = "AVAILABLE"; unavailableEmployee.currentLocationId = location.id;
        unavailableEmployee.statusUpdatedAt = LocalDateTime.now().format(DB); unavailableEmployee.persistAndFlush();
        EmployeeSkillEntity unavailableQualification = new EmployeeSkillEntity();
        unavailableQualification.employeeId = unavailableEmployee.id; unavailableQualification.skillId = skill.id;
        unavailableQualification.certificateNumber = "UNAVAILABLE-CERT-" + suffix;
        unavailableQualification.qualificationLevel = "LEAD_AUDITOR";
        unavailableQualification.qualificationStatus = "ACTIVE"; unavailableQualification.validFrom = "2020-01-01";
        unavailableQualification.validUntil = "2099-12-31"; unavailableQualification.persist();
        return new Fixture(structure.id, location.id, skill.id, employee.id, unavailableEmployee.id);
    }

    private record Fixture(int structureId, int locationId, int skillId, int employeeId,
            int unavailableEmployeeId) { }
}
