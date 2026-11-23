package org.acme.employeescheduling.rest;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

import org.acme.employeescheduling.persistence.EmployeeEntity;
import org.acme.employeescheduling.persistence.LocationEntity;
import org.acme.employeescheduling.persistence.ShiftEntity;
import org.acme.employeescheduling.persistence.StructureEntity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;

/**
 * 员工删除保护：只拦「进行中或未来」的班次。
 *
 * <p>需求背景：删除员工会把班次 employee_id 置空，那些名额静默变成无人。
 * 但如果连历史班次也拦，任何人只要被排过一次班就永远删不掉——
 * 所以边界必须画在 end_time >= now，前端图标与后端 409 用同一条规则。
 */
@QuarkusTest
@TestSecurity(user = "dispatch-admin", roles = {"ADMIN", "DISPATCHER"})
@EnabledIfSystemProperty(named = "quarkus.test.profile", matches = "test-(sqlite|postgresql)")
class EmployeeDeletionGuardTest {

    private static final DateTimeFormatter DB = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Test
    void upcomingShiftBlocksDeletionWhileHistoryDoesNot() {
        Fixture fixture = QuarkusTransaction.requiringNew().call(EmployeeDeletionGuardTest::fixture);

        // 1) 有未来班次 → 必须拒绝，且不能把班次解绑
        given().queryParam("structureId", fixture.structureId).when()
                .delete("/demo-data/employees/{id}", fixture.upcomingEmployeeId)
                .then().statusCode(409)
                .body("error", equalTo("EMPLOYEE_IN_USE"));

        QuarkusTransaction.requiringNew().run(() -> {
            EmployeeEntity still = EmployeeEntity.findById(fixture.upcomingEmployeeId);
            assert still != null : "被拒绝后员工必须仍然存在";
            ShiftEntity shift = ShiftEntity.findById(fixture.upcomingShiftId);
            assert shift != null && shift.employeeId != null : "被拒绝后班次不应被解绑";
        });

        // 2) 只有历史班次 → 允许删除（否则离职人员永久锁死）
        given().queryParam("structureId", fixture.structureId).when()
                .delete("/demo-data/employees/{id}", fixture.historicalEmployeeId)
                .then().statusCode(204);

        // 3) 完全没有班次 → 允许删除
        given().queryParam("structureId", fixture.structureId).when()
                .delete("/demo-data/employees/{id}", fixture.freeEmployeeId)
                .then().statusCode(204);
    }

    @Test
    void usageSeparatesHistoricalFromUpcomingEmployees() {
        Fixture fixture = QuarkusTransaction.requiringNew().call(EmployeeDeletionGuardTest::fixture);

        given().queryParam("structureId", fixture.structureId).when()
                .get("/demo-data/usage")
                .then().statusCode(200)
                // 任意班次（含历史）——地点关联与"干过活"的事实
                .body("employeeIds", hasItem(fixture.upcomingEmployeeId))
                .body("employeeIds", hasItem(fixture.historicalEmployeeId))
                // 在途班次——只有这部分才应阻止删除
                .body("activeEmployeeIds", hasItem(fixture.upcomingEmployeeId))
                .body("activeEmployeeIds", not(hasItem(fixture.historicalEmployeeId)))
                .body("activeEmployeeIds", not(hasItem(fixture.freeEmployeeId)));
    }

    /** 简历字段在无附件时必须是 null，前端据此显示"尚未上传"。 */
    @Test
    void resumeFieldsDefaultToNull() {
        Fixture fixture = QuarkusTransaction.requiringNew().call(EmployeeDeletionGuardTest::fixture);

        given().queryParam("structureId", fixture.structureId).when()
                .get("/demo-data/getemployee/{id}", fixture.freeEmployeeId)
                .then().statusCode(200)
                .body("resumeFileName", equalTo(null))
                .body("resumeUploadedAt", equalTo(null));
    }

    private static Fixture fixture() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        LocalDateTime now = LocalDateTime.now();

        StructureEntity structure = new StructureEntity();
        structure.name = "Delete Guard " + suffix;
        structure.persistAndFlush();

        LocationEntity location = new LocationEntity();
        location.structureId = structure.id;
        location.code = "DLOC-" + suffix;
        location.name = "Shanghai";
        location.active = true;
        location.latitude = 31.2304;
        location.longitude = 121.4737;
        location.persistAndFlush();

        EmployeeEntity upcoming = employee(structure.id, "UP-" + suffix, location.id);
        EmployeeEntity historical = employee(structure.id, "HIST-" + suffix, location.id);
        EmployeeEntity free = employee(structure.id, "FREE-" + suffix, location.id);

        ShiftEntity upcomingShift = shift(location.id, upcoming.id,
                now.plusDays(2).withHour(9).withMinute(0).withSecond(0).withNano(0),
                now.plusDays(2).withHour(18).withMinute(0).withSecond(0).withNano(0));
        // 历史：一个月前就结束了
        shift(location.id, historical.id,
                now.minusDays(30).withHour(9).withMinute(0).withSecond(0).withNano(0),
                now.minusDays(30).withHour(18).withMinute(0).withSecond(0).withNano(0));

        return new Fixture(structure.id, upcoming.id, historical.id, free.id, upcomingShift.id);
    }

    private static EmployeeEntity employee(int structureId, String code, int locationId) {
        EmployeeEntity entity = new EmployeeEntity();
        entity.structureId = structureId;
        entity.code = code;
        entity.firstName = "Guard";
        entity.lastName = "Case";
        entity.email = "";
        entity.active = true;
        entity.operationalStatus = "AVAILABLE";
        entity.currentLocationId = locationId;
        entity.statusUpdatedAt = LocalDateTime.now().format(DB);
        entity.persistAndFlush();
        return entity;
    }

    private static ShiftEntity shift(int locationId, int employeeId, LocalDateTime start, LocalDateTime end) {
        ShiftEntity entity = new ShiftEntity();
        entity.locationId = locationId;
        entity.employeeId = employeeId;
        entity.startTime = start.format(DB);
        entity.endTime = end.format(DB);
        entity.auditOrderCode = "";
        entity.auditStage = "";
        entity.persistAndFlush();
        return entity;
    }

    private record Fixture(int structureId, int upcomingEmployeeId, int historicalEmployeeId,
            int freeEmployeeId, int upcomingShiftId) {
    }
}
