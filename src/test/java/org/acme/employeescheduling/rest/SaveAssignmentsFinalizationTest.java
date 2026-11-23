package org.acme.employeescheduling.rest;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.acme.employeescheduling.persistence.EmployeeEntity;
import org.acme.employeescheduling.persistence.EmployeeSkillEntity;
import org.acme.employeescheduling.persistence.LocationEntity;
import org.acme.employeescheduling.persistence.SkillEntity;
import org.acme.employeescheduling.persistence.StructureEntity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;

/**
 * @brief 「保存结果 = 定稿」契约：求解器保存分配后，覆盖这些班次的草案自动封存并进入 EXECUTED。
 *
 * @details 这是「分配即生效」最关键的一环。如果保存了分配但方案仍停在 DRAFT，
 * 事后追溯看到的会是"当初打算派谁"，而不是"最后派了谁"——快照与事实脱节。
 */
@QuarkusTest
@TestSecurity(user = "finalize-admin", roles = {"ADMIN", "DISPATCHER", "APPROVER"})
@EnabledIfSystemProperty(named = "quarkus.test.profile", matches = "test-(sqlite|postgresql)")
class SaveAssignmentsFinalizationTest {
    private static final DateTimeFormatter DB = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Test
    void savingAssignmentsFinalizesTheCoveredDraftPlan() {
        Fixture fixture = QuarkusTransaction.requiringNew().call(SaveAssignmentsFinalizationTest::fixture);
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String orderCode = "FIN-" + suffix;
        LocalDateTime day = LocalDateTime.now().plusDays(10).withHour(0).withMinute(0).withSecond(0).withNano(0);
        String start = day.withHour(8).format(DB);
        String end = day.withHour(17).format(DB);

        int orderId = given().contentType(ContentType.JSON).queryParam("structureId", fixture.structureId)
                .body(Map.of("orderCode", orderCode, "customerName", "Finalize Customer",
                        "projectName", "Finalize Audit", "status", "APPROVED", "priority", 3,
                        "ownerName", "Dispatcher", "notes", "finalization test"))
                .when().post("/audit-orders").then().statusCode(201)
                .extract().jsonPath().getInt("id");

        given().contentType(ContentType.JSON).queryParam("structureId", fixture.structureId)
                .body(Map.ofEntries(Map.entry("phaseCode", "P1"), Map.entry("phaseName", "现场审核"),
                        Map.entry("startTime", start), Map.entry("endTime", end),
                        Map.entry("locationId", fixture.locationId), Map.entry("requiredHeadcount", 1),
                        Map.entry("leadRequired", false), Map.entry("continuityGroup", "G1"),
                        Map.entry("deliveryMode", "ONSITE"), Map.entry("status", "READY"),
                        Map.entry("requiredSkills", List.of())))
                .when().post("/audit-orders/{id}/phases", orderId).then().statusCode(201);

        var plan = given().contentType(ContentType.JSON).queryParam("structureId", fixture.structureId)
                .body(Map.of("name", "Finalize plan " + suffix))
                .when().post("/schedule-plans/from-order/{orderId}", orderId).then().statusCode(201)
                .body("status", equalTo("DRAFT")).body("gapCount", equalTo(1))
                .extract().response();
        int planId = plan.jsonPath().getInt("id");
        int shiftId = plan.jsonPath().getInt("assignments[0].shiftId");

        given().contentType(ContentType.JSON)
                .queryParam("structureId", fixture.structureId)
                .queryParam("start", day.format(DB))
                .queryParam("end", day.withHour(23).withMinute(59).withSecond(59).format(DB))
                .body(List.of(Map.of("shift_id", shiftId, "employee_id", fixture.employeeId)))
                .when().post("/demo-data/save-assignments").then().statusCode(200)
                .body("updated", equalTo(1))
                .body("finalizedPlans", equalTo(1));

        given().queryParam("structureId", fixture.structureId).when().get("/schedule-plans/{id}", planId)
                .then().statusCode(200)
                .body("status", equalTo("EXECUTED"))
                .body("publishedBy", equalTo("finalize-admin"))
                .body("publishedAt", org.hamcrest.Matchers.notNullValue())
                .body("assignments[0].employeeId", equalTo(fixture.employeeId));
    }

    /**
     * @brief 只有部分名额被分配时不得定稿 —— 方案还有缺口，调度员需要继续排。
     */
    @Test
    void partiallyFilledPlanStaysDraft() {
        Fixture fixture = QuarkusTransaction.requiringNew().call(SaveAssignmentsFinalizationTest::fixture);
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String orderCode = "PART-" + suffix;
        LocalDateTime day = LocalDateTime.now().plusDays(11).withHour(0).withMinute(0).withSecond(0).withNano(0);
        String start = day.withHour(8).format(DB);
        String end = day.withHour(17).format(DB);

        int orderId = given().contentType(ContentType.JSON).queryParam("structureId", fixture.structureId)
                .body(Map.of("orderCode", orderCode, "customerName", "Partial Customer",
                        "projectName", "Partial Audit", "status", "APPROVED", "priority", 3,
                        "ownerName", "Dispatcher", "notes", "partial finalization"))
                .when().post("/audit-orders").then().statusCode(201)
                .extract().jsonPath().getInt("id");

        given().contentType(ContentType.JSON).queryParam("structureId", fixture.structureId)
                .body(Map.ofEntries(Map.entry("phaseCode", "P1"), Map.entry("phaseName", "现场审核"),
                        Map.entry("startTime", start), Map.entry("endTime", end),
                        Map.entry("locationId", fixture.locationId), Map.entry("requiredHeadcount", 2),
                        Map.entry("leadRequired", false), Map.entry("continuityGroup", "G1"),
                        Map.entry("deliveryMode", "ONSITE"), Map.entry("status", "READY"),
                        Map.entry("requiredSkills", List.of())))
                .when().post("/audit-orders/{id}/phases", orderId).then().statusCode(201);

        var plan = given().contentType(ContentType.JSON).queryParam("structureId", fixture.structureId)
                .body(Map.of("name", "Partial plan " + suffix))
                .when().post("/schedule-plans/from-order/{orderId}", orderId).then().statusCode(201)
                .body("gapCount", equalTo(2))
                .extract().response();
        int planId = plan.jsonPath().getInt("id");
        int shiftId = plan.jsonPath().getInt("assignments[0].shiftId");

        given().contentType(ContentType.JSON)
                .queryParam("structureId", fixture.structureId)
                .queryParam("start", day.format(DB))
                .queryParam("end", day.withHour(23).withMinute(59).withSecond(59).format(DB))
                .body(List.of(Map.of("shift_id", shiftId, "employee_id", fixture.employeeId)))
                .when().post("/demo-data/save-assignments").then().statusCode(200)
                .body("updated", equalTo(1));

        given().queryParam("structureId", fixture.structureId).when().get("/schedule-plans/{id}", planId)
                .then().statusCode(200).body("status", equalTo("DRAFT"));
    }

    private static Fixture fixture() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        StructureEntity structure = new StructureEntity();
        structure.name = "Finalize " + suffix; structure.persistAndFlush();
        LocationEntity location = new LocationEntity();
        location.structureId = structure.id; location.code = "FLOC-" + suffix; location.name = "Shanghai";
        location.active = true; location.latitude = 31.2304; location.longitude = 121.4737;
        location.persistAndFlush();
        SkillEntity skill = new SkillEntity();
        skill.structureId = structure.id; skill.name = "FIN-" + suffix; skill.skillOrder = 1; skill.active = true;
        skill.persistAndFlush();
        EmployeeEntity employee = new EmployeeEntity();
        employee.structureId = structure.id; employee.code = "FAUD-" + suffix;
        employee.firstName = "Finalize"; employee.lastName = "Auditor"; employee.email = "";
        employee.active = true; employee.operationalStatus = "AVAILABLE";
        employee.currentLocationId = location.id;
        employee.statusUpdatedAt = LocalDateTime.now().format(DB);
        employee.persistAndFlush();
        EmployeeSkillEntity qualification = new EmployeeSkillEntity();
        qualification.employeeId = employee.id; qualification.skillId = skill.id;
        qualification.certificateNumber = "FINCERT-" + suffix;
        qualification.qualificationLevel = "AUDITOR"; qualification.qualificationStatus = "ACTIVE";
        qualification.validFrom = "2020-01-01"; qualification.validUntil = "2099-12-31";
        qualification.persist();
        return new Fixture(structure.id, location.id, employee.id);
    }

    private record Fixture(int structureId, int locationId, int employeeId) { }
}
