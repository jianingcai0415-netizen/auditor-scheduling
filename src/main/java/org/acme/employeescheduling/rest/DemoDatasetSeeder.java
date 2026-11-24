package org.acme.employeescheduling.rest;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import javax.sql.DataSource;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

/** Loads the optional, database-portable sample dataset used by test installations. */
@ApplicationScoped
public class DemoDatasetSeeder {

    static final String DEMO_STRUCTURE_NAME = "演示部门";
    private static final String DEMO_STRUCTURE_ADDRESS = "示例市示例路 1 号";
    private static final DateTimeFormatter DB_DATE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Logger LOGGER = Logger.getLogger(DemoDatasetSeeder.class.getName());

    @Inject
    DataSource dataSource;

    @ConfigProperty(name = "app.demo-data.enabled", defaultValue = "true")
    boolean enabled;

    void onStart(@Observes StartupEvent ignored) {
        if (!enabled) return;
        LocalDate firstMonday = LocalDate.now().with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY));
        try (Connection connection = dataSource.getConnection()) {
            if (seed(connection, firstMonday)) {
                LOGGER.info("Demo dataset created for " + DEMO_STRUCTURE_NAME + ".");
            } else {
                LOGGER.info("Database already has structures; the demo dataset was not added.");
            }
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot create the demo dataset", exception);
        }
    }

    /**
     * Inserts one sample structure, complete with orders, phases and a few assignments.
     *
     * <p>只在一个<b>没人动过</b>的库里播种：库里只剩 V1 迁移自带的 {@code Default} 引导部门、
     * 且没有任何员工和订单时才写。真实安装早就有人建过数据，此时静默跳过——把示例订单混进
     * 别人的生产数据里，比没有示例更糟。</p>
     *
     * @return true when the dataset was inserted, false when the database already had data
     */
    static boolean seed(Connection connection, LocalDate firstMonday) throws Exception {
        if (!isPristineDatabase(connection)) return false;

        boolean originalAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            int structureId = adoptOrInsertStructure(connection);

            List<Integer> skills = new ArrayList<>();
            String[] skillNames = {"资料受理", "现场审核", "抽样检验", "体系审核", "专业审核", "报告评审"};
            for (int index = 0; index < skillNames.length; index++) {
                skills.add(insert(connection,
                        "INSERT INTO skills (structure_id,name,skill_order,active) VALUES (?,?,?,1)",
                        structureId, skillNames[index], index + 1));
            }

            List<Integer> locations = new ArrayList<>();
            // 坐标用 GCJ-02（高德坐标系），与地图页直接可用。
            String[][] locationRows = {
                    {"DEMO-L-001", "北京", "39.9042", "116.4074"},
                    {"DEMO-L-002", "上海", "31.2304", "121.4737"},
                    {"DEMO-L-003", "广州", "23.1291", "113.2644"},
                    {"DEMO-L-004", "成都", "30.5728", "104.0668"}
            };
            for (int index = 0; index < locationRows.length; index++) {
                locations.add(insert(connection,
                        "INSERT INTO locations (code,name,l_order,structure_id,active,latitude,longitude) "
                                + "VALUES (?,?,?,?,1,?,?)",
                        locationRows[index][0], locationRows[index][1], index + 1, structureId,
                        Double.parseDouble(locationRows[index][2]),
                        Double.parseDouble(locationRows[index][3])));
            }

            List<Integer> employees = new ArrayList<>();
            String[][] employeeRows = {
                    {"DEMO-OP-001", "张伟", "测试"},
                    {"DEMO-OP-002", "王芳", "测试"},
                    {"DEMO-OP-003", "李强", "测试"},
                    {"DEMO-OP-004", "刘洋", "测试"},
                    {"DEMO-OP-005", "陈静", "测试"},
                    {"DEMO-OP-006", "杨帆", "测试"},
                    {"DEMO-OP-007", "赵磊", "测试"},
                    {"DEMO-OP-008", "周敏", "测试"}
            };
            for (String[] employee : employeeRows) {
                employees.add(insert(connection,
                        "INSERT INTO employees (code,first_name,last_name,structure_id,active,email) "
                                + "VALUES (?,?,?,?,1,'')",
                        employee[0], employee[1], employee[2], structureId));
            }

            // 每人两项资格：现场审核是通用项，第二项按人分工。前两位是主任审核员——否则
            // lead_required 的阶段（整改验证）根本选不出人，示例数据一进去就自带红色风险。
            int[][] skillPairs = {{1, 3}, {1, 3}, {1, 2}, {1, 2}, {1, 0}, {1, 0}, {1, 5}, {1, 4}};
            int[] homeCity = {0, 1, 2, 3, 0, 1, 2, 3};
            String validFrom = firstMonday.minusYears(1).toString();
            String validUntil = firstMonday.plusYears(2).toString();
            for (int index = 0; index < employees.size(); index++) {
                for (int skillIndex : skillPairs[index]) {
                    execute(connection, "INSERT INTO employee_skills "
                                    + "(employee_id,skill_id,qualification_level,valid_from,valid_until,"
                                    + "qualification_status) VALUES (?,?,?,?,?,'ACTIVE')",
                            employees.get(index), skills.get(skillIndex),
                            index < 2 ? "LEAD_AUDITOR" : "AUDITOR", validFrom, validUntil);
                }
                // 常驻地决定"从哪儿出发"：没有它，跨城转场成本永远是 0，位置分的示例就失真了。
                execute(connection, "UPDATE employees SET current_location_id=? WHERE id=?",
                        locations.get(homeCity[index]), employees.get(index));
            }

            int[] locationSkillIndexes = {0, 1, 2, 5};
            for (int index = 0; index < locations.size(); index++) {
                execute(connection,
                        "INSERT INTO location_skills (location_id,skill_id,skill_type_id) VALUES (?,?,1)",
                        locations.get(index), skills.get(locationSkillIndexes[index]));
            }

            LocalTime[][] hours = {
                    {LocalTime.of(8, 0), LocalTime.of(14, 0)},
                    {LocalTime.of(14, 0), LocalTime.of(20, 0)},
                    {LocalTime.of(7, 0), LocalTime.of(13, 0)},
                    {LocalTime.of(8, 0), LocalTime.of(16, 0)}
            };
            for (int dayOffset = 0; dayOffset < 14; dayOffset++) {
                LocalDate day = firstMonday.plusDays(dayOffset);
                if (day.getDayOfWeek() == DayOfWeek.SATURDAY || day.getDayOfWeek() == DayOfWeek.SUNDAY) continue;
                for (int locationIndex = 0; locationIndex < locations.size(); locationIndex++) {
                    int shiftId = insert(connection,
                            "INSERT INTO shifts (location_id,start_time,end_time,employee_id,pinned) "
                                    + "VALUES (?,?,?,NULL,0)",
                            locations.get(locationIndex), format(day, hours[locationIndex][0]),
                            format(day, hours[locationIndex][1]));
                    execute(connection,
                            "INSERT INTO shift_skills (shift_id,skill_id,skill_type_id) VALUES (?,?,1)",
                            shiftId, skills.get(locationSkillIndexes[locationIndex]));
                }
            }

            seedOrders(connection, structureId, locations, employees, skills);

            execute(connection,
                    "INSERT INTO general_settings (structure_id,shift_window_mode,auto_populate_from_template) "
                            + "VALUES (?,'month',0)", structureId);
            execute(connection,
                    "UPDATE home_ui_settings SET cover_key='cover1' "
                            + "WHERE id=1 AND cover_key='' AND cover_data_url='' ");

            connection.commit();
            return true;
        } catch (Exception exception) {
            connection.rollback();
            throw exception;
        } finally {
            connection.setAutoCommit(originalAutoCommit);
        }
    }

    /**
     * @brief 复用 V1 迁移留下的 {@code Default} 引导部门，而不是再加一条并排的空部门。
     *
     * <p>否则新装用户第一次打开就会看到两个部门：一个是空的 {@code Default}，一个是演示部门，
     * 而他并不知道该删哪一个。</p>
     */
    private static int adoptOrInsertStructure(Connection connection) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id FROM structures WHERE name='Default'");
                ResultSet result = statement.executeQuery()) {
            if (result.next()) {
                int structureId = result.getInt(1);
                execute(connection, "UPDATE structures SET name=?,address=?,phone=? WHERE id=?",
                        DEMO_STRUCTURE_NAME, DEMO_STRUCTURE_ADDRESS, "010-5550 1000", structureId);
                return structureId;
            }
        }
        return insert(connection, "INSERT INTO structures (name,address,phone) VALUES (?,?,?)",
                DEMO_STRUCTURE_NAME, DEMO_STRUCTURE_ADDRESS, "010-5550 1000");
    }

    /**
     * @brief 只有"没人动过"的库才播种示例数据。
     *
     * <p>不能直接判断「一个部门都没有」：V1 迁移自己会插入一条名为 {@code Default} 的引导部门，
     * 所以任何刚建好的库都有 1 行。判据因此是「只剩这条引导部门，且没有任何员工和订单」。
     * 一旦真实用户建过人、建过单，或者把引导部门改了名，这里就返回 false——示例数据宁可
     * 不出现，也不能混进别人的生产库。</p>
     */
    private static boolean isPristineDatabase(Connection connection) throws Exception {
        long structures = countRows(connection, "structures");
        if (structures == 0) return true;
        if (structures > 1) return false;
        try (PreparedStatement statement = connection.prepareStatement("SELECT name FROM structures");
                ResultSet result = statement.executeQuery()) {
            if (!result.next() || !"Default".equals(result.getString(1))) return false;
        }
        return countRows(connection, "employees") == 0 && countRows(connection, "audit_orders") == 0;
    }

    private static long countRows(Connection connection, String table) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM " + table);
                ResultSet result = statement.executeQuery()) {
            result.next();
            return result.getLong(1);
        }
    }

    /**
     * @brief 四张订单，覆盖审批流的四种状态，并且只有已批准的那两张排了人。
     *
     * <p>为什么不是"全部排满"：空库演示要能同时看到「已排 / 缺人 / 未排」三种徽标，
     * 以及「草稿 → 待审批 → 已批准 → 已派工」这条链路本身。排上去的四个人都满足
     * 资格、等级、同城、无重叠，因此在订单页展开时不会出现红色风险——示例数据要是自带
     * 风险提示，用户第一反应会是"系统坏了"。</p>
     *
     * <p>时间全部相对今天生成（现场审核那段故意跨过今天），这样地图的「此刻在岗」
     * 和排班时间轴的"当前"竖线都有内容，而不是打开一片空白。</p>
     */
    private static void seedOrders(Connection connection, int structureId, List<Integer> locations,
            List<Integer> employees, List<Integer> skills) throws Exception {
        LocalDate today = LocalDate.now();
        String now = LocalDateTime.now().format(DB_DATE_TIME);
        int beijing = locations.get(0);
        int shanghai = locations.get(1);
        int guangzhou = locations.get(2);
        int chengdu = locations.get(3);
        int documentReview = skills.get(0);
        int onSite = skills.get(1);

        // ── 已批准且已排人（北京） ─────────────────────────────────────────────
        int order1 = insertOrder(connection, structureId, "DEMO-ORD-001", "示例客户 · 华北装备制造",
                "质量管理体系初次认证（2026）", "APPROVED", 2, "王敏", now,
                format(today.minusDays(16), LocalTime.of(9, 0)),
                format(today.minusDays(15), LocalTime.of(10, 0)));
        assign(connection, insertPhase(connection, order1, "DEMO-ORD-001", "P1", "文件审查",
                format(today.minusDays(6), LocalTime.of(9, 0)),
                format(today.minusDays(5), LocalTime.of(18, 0)),
                beijing, 1, false, "READY", documentReview, now).get(0), employees.get(4));
        List<Integer> onSiteBeijing = insertPhase(connection, order1, "DEMO-ORD-001", "P2", "现场审核",
                format(today.minusDays(1), LocalTime.of(9, 0)),
                format(today.plusDays(1), LocalTime.of(18, 0)),
                beijing, 2, true, "READY", onSite, now);
        assign(connection, onSiteBeijing.get(0), employees.get(0));
        assign(connection, onSiteBeijing.get(1), employees.get(4));
        assign(connection, insertPhase(connection, order1, "DEMO-ORD-001", "P3", "整改验证",
                format(today.plusDays(18), LocalTime.of(9, 0)),
                format(today.plusDays(19), LocalTime.of(18, 0)),
                beijing, 1, true, "READY", onSite, now).get(0), employees.get(0));

        // ── 已批准但一个都没排（上海）：用来演示"缺人"和候选打分 ────────────────
        int order2 = insertOrder(connection, structureId, "DEMO-ORD-002", "示例客户 · 华东精密仪器",
                "环境管理体系监督审核（2026）", "APPROVED", 3, "李振", now,
                format(today.minusDays(9), LocalTime.of(14, 0)),
                format(today.minusDays(8), LocalTime.of(9, 30)));
        insertPhase(connection, order2, "DEMO-ORD-002", "P1", "文件审查",
                format(today.plusDays(5), LocalTime.of(9, 0)),
                format(today.plusDays(6), LocalTime.of(18, 0)),
                shanghai, 1, false, "READY", documentReview, now);
        insertPhase(connection, order2, "DEMO-ORD-002", "P2", "现场审核",
                format(today.plusDays(11), LocalTime.of(9, 0)),
                format(today.plusDays(15), LocalTime.of(18, 0)),
                shanghai, 2, true, "READY", onSite, now);
        insertPhase(connection, order2, "DEMO-ORD-002", "P3", "整改验证",
                format(today.plusDays(22), LocalTime.of(9, 0)),
                format(today.plusDays(23), LocalTime.of(18, 0)),
                shanghai, 1, true, "READY", onSite, now);

        // ── 已提交待审批（广州）：审批门禁的演示样本 ───────────────────────────
        int order3 = insertOrder(connection, structureId, "DEMO-ORD-003", "示例客户 · 华南电子",
                "职业健康安全体系初次认证（2027）", "SUBMITTED", 4, "周航", now,
                format(today.minusDays(2), LocalTime.of(16, 0)), null);
        insertPhase(connection, order3, "DEMO-ORD-003", "P1", "文件审查",
                format(today.plusDays(9), LocalTime.of(9, 0)),
                format(today.plusDays(10), LocalTime.of(18, 0)),
                guangzhou, 1, false, "PLANNED", documentReview, now);
        insertPhase(connection, order3, "DEMO-ORD-003", "P2", "现场审核",
                format(today.plusDays(16), LocalTime.of(9, 0)),
                format(today.plusDays(20), LocalTime.of(18, 0)),
                guangzhou, 2, true, "PLANNED", onSite, now);
        insertPhase(connection, order3, "DEMO-ORD-003", "P3", "整改验证",
                format(today.plusDays(30), LocalTime.of(9, 0)),
                format(today.plusDays(31), LocalTime.of(18, 0)),
                guangzhou, 1, true, "PLANNED", onSite, now);

        // ── 草稿（成都）：还没提交，阶段可以先改 ───────────────────────────────
        int order4 = insertOrder(connection, structureId, "DEMO-ORD-004", "示例客户 · 西南食品",
                "食品安全管理体系初次认证（2027）", "DRAFT", 5, "王敏", now, null, null);
        insertPhase(connection, order4, "DEMO-ORD-004", "P1", "文件审查",
                format(today.plusDays(14), LocalTime.of(9, 0)),
                format(today.plusDays(15), LocalTime.of(18, 0)),
                chengdu, 1, false, "PLANNED", documentReview, now);
        insertPhase(connection, order4, "DEMO-ORD-004", "P2", "现场审核",
                format(today.plusDays(21), LocalTime.of(9, 0)),
                format(today.plusDays(25), LocalTime.of(18, 0)),
                chengdu, 2, true, "PLANNED", onSite, now);
    }

    private static int insertOrder(Connection connection, int structureId, String code, String customer,
            String project, String status, int priority, String owner, String now,
            String submittedAt, String approvedAt) throws Exception {
        return insert(connection, "INSERT INTO audit_orders (structure_id,order_code,customer_name,"
                        + "project_name,status,priority,owner_name,notes,created_at,updated_at,"
                        + "submitted_at,approved_at,approved_by,reject_reason) "
                        + "VALUES (?,?,?,?,?,?,?,'',?,?,?,?,'','')",
                structureId, code, customer, project, status, priority, owner, now, now,
                submittedAt, approvedAt);
    }

    /** @brief 建阶段并像 {@code AuditOrderResource.materialize()} 那样落出 N 个名额，返回班次 id。 */
    private static List<Integer> insertPhase(Connection connection, int orderId, String orderCode,
            String phaseCode, String phaseName, String start, String end, int locationId,
            int headcount, boolean leadRequired, String status, int skillId, String now) throws Exception {
        int phaseId = insert(connection, "INSERT INTO audit_order_phases (audit_order_id,phase_code,"
                        + "phase_name,start_time,end_time,location_id,required_headcount,lead_required,"
                        + "continuity_group,delivery_mode,status,created_at,updated_at) "
                        + "VALUES (?,?,?,?,?,?,?,?,'','ONSITE',?,?,?)",
                orderId, phaseCode, phaseName, start, end, locationId, headcount,
                leadRequired ? 1 : 0, status, now, now);
        execute(connection, "INSERT INTO audit_phase_skills (phase_id,skill_id,minimum_count,lead_only) "
                + "VALUES (?,?,?,0)", phaseId, skillId, headcount);
        List<Integer> shifts = new ArrayList<>();
        for (int slot = 0; slot < headcount; slot++) {
            int shiftId = insert(connection, "INSERT INTO shifts (location_id,start_time,end_time,"
                            + "employee_id,pinned,audit_order_code,audit_stage,audit_order_phase_id,"
                            + "required_role) VALUES (?,?,?,NULL,0,?,?,?,?)",
                    locationId, start, end, orderCode, phaseName, phaseId,
                    leadRequired && slot == 0 ? "LEAD_AUDITOR" : "AUDITOR");
            execute(connection, "INSERT INTO shift_skills (shift_id,skill_id,skill_type_id) VALUES (?,?,1)",
                    shiftId, skillId);
            shifts.add(shiftId);
        }
        return shifts;
    }

    private static void assign(Connection connection, int shiftId, int employeeId) throws Exception {
        execute(connection, "UPDATE shifts SET employee_id=? WHERE id=?", employeeId, shiftId);
    }

    private static int insert(Connection connection, String sql, Object... values) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            bind(statement, values);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (!keys.next()) throw new IllegalStateException("Database did not return an inserted row identifier");
                return keys.getInt(1);
            }
        }
    }

    private static void execute(Connection connection, String sql, Object... values) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, values);
            statement.executeUpdate();
        }
    }

    private static void bind(PreparedStatement statement, Object... values) throws Exception {
        for (int index = 0; index < values.length; index++) statement.setObject(index + 1, values[index]);
    }

    private static String format(LocalDate day, LocalTime time) {
        return LocalDateTime.of(day, time).format(DB_DATE_TIME);
    }
}
