package org.acme.employeescheduling.rest;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
 * 提交审批的三道门禁。
 *
 * <p>背景：资格挂在阶段上而不是订单上，但系统原先允许「无阶段 / 阶段无资格 / 人数为 0」
 * 的订单直接提交并被批准。资格为空时 {@code directCandidateRisk} 会整段跳过资格检查，
 * 导致谁都能被推荐——审批等于批了一张排不出合格人员的需求单。
 */
@QuarkusTest
@TestSecurity(user = "gate-admin", roles = {"ADMIN", "CAPOSALA", "DISPATCHER", "APPROVER"})
@EnabledIfSystemProperty(named = "quarkus.test.profile", matches = "test-(sqlite|postgresql)")
class AuditOrderSubmissionGateTest {

    private static final DateTimeFormatter DB = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Test
    void orderWithoutPhasesCannotBeSubmitted() {
        Fixture fixture = QuarkusTransaction.requiringNew().call(AuditOrderSubmissionGateTest::fixture);
        int orderId = createOrder(fixture.structureId);

        given().contentType(ContentType.JSON).queryParam("structureId", fixture.structureId).when()
                .post("/audit-orders/{id}/submit", orderId).then().statusCode(409)
                .body("error", equalTo("AUDIT_ORDER_NO_PHASES"));
    }

    @Test
    void readyPhaseWithoutSkillsCannotBeSubmitted() {
        Fixture fixture = QuarkusTransaction.requiringNew().call(AuditOrderSubmissionGateTest::fixture);
        int orderId = createOrder(fixture.structureId);
        addPhase(fixture, orderId, "P1", 2, "READY", false);

        given().contentType(ContentType.JSON).queryParam("structureId", fixture.structureId).when()
                .post("/audit-orders/{id}/submit", orderId).then().statusCode(409)
                .body("error", equalTo("AUDIT_PHASE_SKILL_REQUIRED"))
                // 错误体要指出是哪个阶段，否则用户得自己翻整张阶段表
                .body("phaseCode", equalTo("P1"));
    }

    /**
     * 人数 0 是双重防线：创建/更新阶段的接口就会拒绝（400），
     * 提交时的检查是兜底——防止历史数据或直接改库留下 0 人阶段。
     */
    @Test
    void zeroHeadcountIsRejectedAtCreationAndAgainAtSubmission() {
        Fixture fixture = QuarkusTransaction.requiringNew().call(AuditOrderSubmissionGateTest::fixture);

        // 第一道：接口层
        int orderId = createOrder(fixture.structureId);
        given().contentType(ContentType.JSON).queryParam("structureId", fixture.structureId)
                .body(Map.ofEntries(Map.entry("phaseCode", "P0"), Map.entry("phaseName", "现场审核"),
                        Map.entry("startTime", fixture.start), Map.entry("endTime", fixture.end),
                        Map.entry("locationId", fixture.locationId), Map.entry("requiredHeadcount", 0),
                        Map.entry("leadRequired", false), Map.entry("continuityGroup", ""),
                        Map.entry("deliveryMode", "ONSITE"), Map.entry("status", "READY"),
                        Map.entry("requiredSkills", List.of())))
                .when().post("/audit-orders/{id}/phases", orderId).then().statusCode(400)
                .body("error", equalTo("AUDIT_PHASE_HEADCOUNT_INVALID"));

        // 第二道：绕过接口直接落库一条 0 人阶段，提交时仍应被拦
        QuarkusTransaction.requiringNew().run(() -> {
            org.acme.employeescheduling.persistence.AuditOrderPhaseEntity phase =
                    new org.acme.employeescheduling.persistence.AuditOrderPhaseEntity();
            phase.auditOrderId = orderId;
            phase.phaseCode = "P9";
            phase.phaseName = "历史脏数据";
            phase.startTime = fixture.start;
            phase.endTime = fixture.end;
            phase.locationId = fixture.locationId;
            phase.requiredHeadcount = 0;
            phase.status = "READY";
            phase.createdAt = phase.updatedAt = LocalDateTime.now().format(DB);
            phase.persistAndFlush();
        });
        given().contentType(ContentType.JSON).queryParam("structureId", fixture.structureId).when()
                .post("/audit-orders/{id}/submit", orderId).then().statusCode(409)
                .body("error", equalTo("AUDIT_PHASE_HEADCOUNT_INVALID"))
                .body("phaseCode", equalTo("P9"));
    }

    @Test
    void plannedPhaseIsExemptFromSkillRequirement() {
        Fixture fixture = QuarkusTransaction.requiringNew().call(AuditOrderSubmissionGateTest::fixture);
        int orderId = createOrder(fixture.structureId);
        // 尚未就绪的阶段不参与候选筛选，强制它填资格只是障碍
        addPhase(fixture, orderId, "P1", 2, "PLANNED", false);

        given().contentType(ContentType.JSON).queryParam("structureId", fixture.structureId).when()
                .post("/audit-orders/{id}/submit", orderId).then().statusCode(200)
                .body("status", equalTo("SUBMITTED"));
    }

    @Test
    void completeOrderPassesAndReachesApproval() {
        Fixture fixture = QuarkusTransaction.requiringNew().call(AuditOrderSubmissionGateTest::fixture);
        int orderId = createOrder(fixture.structureId);
        addPhase(fixture, orderId, "P1", 2, "READY", true);

        given().contentType(ContentType.JSON).queryParam("structureId", fixture.structureId).when()
                .post("/audit-orders/{id}/submit", orderId).then().statusCode(200)
                .body("status", equalTo("SUBMITTED"));
        given().contentType(ContentType.JSON).queryParam("structureId", fixture.structureId).when()
                .post("/audit-orders/{id}/approve", orderId).then().statusCode(200)
                .body("status", equalTo("APPROVED"));
    }

    /** 退回后的订单可以改阶段再提交：门禁是"能不能提交"，不是"只允许提交一次"。 */
    @Test
    void rejectedOrderCanBeFixedAndResubmitted() {
        Fixture fixture = QuarkusTransaction.requiringNew().call(AuditOrderSubmissionGateTest::fixture);
        int orderId = createOrder(fixture.structureId);
        int phaseId = addPhase(fixture, orderId, "P1", 2, "READY", true);

        given().contentType(ContentType.JSON).queryParam("structureId", fixture.structureId).when()
                .post("/audit-orders/{id}/submit", orderId).then().statusCode(200);
        given().contentType(ContentType.JSON).queryParam("structureId", fixture.structureId)
                .queryParam("reason", "客户名称待确认").when()
                .post("/audit-orders/{id}/reject", orderId).then().statusCode(200)
                .body("status", equalTo("REJECTED"));

        // 把资格清空再提交，应被拦住
        given().contentType(ContentType.JSON).queryParam("structureId", fixture.structureId)
                .body(Map.ofEntries(Map.entry("phaseCode", "P1"), Map.entry("phaseName", "现场审核"),
                        Map.entry("startTime", fixture.start), Map.entry("endTime", fixture.end),
                        Map.entry("locationId", fixture.locationId), Map.entry("requiredHeadcount", 2),
                        Map.entry("leadRequired", true), Map.entry("continuityGroup", ""),
                        Map.entry("deliveryMode", "ONSITE"), Map.entry("status", "READY"),
                        Map.entry("requiredSkills", List.of())))
                .when().put("/audit-orders/phases/{id}", phaseId).then().statusCode(200);

        given().contentType(ContentType.JSON).queryParam("structureId", fixture.structureId).when()
                .post("/audit-orders/{id}/submit", orderId).then().statusCode(409)
                .body("error", equalTo("AUDIT_PHASE_SKILL_REQUIRED"));
    }

    private static int createOrder(int structureId) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        return given().contentType(ContentType.JSON).queryParam("structureId", structureId)
                .body(Map.of("orderCode", "GATE-" + suffix, "customerName", "Gate Customer",
                        "projectName", "Gate Audit", "status", "DRAFT", "priority", 3,
                        "ownerName", "Dispatcher", "notes", "submission gate test"))
                .when().post("/audit-orders").then().statusCode(201)
                .body("status", equalTo("DRAFT"))
                .extract().jsonPath().getInt("id");
    }

    private static int addPhase(Fixture fixture, int orderId, String code, int headcount,
            String status, boolean withSkills) {
        Map<String, Object> body = new HashMap<>();
        body.put("phaseCode", code);
        body.put("phaseName", "现场审核");
        body.put("startTime", fixture.start);
        body.put("endTime", fixture.end);
        body.put("locationId", fixture.locationId);
        body.put("requiredHeadcount", headcount);
        body.put("leadRequired", false);
        body.put("continuityGroup", "");
        body.put("deliveryMode", "ONSITE");
        body.put("status", status);
        body.put("requiredSkills", withSkills
                ? List.of(Map.of("skillId", fixture.skillId, "minimumCount", Math.max(1, headcount), "leadOnly", false))
                : List.of());

        return given().contentType(ContentType.JSON).queryParam("structureId", fixture.structureId)
                .body(body).when().post("/audit-orders/{id}/phases", orderId)
                .then().statusCode(201).extract().jsonPath().getInt("id");
    }

    private static Fixture fixture() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        StructureEntity structure = new StructureEntity();
        structure.name = "Gate " + suffix;
        structure.persistAndFlush();

        LocationEntity location = new LocationEntity();
        location.structureId = structure.id;
        location.code = "GLOC-" + suffix;
        location.name = "Shenzhen";
        location.active = true;
        location.latitude = 22.5431;
        location.longitude = 114.0579;
        location.persistAndFlush();

        SkillEntity skill = new SkillEntity();
        skill.structureId = structure.id;
        skill.name = "MDQMS-" + suffix;
        skill.skillOrder = 1;
        skill.active = true;
        skill.persistAndFlush();

        LocalDateTime base = LocalDateTime.now().plusDays(20).withHour(9).withMinute(0).withSecond(0).withNano(0);
        return new Fixture(structure.id, location.id, skill.id,
                base.format(DB), base.withHour(18).format(DB));
    }

    private record Fixture(int structureId, int locationId, int skillId, String start, String end) {
    }
}
