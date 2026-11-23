package org.acme.employeescheduling.rest;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

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
import io.restassured.response.Response;

/**
 * @brief 「方案被取代」的判定范围：只有覆盖同一张订单的方案才互为版本。
 *
 * @details 历史缺陷（2026-10-28 三张验收单实测）：{@code supersedeOverlapping} 原来只按**时间窗重叠**
 *          判定，而定稿是按订单走的——于是定稿 B 单方案会把同月的 A 单方案也标成 SUPERSEDED，
 *          可 A 的班次一条都没少、照样在生效。结果 6 份方案里只有 2 份显示 EXECUTED，
 *          仪表盘的「已定稿方案」也跟着少算。
 *
 *          <p>现在只有"同一张订单（或同一份方案的新版本）"才替代；整窗求解方案（没有订单归属）
 *          仍按时间窗判定，由 {@code InternalDispatchWorkflowTest} 的 revise 用例守住。</p>
 */
@QuarkusTest
@TestSecurity(user = "plan-scope-admin", roles = { "ADMIN", "DISPATCHER", "APPROVER" })
@EnabledIfSystemProperty(named = "quarkus.test.profile", matches = "test-(sqlite|postgresql)")
class PlanSupersedeScopeTest {

    private static final DateTimeFormatter DB = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Test
    void publishingAnotherOrdersPlanKeepsTheSiblingExecuted() {
        Fixture fixture = QuarkusTransaction.requiringNew().call(PlanSupersedeScopeTest::fixture);

        int planA = createAndPublish(fixture, "SUP-A", fixture.employeeA);
        int planB = createAndPublish(fixture, "SUP-B", fixture.employeeB);

        // 两张订单同一个月、同一天，但互不相干：两份方案都应保持在 EXECUTED。
        given().queryParam("structureId", fixture.structureId).when().get("/schedule-plans/{id}", planA)
                .then().statusCode(200)
                .body("status", equalTo("EXECUTED"))
                .body("supersededByPlanId", nullValue());
        given().queryParam("structureId", fixture.structureId).when().get("/schedule-plans/{id}", planB)
                .then().statusCode(200)
                .body("status", equalTo("EXECUTED"));

        // 两份方案的班次都真的在生效（不是"状态好看、数据没写"）。
        given().queryParam("structureId", fixture.structureId).when().get("/schedule-plans")
                .then().statusCode(200)
                .body("findAll { it.status == 'EXECUTED' }.size()", equalTo(2));
    }

    /**
     * @brief 定稿弹窗的影响预览必须与真实结果一致。
     *
     * @details 前端原来自己按"时间窗重叠"估算，和后端判据分叉：弹窗对 final-test-01 说
     *          「4 个已定稿方案将被替代」，实际一个都不会动（2026-10-29 实测）。
     *          判据最终收敛为「是否分配到同一个班次」——不同订单同一时间由不同的人干活是常态。
     *          这里钉住：两份方案时间窗完全相同，但班次集合不相交 → 预览 0 个目标，定稿后 A 仍在生效。
     */
    @Test
    void publishPreviewMatchesTheActualSupersedeScope() {
        Fixture fixture = QuarkusTransaction.requiringNew().call(PlanSupersedeScopeTest::fixture);
        int planA = createAndPublish(fixture, "PVA", fixture.employeeA);

        Draft draft = createDraft(fixture, "PVB");
        given().queryParam("structureId", fixture.structureId).when()
                .get("/schedule-plans/{id}/supersede-preview", draft.planId)
                .then().statusCode(200)
                .body("targets.size()", equalTo(0))
                .body("orderCodes", equalTo(List.of(draft.orderCode)));

        assignAndPublish(fixture, draft, fixture.employeeB);
        given().queryParam("structureId", fixture.structureId).when().get("/schedule-plans/{id}", planA)
                .then().statusCode(200).body("status", equalTo("EXECUTED"));
    }

    private static int createAndPublish(Fixture fixture, String prefix, int employeeId) {
        return assignAndPublish(fixture, createDraft(fixture, prefix), employeeId);
    }

    /** 建一张 APPROVED 订单 + 一个阶段，并生成它的草案（未派人）。 */
    private static Draft createDraft(Fixture fixture, String prefix) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String orderCode = prefix + "-" + suffix;
        int orderId = given().contentType(ContentType.JSON).queryParam("structureId", fixture.structureId)
                .body(Map.of("orderCode", orderCode, "customerName", "Scope Customer",
                        "projectName", "Scope Audit", "status", "APPROVED", "priority", 3,
                        "ownerName", "Dispatcher", "notes", "supersede scope test"))
                .when().post("/audit-orders").then().statusCode(201)
                .extract().jsonPath().getInt("id");
        given().contentType(ContentType.JSON).queryParam("structureId", fixture.structureId)
                .body(Map.ofEntries(Map.entry("phaseCode", "P1"), Map.entry("phaseName", "现场审核"),
                        Map.entry("startTime", fixture.start), Map.entry("endTime", fixture.end),
                        Map.entry("locationId", fixture.locationId), Map.entry("requiredHeadcount", 1),
                        Map.entry("leadRequired", false), Map.entry("continuityGroup", ""),
                        Map.entry("deliveryMode", "ONSITE"), Map.entry("status", "READY"),
                        Map.entry("requiredSkills", List.of())))
                .when().post("/audit-orders/{id}/phases", orderId).then().statusCode(201);

        Response plan = given().contentType(ContentType.JSON).queryParam("structureId", fixture.structureId)
                .body(Map.of("name", prefix + " plan " + suffix))
                .when().post("/schedule-plans/from-order/{orderId}", orderId).then().statusCode(201)
                .extract().response();
        return new Draft(orderId, orderCode, plan.jsonPath().getInt("id"),
                plan.jsonPath().getInt("assignments[0].id"));
    }

    /** 给草案锁定一个人并定稿。 */
    private static int assignAndPublish(Fixture fixture, Draft draft, int employeeId) {
        given().contentType(ContentType.JSON).queryParam("structureId", fixture.structureId)
                .body(Map.of("employeeId", employeeId, "locked", true, "assignmentRole", "AUDITOR",
                        "changeReason", "验收：同窗口另一张单的方案不得取代本方案"))
                .when().put("/schedule-plans/{id}/assignments/{assignmentId}", draft.planId, draft.assignmentId)
                .then().statusCode(200);
        given().contentType(ContentType.JSON).queryParam("structureId", fixture.structureId).when()
                .post("/schedule-plans/{id}/publish", draft.planId).then().statusCode(200)
                .body("status", equalTo("EXECUTED"));
        return draft.planId;
    }

    private static Fixture fixture() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        StructureEntity structure = new StructureEntity();
        structure.name = "Scope " + suffix;
        structure.persistAndFlush();

        LocationEntity location = new LocationEntity();
        location.structureId = structure.id; location.code = "SLOC-" + suffix; location.name = "Shanghai";
        location.active = true; location.latitude = 31.2304; location.longitude = 121.4737;
        location.persistAndFlush();

        String start = LocalDateTime.now().plusDays(45).withHour(9).withMinute(0).withSecond(0).withNano(0)
                .format(DB);
        String end = LocalDateTime.now().plusDays(45).withHour(18).withMinute(0).withSecond(0).withNano(0)
                .format(DB);
        return new Fixture(structure.id, location.id,
                employee(structure.id, location.id, "SA-" + suffix),
                employee(structure.id, location.id, "SB-" + suffix), start, end);
    }

    private static int employee(int structureId, int locationId, String code) {
        EmployeeEntity employee = new EmployeeEntity();
        employee.structureId = structureId; employee.code = code;
        employee.firstName = code; employee.lastName = "Auditor"; employee.email = "";
        employee.active = true; employee.operationalStatus = "AVAILABLE";
        employee.currentLocationId = locationId;
        employee.statusUpdatedAt = LocalDateTime.now().format(DB);
        employee.persistAndFlush();
        return employee.id;
    }

    private record Fixture(int structureId, int locationId, int employeeA, int employeeB,
            String start, String end) {
    }

    private record Draft(int orderId, String orderCode, int planId, int assignmentId) {
    }
}
