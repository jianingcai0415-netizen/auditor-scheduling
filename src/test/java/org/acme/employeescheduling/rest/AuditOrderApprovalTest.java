package org.acme.employeescheduling.rest;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
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
 * @brief 订单审批契约：审批单元是订单，方案层不再有提交/批准/退回。
 *
 * @details 覆盖四件容易被改坏的事：
 *          1. 合法顺序只能 forward（DRAFT → SUBMITTED → APPROVED）；
 *          2. 非法跃迁返回 409 而不是静默改状态；
 *          3. 「编辑订单」不能成为绕过审批的后门；
 *          4. 退回后可重新提交，且退回原因被保留。
 */
@QuarkusTest
@TestSecurity(user = "approval-admin", roles = {"ADMIN", "DISPATCHER", "APPROVER"})
@EnabledIfSystemProperty(named = "quarkus.test.profile", matches = "test-(sqlite|postgresql)")
class AuditOrderApprovalTest {

    @Test
    void approvalMovesForwardOnlyThroughDedicatedEndpoints() {
        int structureId = newStructure();
        String code = "APR-" + UUID.randomUUID().toString().substring(0, 8);

        int orderId = given().contentType(ContentType.JSON).queryParam("structureId", structureId)
                .body(orderBody(code, "DRAFT"))
                .when().post("/audit-orders").then().statusCode(201)
                .body("status", equalTo("DRAFT"))
                .body("approvedAt", nullValue())
                .extract().jsonPath().getInt("id");

        // 未批准时不能进入排班：这是整条链路的第一道闸门。
        given().queryParam("structureId", structureId).queryParam("orderCode", code)
                .when().get("/schedule-plans/order-candidates").then().statusCode(409)
                .body("error", equalTo("AUDIT_ORDER_NOT_APPROVED"));

        // 跳过提交直接批准 → 非法跃迁
        given().contentType(ContentType.JSON).queryParam("structureId", structureId).when()
                .post("/audit-orders/{id}/approve", orderId).then().statusCode(409)
                .body("error", equalTo("AUDIT_ORDER_INVALID_TRANSITION"));

        addCompliantPhase(structureId, orderId);
        given().contentType(ContentType.JSON).queryParam("structureId", structureId).when()
                .post("/audit-orders/{id}/submit", orderId).then().statusCode(200)
                .body("status", equalTo("SUBMITTED"))
                .body("submittedAt", notNullValue());

        // 重复提交 → 也是非法跃迁，不是静默成功
        given().contentType(ContentType.JSON).queryParam("structureId", structureId).when()
                .post("/audit-orders/{id}/submit", orderId).then().statusCode(409)
                .body("error", equalTo("AUDIT_ORDER_INVALID_TRANSITION"));

        given().contentType(ContentType.JSON).queryParam("structureId", structureId).when()
                .post("/audit-orders/{id}/approve", orderId).then().statusCode(200)
                .body("status", equalTo("APPROVED"))
                .body("approvedBy", equalTo("approval-admin"))
                .body("approvedAt", notNullValue());

        // 已批准后不能再退回（退回只对 SUBMITTED 有效）
        given().contentType(ContentType.JSON).queryParam("structureId", structureId)
                .queryParam("reason", "late").when()
                .post("/audit-orders/{id}/reject", orderId).then().statusCode(409)
                .body("error", equalTo("AUDIT_ORDER_INVALID_TRANSITION"));

        // 编辑订单试图把状态改回 DRAFT：必须被忽略，否则审批可被绕过
        given().contentType(ContentType.JSON).queryParam("structureId", structureId)
                .body(orderBody(code, "DRAFT"))
                .when().put("/audit-orders/{id}", orderId).then().statusCode(200)
                .body("status", equalTo("APPROVED"));

        // 但关闭（取消 / 完成）是允许的：已批准的订单也可能作废
        given().contentType(ContentType.JSON).queryParam("structureId", structureId)
                .body(orderBody(code, "CANCELLED"))
                .when().put("/audit-orders/{id}", orderId).then().statusCode(200)
                .body("status", equalTo("CANCELLED"));

        given().contentType(ContentType.JSON).queryParam("structureId", structureId).when()
                .post("/audit-orders/{id}/submit", orderId).then().statusCode(409)
                .body("error", equalTo("AUDIT_ORDER_INVALID_TRANSITION"));
    }

    @Test
    void rejectedOrderCanBeCorrectedAndResubmitted() {
        int structureId = newStructure();
        String code = "REJ-" + UUID.randomUUID().toString().substring(0, 8);

        int orderId = given().contentType(ContentType.JSON).queryParam("structureId", structureId)
                .body(orderBody(code, "DRAFT"))
                .when().post("/audit-orders").then().statusCode(201)
                .extract().jsonPath().getInt("id");

        addCompliantPhase(structureId, orderId);
        given().contentType(ContentType.JSON).queryParam("structureId", structureId).when()
                .post("/audit-orders/{id}/submit", orderId).then().statusCode(200);
        given().contentType(ContentType.JSON).queryParam("structureId", structureId)
                .queryParam("reason", "时间窗与客户需求不符").when()
                .post("/audit-orders/{id}/reject", orderId).then().statusCode(200)
                .body("status", equalTo("REJECTED"))
                .body("rejectReason", equalTo("时间窗与客户需求不符"));

        // 退回后允许直接修改内容（这是它区别于「已提交」的唯一地方）
        given().contentType(ContentType.JSON).queryParam("structureId", structureId)
                .body(Map.of("orderCode", code, "customerName", "Revised Customer",
                        "projectName", "Pilot Audit", "status", "DRAFT", "priority", 2,
                        "ownerName", "Dispatcher", "notes", "revised"))
                .when().put("/audit-orders/{id}", orderId).then().statusCode(200)
                .body("status", equalTo("DRAFT"));

        given().contentType(ContentType.JSON).queryParam("structureId", structureId).when()
                .post("/audit-orders/{id}/submit", orderId).then().statusCode(200)
                .body("status", equalTo("SUBMITTED"));
    }

    /**
     * @brief 造一条合规阶段，供提交审批前使用。
     *
     * @details 提交门禁要求「至少一个阶段 + 就绪阶段有资格 + 人数 ≥ 1」，
     *          所以这些只关心状态流转的用例也得先有一张完整的"需求单"。
     */
    private static void addCompliantPhase(int structureId, int orderId) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        int[] ids = QuarkusTransaction.requiringNew().call(() -> {
            LocationEntity location = new LocationEntity();
            location.structureId = structureId;
            location.code = "ALOC-" + suffix;
            location.name = "Shanghai";
            location.active = true;
            location.persistAndFlush();
            SkillEntity skill = new SkillEntity();
            skill.structureId = structureId;
            skill.name = "QMS-" + suffix;
            skill.skillOrder = 1;
            skill.active = true;
            skill.persistAndFlush();
            return new int[] { location.id, skill.id };
        });
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        LocalDateTime base = LocalDateTime.now().plusDays(15).withHour(9).withMinute(0).withSecond(0).withNano(0);
        given().contentType(ContentType.JSON).queryParam("structureId", structureId)
                .body(Map.ofEntries(
                        Map.entry("phaseCode", "P1"), Map.entry("phaseName", "现场审核"),
                        Map.entry("startTime", base.format(fmt)),
                        Map.entry("endTime", base.withHour(18).format(fmt)),
                        Map.entry("locationId", ids[0]), Map.entry("requiredHeadcount", 2),
                        Map.entry("leadRequired", false), Map.entry("continuityGroup", ""),
                        Map.entry("deliveryMode", "ONSITE"), Map.entry("status", "READY"),
                        Map.entry("requiredSkills", List.of(Map.of(
                                "skillId", ids[1], "minimumCount", 2, "leadOnly", false)))))
                .when().post("/audit-orders/{id}/phases", orderId).then().statusCode(201);
    }

    private static Map<String, Object> orderBody(String code, String status) {
        return Map.of("orderCode", code, "customerName", "Approval Customer",
                "projectName", "Approval Audit", "status", status, "priority", 3,
                "ownerName", "Dispatcher", "notes", "approval test");
    }

    private static int newStructure() {
        return QuarkusTransaction.requiringNew().call(() -> {
            StructureEntity structure = new StructureEntity();
            structure.name = "Approval " + UUID.randomUUID().toString().substring(0, 8);
            structure.persistAndFlush();
            return structure.id;
        });
    }
}
