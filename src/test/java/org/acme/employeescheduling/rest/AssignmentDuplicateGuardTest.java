package org.acme.employeescheduling.rest;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.acme.employeescheduling.persistence.EmployeeEntity;
import org.acme.employeescheduling.persistence.LocationEntity;
import org.acme.employeescheduling.persistence.StructureEntity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;

/**
 * @brief 「同一个人不得被同时排进两个时间重叠的班次」——保存分配时的兜底。
 *
 * @details 背景（2026-10-27 复核）：候选筛选只对**已落库**的班次判冲突，同一订单里
 *          还没保存的空名额互相看不见，于是 2 人名额的订单两个名额会给出同一批候选
 *          （实测前 20 名完全重合）。调度员把同一个人点到两个名额上时，
 *          {@code /demo-data/save-assignments} 原先只校验版本号与在职状态，
 *          真写下去就是一条实实在在的重复派工。
 *
 *          <p>守卫的边界很窄：<b>只看本次保存内部</b>的重复。历史上本来就带风险的其他分配
 *          仍然放行——否则"只补一个空缺、不动其他人"这条唯一的补单人路径就断了。</p>
 */
@QuarkusTest
@TestSecurity(user = "dup-admin", roles = {"ADMIN", "DISPATCHER", "APPROVER"})
@EnabledIfSystemProperty(named = "quarkus.test.profile", matches = "test-(sqlite|postgresql)")
class AssignmentDuplicateGuardTest {

    private static final DateTimeFormatter DB = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Test
    void sameEmployeeOnTwoOverlappingSlotsIsRejected() {
        Fixture fixture = QuarkusTransaction.requiringNew().call(AssignmentDuplicateGuardTest::fixture);
        LocalDateTime day = LocalDateTime.now().plusDays(30).withHour(0).withMinute(0).withSecond(0).withNano(0);
        String start = day.withHour(8).format(DB);
        String end = day.withHour(17).format(DB);
        List<Integer> shiftIds = twoSlotShiftIds(fixture, "DUP-", start, end);

        given().contentType(ContentType.JSON)
                .queryParam("structureId", fixture.structureId)
                .queryParam("start", day.format(DB))
                .queryParam("end", day.withHour(23).withMinute(59).withSecond(59).format(DB))
                .body(List.of(
                        Map.of("shift_id", shiftIds.get(0), "employee_id", fixture.employeeA),
                        Map.of("shift_id", shiftIds.get(1), "employee_id", fixture.employeeA)))
                .when().post("/demo-data/save-assignments").then().statusCode(409)
                .body("error", equalTo("EMPLOYEE_DOUBLE_BOOKED"));

        // 整份保存被拒 = 一行都不能写。写一半比两种结果都糟。
        given().queryParam("structureId", fixture.structureId).queryParam("employeeId", fixture.employeeA)
                .when().get("/schedule-plans/employee-shifts").then().statusCode(200)
                .body("count", equalTo(0));
    }

    @Test
    void twoDifferentEmployeesOnTheSameWindowAreAccepted() {
        Fixture fixture = QuarkusTransaction.requiringNew().call(AssignmentDuplicateGuardTest::fixture);
        LocalDateTime day = LocalDateTime.now().plusDays(31).withHour(0).withMinute(0).withSecond(0).withNano(0);
        String start = day.withHour(8).format(DB);
        String end = day.withHour(17).format(DB);
        List<Integer> shiftIds = twoSlotShiftIds(fixture, "OK-", start, end);

        given().contentType(ContentType.JSON)
                .queryParam("structureId", fixture.structureId)
                .queryParam("start", day.format(DB))
                .queryParam("end", day.withHour(23).withMinute(59).withSecond(59).format(DB))
                .body(List.of(
                        Map.of("shift_id", shiftIds.get(0), "employee_id", fixture.employeeA),
                        Map.of("shift_id", shiftIds.get(1), "employee_id", fixture.employeeB)))
                .when().post("/demo-data/save-assignments").then().statusCode(200)
                .body("updated", equalTo(2));
    }

    /**
     * 守卫只看「重叠」：同一个人跨两天做两段互不重叠的任务必须放行，
     * 否则会误伤"同一人连续跟多个阶段"的正常排法。
     */
    @Test
    void sameEmployeeOnNonOverlappingDaysIsAccepted() {
        Fixture fixture = QuarkusTransaction.requiringNew().call(AssignmentDuplicateGuardTest::fixture);
        LocalDateTime day = LocalDateTime.now().plusDays(32).withHour(0).withMinute(0).withSecond(0).withNano(0);
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        int orderId = createApprovedOrder(fixture.structureId, "SEQ-" + suffix);
        addPhase(fixture, orderId, "D1", day.withHour(8).format(DB), day.withHour(17).format(DB), 1);
        addPhase(fixture, orderId, "D2", day.plusDays(1).withHour(8).format(DB),
                day.plusDays(1).withHour(17).format(DB), 1);

        List<Integer> shiftIds = given().contentType(ContentType.JSON)
                .queryParam("structureId", fixture.structureId)
                .body(Map.of("name", "Sequence " + suffix))
                .when().post("/schedule-plans/from-order/{orderId}", orderId).then().statusCode(201)
                .extract().jsonPath().getList("assignments.shiftId", Integer.class);

        given().contentType(ContentType.JSON)
                .queryParam("structureId", fixture.structureId)
                .queryParam("start", day.format(DB))
                .queryParam("end", day.plusDays(2).format(DB))
                .body(List.of(
                        Map.of("shift_id", shiftIds.get(0), "employee_id", fixture.employeeA),
                        Map.of("shift_id", shiftIds.get(1), "employee_id", fixture.employeeA)))
                .when().post("/demo-data/save-assignments").then().statusCode(200)
                .body("updated", equalTo(2));
    }

    // ---------------- helpers ----------------

    /** 建一张 2 人名额的订单并取到两个名额的班次 id（同一时间窗 = 必然重叠）。 */
    private static List<Integer> twoSlotShiftIds(Fixture fixture, String prefix, String start, String end) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        int orderId = createApprovedOrder(fixture.structureId, prefix + suffix);
        addPhase(fixture, orderId, "P1", start, end, 2);
        List<Integer> shiftIds = given().contentType(ContentType.JSON)
                .queryParam("structureId", fixture.structureId)
                .body(Map.of("name", "Duplicate " + suffix))
                .when().post("/schedule-plans/from-order/{orderId}", orderId).then().statusCode(201)
                .extract().jsonPath().getList("assignments.shiftId", Integer.class);
        if (shiftIds.size() != 2) throw new IllegalStateException("expected 2 slots, got " + shiftIds.size());
        return shiftIds;
    }

    private static int createApprovedOrder(int structureId, String code) {
        return given().contentType(ContentType.JSON).queryParam("structureId", structureId)
                .body(Map.of("orderCode", code, "customerName", "Duplicate Customer",
                        "projectName", "Duplicate Audit", "status", "APPROVED", "priority", 3,
                        "ownerName", "Dispatcher", "notes", "duplicate guard test"))
                .when().post("/audit-orders").then().statusCode(201)
                .extract().jsonPath().getInt("id");
    }

    private static void addPhase(Fixture fixture, int orderId, String code, String start, String end,
            int headcount) {
        given().contentType(ContentType.JSON).queryParam("structureId", fixture.structureId)
                .body(Map.ofEntries(Map.entry("phaseCode", code), Map.entry("phaseName", "现场审核"),
                        Map.entry("startTime", start), Map.entry("endTime", end),
                        Map.entry("locationId", fixture.locationId), Map.entry("requiredHeadcount", headcount),
                        Map.entry("leadRequired", false), Map.entry("continuityGroup", ""),
                        Map.entry("deliveryMode", "ONSITE"), Map.entry("status", "READY"),
                        Map.entry("requiredSkills", List.of())))
                .when().post("/audit-orders/{id}/phases", orderId).then().statusCode(201);
    }

    private static Fixture fixture() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        StructureEntity structure = new StructureEntity();
        structure.name = "Dup " + suffix;
        structure.persistAndFlush();

        LocationEntity location = new LocationEntity();
        location.structureId = structure.id; location.code = "DLOC-" + suffix; location.name = "Shenzhen";
        location.active = true; location.latitude = 22.5431; location.longitude = 114.0579;
        location.persistAndFlush();

        return new Fixture(structure.id, location.id,
                employee(structure.id, location.id, "DA-" + suffix, "Alpha"),
                employee(structure.id, location.id, "DB-" + suffix, "Beta"));
    }

    private static int employee(int structureId, int locationId, String code, String firstName) {
        EmployeeEntity employee = new EmployeeEntity();
        employee.structureId = structureId; employee.code = code;
        employee.firstName = firstName; employee.lastName = "Auditor"; employee.email = "";
        employee.active = true; employee.operationalStatus = "AVAILABLE";
        employee.currentLocationId = locationId;
        employee.statusUpdatedAt = LocalDateTime.now().format(DB);
        employee.persistAndFlush();
        return employee.id;
    }

    private record Fixture(int structureId, int locationId, int employeeA, int employeeB) { }
}
