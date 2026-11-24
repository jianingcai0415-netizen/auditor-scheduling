package org.acme.employeescheduling.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DemoDatasetSeederTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void sampleDatasetIsCompleteAndIdempotent() throws Exception {
        Class.forName("org.sqlite.JDBC");
        Path database = temporaryDirectory.resolve("demo-seed.db");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database)) {
            createSchema(connection);

            assertTrue(DemoDatasetSeeder.seed(connection, LocalDate.of(2030, 1, 7)));
            assertEquals(1, count(connection, "structures", "name='演示部门'"));
            assertEquals(6, count(connection, "skills", "1=1"));
            assertEquals(4, count(connection, "locations", "1=1"));
            assertEquals(8, count(connection, "employees", "1=1"));
            assertEquals(16, count(connection, "employee_skills", "1=1"));
            assertEquals(40, count(connection, "shifts", "1=1 AND audit_order_phase_id IS NULL"));
            assertEquals(55, count(connection, "shift_skills", "1=1"));
            assertEquals("cover1", value(connection, "SELECT cover_key FROM home_ui_settings WHERE id=1"));

            // 订单样本：四张单覆盖四种状态，十一个阶段，十五个名额（其中四个已派人）。
            assertEquals(4, count(connection, "audit_orders", "1=1"));
            assertEquals(2, count(connection, "audit_orders", "status='APPROVED'"));
            assertEquals(11, count(connection, "audit_order_phases", "1=1"));
            assertEquals(11, count(connection, "audit_phase_skills", "1=1"));
            assertEquals(15, count(connection, "shifts", "audit_order_phase_id IS NOT NULL"));
            assertEquals(4, count(connection, "shifts", "employee_id IS NOT NULL"));
            assertEquals(7, count(connection, "shifts", "required_role='LEAD_AUDITOR'"));
            // 常驻地必须落在示例的四个城市之一，否则跨城转场算不出来。
            assertEquals(8, count(connection, "employees", "current_location_id IS NOT NULL"));

            assertFalse(DemoDatasetSeeder.seed(connection, LocalDate.of(2030, 2, 4)));
            assertEquals(8, count(connection, "employees", "1=1"));
            assertEquals(55, count(connection, "shifts", "1=1"));
            assertEquals(4, count(connection, "audit_orders", "1=1"));
        }
    }

    /**
     * 真实启动路径：Flyway 建完库后，库里已经有一条 V1 自带的 {@code Default} 引导部门。
     * 播种必须照常发生，否则空库演示永远是空的——这正是第一次实现时踩的坑。
     */
    @Test
    void seedsOnAFreshlyMigratedDatabaseThatStillHasTheBootstrapStructure() throws Exception {
        Class.forName("org.sqlite.JDBC");
        Path database = temporaryDirectory.resolve("migrated.db");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database)) {
            createSchema(connection);
            try (Statement statement = connection.createStatement()) {
                statement.execute(
                        "INSERT INTO structures (id,name,address,phone) VALUES (1,'Default','','')");
            }

            assertTrue(DemoDatasetSeeder.seed(connection, LocalDate.of(2030, 1, 7)));
            assertEquals(4, count(connection, "audit_orders", "1=1"));
            assertEquals(8, count(connection, "employees", "1=1"));
            // 引导部门被就地改造成演示部门，不能留下一个空部门并排显示。
            assertEquals(1, count(connection, "structures", "1=1"));
            assertEquals(1, count(connection, "structures", "name='演示部门'"));
        }
    }

    private static void createSchema(Connection connection) throws Exception {        String[] statements = {
                "CREATE TABLE structures (id INTEGER PRIMARY KEY AUTOINCREMENT,name TEXT,address TEXT,phone TEXT)",
                "CREATE TABLE skills (id INTEGER PRIMARY KEY AUTOINCREMENT,structure_id INTEGER,name TEXT,skill_order INTEGER,active INTEGER)",
                "CREATE TABLE locations (id INTEGER PRIMARY KEY AUTOINCREMENT,code TEXT UNIQUE,name TEXT,l_order INTEGER,structure_id INTEGER,active INTEGER,latitude REAL,longitude REAL)",
                "CREATE TABLE employees (id INTEGER PRIMARY KEY AUTOINCREMENT,code TEXT UNIQUE,first_name TEXT,last_name TEXT,structure_id INTEGER,active INTEGER,email TEXT,current_location_id INTEGER)",
                "CREATE TABLE employee_skills (id INTEGER PRIMARY KEY AUTOINCREMENT,employee_id INTEGER,skill_id INTEGER,"
                        + "certificate_number TEXT NOT NULL DEFAULT '',qualification_level TEXT NOT NULL DEFAULT 'AUDITOR',"
                        + "valid_from TEXT,valid_until TEXT,qualification_status TEXT NOT NULL DEFAULT 'ACTIVE',"
                        + "UNIQUE(employee_id,skill_id))",
                "CREATE TABLE location_skills (id INTEGER PRIMARY KEY AUTOINCREMENT,location_id INTEGER,skill_id INTEGER,skill_type_id INTEGER,UNIQUE(location_id,skill_id,skill_type_id))",
                "CREATE TABLE audit_orders (id INTEGER PRIMARY KEY AUTOINCREMENT,structure_id INTEGER,order_code TEXT,customer_name TEXT,project_name TEXT,"
                        + "status TEXT DEFAULT 'DRAFT',priority INTEGER DEFAULT 3,owner_name TEXT,notes TEXT DEFAULT '',"
                        + "created_at TEXT,updated_at TEXT,version INTEGER DEFAULT 0,submitted_at TEXT,approved_at TEXT,"
                        + "approved_by TEXT DEFAULT '',reject_reason TEXT DEFAULT '')",
                "CREATE TABLE audit_order_phases (id INTEGER PRIMARY KEY AUTOINCREMENT,audit_order_id INTEGER,phase_code TEXT,phase_name TEXT,"
                        + "start_time TEXT,end_time TEXT,location_id INTEGER,required_headcount INTEGER DEFAULT 1,"
                        + "lead_required INTEGER DEFAULT 0,continuity_group TEXT DEFAULT '',delivery_mode TEXT DEFAULT 'ONSITE',"
                        + "status TEXT DEFAULT 'PLANNED',created_at TEXT,updated_at TEXT,version INTEGER DEFAULT 0)",
                "CREATE TABLE audit_phase_skills (id INTEGER PRIMARY KEY AUTOINCREMENT,phase_id INTEGER,skill_id INTEGER,"
                        + "minimum_count INTEGER DEFAULT 1,lead_only INTEGER DEFAULT 0)",
                "CREATE TABLE shifts (id INTEGER PRIMARY KEY AUTOINCREMENT,location_id INTEGER,start_time TEXT,end_time TEXT,employee_id INTEGER,"
                        + "pinned INTEGER,version INTEGER DEFAULT 0,audit_order_code TEXT DEFAULT '',audit_stage TEXT DEFAULT '',"
                        + "audit_order_phase_id INTEGER,required_role TEXT DEFAULT 'AUDITOR')",
                "CREATE TABLE shift_skills (id INTEGER PRIMARY KEY AUTOINCREMENT,shift_id INTEGER,skill_id INTEGER,skill_type_id INTEGER,UNIQUE(shift_id,skill_id,skill_type_id))",
                "CREATE TABLE general_settings (id INTEGER PRIMARY KEY AUTOINCREMENT,structure_id INTEGER UNIQUE,shift_window_mode TEXT,auto_populate_from_template INTEGER)",
                "CREATE TABLE home_ui_settings (id INTEGER PRIMARY KEY,cover_key TEXT,cover_data_url TEXT,title_key TEXT,body_key TEXT,hint_key TEXT)",
                "INSERT INTO home_ui_settings VALUES (1,'','','home.title','home.body','home.hint')"
        };
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys=ON");
            for (String sql : statements) statement.execute(sql);
        }
    }

    private static long count(Connection connection, String table, String where) throws Exception {
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM " + table + " WHERE " + where)) {
            result.next();
            return result.getLong(1);
        }
    }

    private static String value(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getString(1);
        }
    }
}
