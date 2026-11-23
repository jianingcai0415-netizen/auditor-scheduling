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

    @ConfigProperty(name = "app.demo-data.enabled", defaultValue = "false")
    boolean enabled;

    void onStart(@Observes StartupEvent ignored) {
        if (!enabled) return;
        LocalDate firstMonday = LocalDate.now().with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY));
        try (Connection connection = dataSource.getConnection()) {
            if (seed(connection, firstMonday)) {
                LOGGER.info("Optional demo dataset created for " + DEMO_STRUCTURE_NAME + ".");
            } else {
                LOGGER.info("Optional demo dataset already exists; no rows were added.");
            }
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot create the optional demo dataset", exception);
        }
    }

    /**
     * Inserts one deterministic sample structure in a single transaction.
     *
     * @return true when the dataset was inserted, false when it already existed
     */
    static boolean seed(Connection connection, LocalDate firstMonday) throws Exception {
        if (demoStructureExists(connection)) return false;

        boolean originalAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            int structureId = insert(connection,
                    "INSERT INTO structures (name,address,phone) VALUES (?,?,?)",
                    DEMO_STRUCTURE_NAME, DEMO_STRUCTURE_ADDRESS, "010-5550 1000");

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

            for (int index = 0; index < employees.size(); index++) {
                execute(connection, "INSERT INTO employee_skills (employee_id,skill_id) VALUES (?,?)",
                        employees.get(index), skills.get(index % skills.size()));
                execute(connection, "INSERT INTO employee_skills (employee_id,skill_id) VALUES (?,?)",
                        employees.get(index), skills.get((index + 1) % skills.size()));
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

    private static boolean demoStructureExists(Connection connection) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM structures WHERE name=? AND address=?")) {
            statement.setString(1, DEMO_STRUCTURE_NAME);
            statement.setString(2, DEMO_STRUCTURE_ADDRESS);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getLong(1) > 0;
            }
        }
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
