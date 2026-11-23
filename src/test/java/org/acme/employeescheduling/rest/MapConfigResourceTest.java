package org.acme.employeescheduling.rest;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.path.json.JsonPath;

/**
 * 地图配置接口的契约：{@code configured} 必须严格等价于「Key 与安全密钥同时非空」。
 *
 * <p>为什么必须守住「两者缺一不可」：JS API 2.0 没有安全密钥时底图与打点都会失败，
 * 若这里误报 {@code configured=true}，前端就不会显示申请引导，而是一直转圈——用户
 * 无从判断到底是密钥错、网络错还是 Referer 白名单错。</p>
 *
 * <p>本测试<b>不假设</b>部署环境是否已配置密钥：开发机上项目根的
 * {@code config/application.properties} 往往填了真实 Key（Quarkus 会读它），
 * 断言"默认未配置"会随环境变化而假失败。这里只验证两个字段与标志位的一致性。</p>
 */
@QuarkusTest
@EnabledIfSystemProperty(named = "quarkus.test.profile", matches = "test-(sqlite|postgresql)")
@TestSecurity(user = "test-admin", roles = { "ADMIN" })
class MapConfigResourceTest {

    @Test
    void configuredFlagMirrorsThePresenceOfBothValues() {
        JsonPath body = given()
                .when().get("/map-config")
                .then().statusCode(200)
                .extract().jsonPath();

        String key = body.getString("jsKey");
        String securityCode = body.getString("securityCode");
        boolean expected = key != null && !key.isBlank()
                && securityCode != null && !securityCode.isBlank();

        assertEquals(expected, body.getBoolean("configured"),
                "configured 必须等价于「Key 与安全密钥同时非空」：缺任一项时分身都会失败");
    }
}
