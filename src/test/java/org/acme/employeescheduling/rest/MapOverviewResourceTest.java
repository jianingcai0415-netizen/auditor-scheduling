package org.acme.employeescheduling.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static io.restassured.RestAssured.given;

import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import jakarta.inject.Inject;
import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.path.json.JsonPath;

/**
 * 审核员分布地图接口的聚合逻辑。
 *
 * <p>重点守住两条容易出错、错了却"看起来正常"的语义：</p>
 * <ol>
 *   <li><b>在岗判定</b>＝当前时刻有班次覆盖（{@code start <= now <= end}），
 *       而不是 {@code employees.operational_status}（该字段全库都是默认值）。</li>
 *   <li><b>总数要对得上</b>：没有归属到有坐标城市的人进 {@code unassigned} 桶，
 *       而不是被静默丢弃。</li>
 * </ol>
 *
 * <p>造数与读库都走原生 JDBC：Panache 的静态方法依赖字节码增强，增强不改写方法引用，
 * 且测试类不在增强范围内。</p>
 */
@QuarkusTest
@EnabledIfSystemProperty(named = "quarkus.test.profile", matches = "test-(sqlite|postgresql)")
@TestSecurity(user = "test-admin", roles = { "ADMIN" })
class MapOverviewResourceTest {

    private static final int STRUCTURE = 9999;
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Inject
    DataSource dataSource;

    @BeforeEach
    void seed() throws Exception {
        clean();
        LocalDateTime now = LocalDateTime.now();
        String started = now.minusHours(1).format(STAMP);
        String ends = now.plusHours(1).format(STAMP);
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate(
                    "INSERT INTO structures (id, name, address, phone) VALUES (" + STRUCTURE
                            + ", 'MAP-TEST', '', '')");
            // 甲城坐标在乙城之前（规范序上更小），两者都活跃且有坐标。
            statement.executeUpdate("INSERT INTO locations (id, name, l_order, code, structure_id, active,"
                    + " latitude, longitude) VALUES (9001, '甲城', 1, 'T-MAP-A', " + STRUCTURE + ", 1, 30.0, 120.0)");
            statement.executeUpdate("INSERT INTO locations (id, name, l_order, code, structure_id, active,"
                    + " latitude, longitude) VALUES (9002, '乙城', 2, 'T-MAP-B', " + STRUCTURE + ", 1, 31.0, 121.0)");
            // 无坐标城市：其人员应落到 unassigned，而不是消失。
            statement.executeUpdate("INSERT INTO locations (id, name, l_order, code, structure_id, active)"
                    + " VALUES (9003, '丙城-无坐标', 3, 'T-MAP-C', " + STRUCTURE + ", 1)");
            statement.executeUpdate("INSERT INTO employees (id, code, first_name, last_name, structure_id,"
                    + " active, email, current_location_id) VALUES (9001, 'E1', '张', '三', " + STRUCTURE
                    + ", 1, 'a@x', 9001)");
            statement.executeUpdate("INSERT INTO employees (id, code, first_name, last_name, structure_id,"
                    + " active, email, current_location_id) VALUES (9002, 'E2', '李', '四', " + STRUCTURE
                    + ", 1, 'b@x', 9001)");
            statement.executeUpdate("INSERT INTO employees (id, code, first_name, last_name, structure_id,"
                    + " active, email, current_location_id) VALUES (9003, 'E3', '王', '五', " + STRUCTURE
                    + ", 1, 'c@x', 9002)");
            statement.executeUpdate("INSERT INTO employees (id, code, first_name, last_name, structure_id,"
                    + " active, email, current_location_id) VALUES (9004, 'E4', '赵', '六', " + STRUCTURE
                    + ", 1, 'd@x', 9003)");
            // 只有 E1 此刻有班次；E2 的班次在明天（now 口径不该算，today 也不该算，next7 该算）。
            statement.executeUpdate("INSERT INTO shifts (location_id, start_time, end_time, employee_id,"
                    + " audit_order_code, audit_stage) VALUES (9001, '" + started + "', '" + ends
                    + "', 9001, 'ORD-MAP', 'stage1')");
            statement.executeUpdate("INSERT INTO shifts (location_id, start_time, end_time, employee_id)"
                    + " VALUES (9001, '"
                    + now.plusDays(1).format(STAMP) + "', '" + now.plusDays(1).plusHours(8).format(STAMP)
                    + "', 9002)");
        }
    }

    @Test
    void aggregatesAuditorsByCityAndFlagsThoseInScopeNow() {
        JsonPath body = given().queryParam("structureId", STRUCTURE).when().get("/map-overview")
                .then().statusCode(200).extract().jsonPath();

        assertEquals(4, body.getInt("totalAuditors"));
        assertEquals(1, body.getInt("totalInScope"), "默认口径 now：只有班次覆盖当前时刻的人才算在口径内");
        assertEquals(2, body.getInt("totalCities"), "只把有坐标的城市作为点位");

        // 甲城 2 人、其中 1 人此刻有班；符合口径者排在最前面。
        int jia = cityIndex(body, "甲城");
        assertEquals(2, body.getInt("cities[" + jia + "].auditors"));
        assertEquals(1, body.getInt("cities[" + jia + "].inScope"));
        assertTrue(body.getBoolean("cities[" + jia + "].people[0].inScope"), "符合口径的人应排在列表首位");
        assertEquals("E1", body.getString("cities[" + jia + "].people[0].code"));
        assertEquals("ORD-MAP", body.getString("cities[" + jia + "].people[0].orderCode"));
        assertFalse(body.getBoolean("cities[" + jia + "].people[1].inScope"), "班次在明天的人此刻不在 now 口径内");

        int yi = cityIndex(body, "乙城");
        assertEquals(1, body.getInt("cities[" + yi + "].auditors"));
        assertEquals(0, body.getInt("cities[" + yi + "].inScope"));

        // 无坐标城市的人员进 unassigned，不能丢——否则 totalAuditors 对不上。
        assertEquals(1, body.getList("unassigned").size());
        assertEquals("E4", body.getString("unassigned[0].code"));
    }

    /**
     * @brief 口径必须真的改变判定窗口，而不仅仅是换个文案。
     *
     * <p>数据：E1 的班次覆盖此刻；E2 的班次在明天。因此
     * now / today 都只应命中 1 人，next7 应命中 2 人。
     * 这条断言防的是"切换口径但后端忽略参数"——那种 bug 界面上看不出来。</p>
     */
    @Test
    void scopeWidensTheWindow() {
        assertEquals(1, inScopeCount("now"));
        assertEquals(1, inScopeCount("today"), "明天的班次不属于今天");
        assertEquals(2, inScopeCount("next7"), "未来 7 天口径应把明天的班次算进来");

        // 未知取值回落到默认口径，而不是 400：只读总览不该因参数拼错整体报错。
        assertEquals(1, inScopeCount("bogus"));
        assertEquals("now", given().queryParam("structureId", STRUCTURE)
                .queryParam("scope", "bogus")
                .when().get("/map-overview").then().statusCode(200)
                .extract().jsonPath().getString("scope"));
    }

    /**
     * @brief 打点口径必须真的改变归堆方式，而不只是换个文案。
     *
     * <p>数据：E5 常驻甲城、此刻在乙城干活。常驻视图把他挂在甲城（甲城 3 人 / 在岗 2），
     * 现场视图把他挂在乙城（乙城 1 人）。这条断言防的是"切换开关但后端忽略参数"——
     * 界面上两个视图长得一样，只有数字能揭穿。</p>
     */
    @Test
    void onsiteGroupingUsesTheShiftCityNotTheHomeCity() throws Exception {
        LocalDateTime now = LocalDateTime.now();
        String started = now.minusHours(1).format(STAMP);
        String ends = now.plusHours(1).format(STAMP);
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO employees (id, code, first_name, last_name, structure_id,"
                    + " active, email, current_location_id) VALUES (9005, 'E5', '钱', '七', " + STRUCTURE
                    + ", 1, 'e@x', 9001)");
            statement.executeUpdate("INSERT INTO shifts (location_id, start_time, end_time, employee_id,"
                    + " audit_order_code, audit_stage) VALUES (9002, '" + started + "', '" + ends
                    + "', 9005, 'ORD-MAP-2', 'stage2')");
        }

        JsonPath home = given().queryParam("structureId", STRUCTURE).when().get("/map-overview")
                .then().statusCode(200).extract().jsonPath();
        assertEquals("home", home.getString("group"), "默认按常驻地打点");
        int jiaHome = cityIndex(home, "甲城");
        assertEquals(3, home.getInt("cities[" + jiaHome + "].auditors"), "甲城常驻 E1/E2/E5 共 3 人");
        assertEquals(2, home.getInt("cities[" + jiaHome + "].inScope"), "常驻口径下 E1、E5 都算甲城的在岗人");
        assertEquals(0, home.getInt("cities[" + cityIndex(home, "乙城") + "].inScope"),
                "E5 虽然人在乙城，常驻视图不把他算到乙城");

        JsonPath onsite = given().queryParam("structureId", STRUCTURE).queryParam("group", "onsite")
                .when().get("/map-overview").then().statusCode(200).extract().jsonPath();
        assertEquals("onsite", onsite.getString("group"));
        assertEquals(2, onsite.getInt("totalInScope"));
        assertEquals(2, onsite.getInt("totalCities"), "现场视图只回有人的城市：甲城 1 + 乙城 1");
        int jia = cityIndex(onsite, "甲城");
        int yi = cityIndex(onsite, "乙城");
        assertEquals(1, onsite.getInt("cities[" + jia + "].auditors"), "甲城现场只剩 E1");
        assertEquals(1, onsite.getInt("cities[" + yi + "].auditors"), "乙城现场是 E5");
        assertEquals("E5", onsite.getString("cities[" + yi + "].people[0].code"));
        assertEquals("甲城", onsite.getString("cities[" + yi + "].people[0].homeCity"),
                "现场视图要能看出这个人从哪来");
        assertEquals(0, onsite.getList("unassigned").size(), "在岗且任务地有坐标的人不该进 unassigned");

        // 未知取值回落 home，与 scope 的处理一致：只读总览不该因参数拼错整体报错。
        assertEquals("home", given().queryParam("structureId", STRUCTURE).queryParam("group", "bogus")
                .when().get("/map-overview").then().statusCode(200)
                .extract().jsonPath().getString("group"));
    }

    private static int inScopeCount(String scope) {
        return given().queryParam("structureId", STRUCTURE).queryParam("scope", scope)
                .when().get("/map-overview").then().statusCode(200)
                .extract().jsonPath().getInt("totalInScope");
    }

    private static int cityIndex(JsonPath body, String name) {
        int found = -1;
        for (int i = 0; i < body.getList("cities").size(); i++) {
            if (name.equals(body.getString("cities[" + i + "].name"))) {
                found = i;
                break;
            }
        }
        assertTrue(found >= 0, "响应里找不到城市 " + name);
        return found;
    }

    private void clean() throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM shifts WHERE location_id IN (9001, 9002, 9003)");
            statement.executeUpdate("DELETE FROM employees WHERE id IN (9001, 9002, 9003, 9004, 9005)");
            statement.executeUpdate("DELETE FROM locations WHERE id IN (9001, 9002, 9003)");
            statement.executeUpdate("DELETE FROM structures WHERE id = " + STRUCTURE);
        }
    }
}
