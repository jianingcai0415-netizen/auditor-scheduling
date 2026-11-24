package org.acme.employeescheduling.rest;






import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Status;
import jakarta.transaction.Synchronization;
import jakarta.transaction.Transactional;
import jakarta.transaction.TransactionSynchronizationRegistry;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.acme.employeescheduling.dto.Location;
import org.acme.employeescheduling.domain.EmployeeSchedule;
import org.acme.employeescheduling.domain.Shift;
import org.acme.employeescheduling.dto.EmployeeDate;
import org.acme.employeescheduling.dto.Employee;
import org.acme.employeescheduling.dto.AuditorQualification;
import org.acme.employeescheduling.dto.Skill;
import org.acme.employeescheduling.dto.ShiftTemplate;
import org.acme.employeescheduling.dto.Language;
import org.acme.employeescheduling.dto.EmailTemplate;
import org.acme.employeescheduling.dto.PdfTemplate;
import org.acme.employeescheduling.dto.SolverSettings;
import org.acme.employeescheduling.dto.GeneralSettings;
import org.acme.employeescheduling.dto.HomeUiSettings;
import org.acme.employeescheduling.dto.ShiftAssignment;
import org.acme.employeescheduling.dto.EmailLogEntry;
import org.acme.employeescheduling.dto.EmailSettings;
import org.acme.employeescheduling.persistence.AuditOrderEntity;
import org.acme.employeescheduling.persistence.AuditOrderPhaseEntity;
import org.acme.employeescheduling.persistence.EmployeeDateEntity;
import org.acme.employeescheduling.persistence.EmployeeEntity;
import org.acme.employeescheduling.persistence.EmployeeSkillEntity;
import org.acme.employeescheduling.persistence.LocationEntity;
import org.acme.employeescheduling.persistence.LocationSkillEntity;
import org.acme.employeescheduling.persistence.ShiftEntity;
import org.acme.employeescheduling.persistence.ShiftSkillEntity;
import org.acme.employeescheduling.persistence.ShiftTemplateEntity;
import org.acme.employeescheduling.persistence.ShiftTemplateHeaderEntity;
import org.acme.employeescheduling.persistence.ShiftTemplateSkillEntity;
import org.acme.employeescheduling.persistence.SkillEntity;
import org.acme.employeescheduling.persistence.SolverSettingsEntity;
import org.acme.employeescheduling.persistence.GeneralSettingsEntity;
import org.acme.employeescheduling.persistence.HomeUiSettingsEntity;
import org.acme.employeescheduling.persistence.PdfTemplateEntity;
import org.acme.employeescheduling.persistence.EmailTemplateEntity;
import org.acme.employeescheduling.persistence.EmailSettingsEntity;
import org.acme.employeescheduling.persistence.EmailLogEntity;
import org.acme.employeescheduling.persistence.LanguageEntity;
import org.acme.employeescheduling.persistence.LabelEntity;
import org.acme.employeescheduling.persistence.LocalizzazioneEntity;







/**
 * @brief Repository providing database access for employee scheduling demo data.
 * @details Application-scoped CDI bean that manages all CRUD operations against
 *          an SQLite database for locations, shifts, skills, employees, and their
 *          associated dates. Provides methods for generating demo schedules and
 *          managing the relationships between entities (e.g., location-skill,
 *          shift-skill, employee-skill associations).
 * @author Employee Scheduling Team
 * @version 1.0
 */
@ApplicationScoped
public class DemoDataRepository {
	@Inject
	@ConfigProperty(name = "demo.db.name")

	private String dbName;

	/** JDBC bootstrap allowed only for adopting legacy SQLite installations. */
	@Inject
	@ConfigProperty(name = "app.sqlite.legacy-bootstrap", defaultValue = "true")
	boolean legacySqliteBootstrap;

	@Inject
	EntityManager em;

	@Inject
	TransactionSynchronizationRegistry transactionRegistry;

	private static final Logger logger = Logger.getLogger(DemoDataRepository.class.getName());
	private static final DateTimeFormatter dbFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
	private static final int SQLITE_IN_CHUNK = 900;

	// -----------------------------------------------------------------------
	// Translation cache
	// -----------------------------------------------------------------------
	private volatile Map<String, Map<String, String>> _transCache = null;
	private final Object _cacheLock = new Object();

	public void invalidateTranslationsAfterCommit() {
		try {
			transactionRegistry.registerInterposedSynchronization(new Synchronization() {
				@Override public void beforeCompletion() { }
				@Override public void afterCompletion(int status) {
					if (status == Status.STATUS_COMMITTED) invalidateTranslationsCache();
				}
			});
		} catch (IllegalStateException noTransaction) {
			invalidateTranslationsCache();
		}
	}

	// -----------------------------------------------------------------------
	// Schema/seed init guard
	// -----------------------------------------------------------------------
	// Migrations/seeding (ensure*Table) are idempotent but write hundreds of rows.
	// They must run ONCE per application lifetime, not on every request: otherwise each
	// page load hammers the DB (SQLITE_BUSY under concurrency) and continually dirties
	// the SQLite file.
	private volatile boolean schemaInitialized = false;
	private final Object _schemaLock = new Object();

	/**
	 * @brief Initializes DDL/seeds before the first ORM endpoint can query a legacy DB.
	 *
	 * @details Skipped on a Flyway-managed database even when the legacy bootstrap is enabled.
	 *          The bootstrap is idempotent and changes nothing there, but it rewrites the file
	 *          on every startup: measured on the published demo database, a startup under the
	 *          default profile left the content identical and the bytes different. That is
	 *          enough to make {@code databases/employee_scheduling.db} — which is tracked in
	 *          git — show up as modified after every {@code mvn quarkus:dev}, and to turn each
	 *          commit into an 860 KB binary diff carrying no information.
	 */
	void onStart(@Observes StartupEvent ignored) {
		if (!legacySqliteBootstrap) return;
		try (Connection conn = DatabaseConnection.connect(dbName)) {
			if (hasFlywayHistory(conn)) {
				logger.info("Flyway-managed database: skipping the legacy SQLite bootstrap");
				return;
			}
		} catch (Exception e) {
			// connect() declares a checked Exception. Unreadable connection: fall through to
			// the bootstrap, which is what happened before this check existed.
			logger.log(Level.FINE, "Cannot determine whether Flyway manages this database", e);
		}
		ensureSchemaInitialized();
	}

	private static <T> List<T> loadInChunks(Set<Integer> ids, Function<List<Integer>, List<T>> loader) {
		if (ids == null || ids.isEmpty()) return new ArrayList<>();
		List<Integer> values = new ArrayList<>(ids);
		List<T> result = new ArrayList<>();
		for (int from = 0; from < values.size(); from += SQLITE_IN_CHUNK)
			result.addAll(loader.apply(values.subList(from, Math.min(from + SQLITE_IN_CHUNK, values.size()))));
		return result;
	}

	// -----------------------------------------------------------------------
	// Employees cache (per structureId)
	// -----------------------------------------------------------------------
	// getEmployees has a costly N+1; employee records rarely change. Cache the employee
	// list per structure and reload it from the DB only after a record update (see
	// invalidateEmployeesCache). NOT invalidated by the solver (saveWeekAssignments/unpinWeek
	// touch only shifts, not employee records).
	private volatile Map<Integer, List<Employee>> _employeesCache = null;
	private final Object _employeesCacheLock = new Object();

	/**
	 * @brief Helper class for establishing SQLite database connections.
	 */
	static class DatabaseConnection {
		/**
		 * @brief Establishes a connection to the SQLite database.
		 * @param dbName the path to the SQLite database file
		 * @return a Connection object to the database
		 * @throws Exception if the connection cannot be established
		 */
		public static Connection connect(String dbName) throws Exception {
			String url = "jdbc:sqlite:" + dbName;
			Connection conn = DriverManager.getConnection(url);
			try {
				try (Statement stmt = conn.createStatement()) {
					stmt.execute("PRAGMA busy_timeout = 5000");
					// Some legacy databases contain an incorrect shift_skills FK to
					// shifts_backup. Do not enable enforcement until that schema is migrated
					// explicitly; enabling it blindly would break otherwise valid writes.
					boolean legacyShiftFk = false;
					try (ResultSet rs = stmt.executeQuery("PRAGMA foreign_key_list('shift_skills')")) {
						while (rs.next()) {
							if ("shifts_backup".equalsIgnoreCase(rs.getString("table"))) {
								legacyShiftFk = true;
								break;
							}
						}
					}
					if (!legacyShiftFk) stmt.execute("PRAGMA foreign_keys = ON");
				}
				return conn;
			} catch (Exception e) {
				// The connection has not yet been handed to the caller: if initialization
				// (PRAGMA / FK introspection) fails, close it here or it will be orphaned.
				try { conn.close(); } catch (Exception ignored) {}
				throw e;
			}
		}
	}

	/** Creates indexes used by the hot scheduling and association queries. */
	private void ensurePerformanceIndexes() {
		try (Connection conn = DatabaseConnection.connect(dbName);
			 Statement stmt = conn.createStatement()) {
			// journal_mode is persistent: set it once during schema initialization,
			// not on every connection (where it could itself contend for a lock).
			stmt.execute("PRAGMA journal_mode = WAL");
			stmt.execute("CREATE INDEX IF NOT EXISTS idx_shifts_location_start_end ON shifts(location_id, start_time, end_time)");
			stmt.execute("CREATE INDEX IF NOT EXISTS idx_shift_skills_shift_type_skill ON shift_skills(shift_id, skill_type_id, skill_id)");
			stmt.execute("CREATE INDEX IF NOT EXISTS idx_location_skills_location_type_skill ON location_skills(location_id, skill_type_id, skill_id)");
			stmt.execute("CREATE INDEX IF NOT EXISTS idx_employee_skills_employee_skill ON employee_skills(employee_id, skill_id)");
			stmt.execute("CREATE INDEX IF NOT EXISTS idx_employee_dates_employee_type_start ON employee_dates(employee_id, date_type_id, date_start)");
			stmt.execute("CREATE INDEX IF NOT EXISTS idx_shift_template_skills_template_type_skill ON shift_template_skills(template_id, skill_type_id, skill_id)");
			stmt.execute("CREATE INDEX IF NOT EXISTS idx_shift_templates_structure_day_start ON shift_templates(structure_id, day_of_week, start_time)");
		} catch (Exception e) {
			logger.log(Level.SEVERE, "Error ensuring performance indexes", e);
		}
	}

	/**
	 * @brief Ensures the shifts table has the employee_id and pinned columns (idempotent migration).
	 */
	private void ensureShiftColumns() {
		try (Connection conn = DatabaseConnection.connect(dbName);
			 Statement stmt = conn.createStatement()) {
			try { stmt.execute("ALTER TABLE shifts ADD COLUMN employee_id INTEGER"); } catch (SQLException ignored) {}
			try { stmt.execute("ALTER TABLE shifts ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0"); } catch (SQLException ignored) {}
			// Optimistic revision managed by Hibernate (@Version on ShiftEntity): without this
			// column every ORM shift write fails against a legacy schema.
			//
			// Only if the database is NOT managed by Flyway. Adding it behind Flyway's back
			// looks harmless — the old ALTER failed silently and nobody noticed — but the
			// migration introducing the same column then finds it occupied and fails with
			// "duplicate column name: version", preventing application startup. This was measured
			// on a copy of the development database, not hypothesized. The two columns above do
			// not have this problem: they originate in V1's CREATE TABLE, so no migration ever
			// attempts to add them to an existing table.
			if (!hasFlywayHistory(conn))
				try { stmt.execute("ALTER TABLE shifts ADD COLUMN version INTEGER NOT NULL DEFAULT 0"); } catch (SQLException ignored) {}
		} catch (Exception e) {
			logger.log(Level.SEVERE, "Error during shift column migration", e);
		}
	}

	/**
	 * @brief Is the database managed by Flyway?
	 * @details Where {@code flyway_schema_history} exists, migrations own the schema: legacy
	 *          bootstrap must limit itself to what Flyway does not cover, or it will set up a
	 *          failure for the next migration.
	 */
	private static boolean hasFlywayHistory(Connection conn) {
		try (ResultSet rs = conn.getMetaData().getTables(null, null, "flyway_schema_history", null)) {
			return rs.next();
		} catch (SQLException e) {
			// When uncertain, assume Flyway manages it: omitting a column can be recovered from,
			// while adding an extra one prevents startup.
			return true;
		}
	}

	/**
	 * Adds auditor-dispatch fields to installations that still use the legacy SQLite bootstrap.
	 * Flyway-managed databases receive the same schema through V9__auditor_dispatch.sql.
	 */
	private void ensureAuditorDispatchColumns() {
		try (Connection conn = DatabaseConnection.connect(dbName);
			 Statement stmt = conn.createStatement()) {
			if (hasFlywayHistory(conn)) return;

			String[] additions = {
					"ALTER TABLE employees ADD COLUMN operational_status TEXT NOT NULL DEFAULT 'AVAILABLE'",
					"ALTER TABLE employees ADD COLUMN busy_until TEXT",
					"ALTER TABLE employees ADD COLUMN status_updated_at TEXT",
					"ALTER TABLE employees ADD COLUMN current_location_id INTEGER",
					"ALTER TABLE employees ADD COLUMN buffer_before_minutes INTEGER NOT NULL DEFAULT 30",
					"ALTER TABLE employees ADD COLUMN buffer_after_minutes INTEGER NOT NULL DEFAULT 30",
					"ALTER TABLE employee_skills ADD COLUMN certificate_number TEXT NOT NULL DEFAULT ''",
					"ALTER TABLE employee_skills ADD COLUMN qualification_level TEXT NOT NULL DEFAULT 'AUDITOR'",
					"ALTER TABLE employee_skills ADD COLUMN valid_from TEXT",
					"ALTER TABLE employee_skills ADD COLUMN valid_until TEXT",
					"ALTER TABLE employee_skills ADD COLUMN qualification_status TEXT NOT NULL DEFAULT 'ACTIVE'",
					"ALTER TABLE locations ADD COLUMN latitude REAL",
					"ALTER TABLE locations ADD COLUMN longitude REAL",
					"ALTER TABLE shifts ADD COLUMN audit_order_code TEXT NOT NULL DEFAULT ''",
					"ALTER TABLE shifts ADD COLUMN audit_stage TEXT NOT NULL DEFAULT ''"
			};
			for (String sql : additions) {
				try { stmt.execute(sql); } catch (SQLException ignored) { }
			}
			stmt.executeUpdate("UPDATE employee_skills SET certificate_number = "
					+ "'LEGACY-' || employee_id || '-' || skill_id WHERE certificate_number = ''");
			stmt.execute("CREATE INDEX IF NOT EXISTS idx_employees_busy_until ON employees(busy_until)");
			stmt.execute("CREATE INDEX IF NOT EXISTS idx_employee_skills_validity "
					+ "ON employee_skills(employee_id, skill_id, qualification_status, valid_until)");
			stmt.execute("CREATE INDEX IF NOT EXISTS idx_shifts_audit_order_stage "
					+ "ON shifts(audit_order_code, audit_stage)");
		} catch (Exception e) {
			logger.log(Level.SEVERE, "Error during auditor dispatch column migration", e);
		}
	}

	/** Adds the versioned order/import/approval workflow to legacy SQLite installations. */
	private void ensureInternalDispatchWorkflow() {
		try (Connection conn = DatabaseConnection.connect(dbName); Statement stmt = conn.createStatement()) {
			if (hasFlywayHistory(conn)) return;
			stmt.execute("CREATE TABLE IF NOT EXISTS audit_orders (id INTEGER PRIMARY KEY AUTOINCREMENT,"
					+ "structure_id INTEGER NOT NULL,order_code TEXT NOT NULL,customer_name TEXT NOT NULL DEFAULT '',"
					+ "project_name TEXT NOT NULL DEFAULT '',status TEXT NOT NULL DEFAULT 'DRAFT',priority INTEGER NOT NULL DEFAULT 3,"
					+ "owner_name TEXT NOT NULL DEFAULT '',notes TEXT NOT NULL DEFAULT '',created_at TEXT NOT NULL,updated_at TEXT NOT NULL,"
					+ "version INTEGER NOT NULL DEFAULT 0,UNIQUE(structure_id,order_code))");
			stmt.execute("CREATE TABLE IF NOT EXISTS audit_order_phases (id INTEGER PRIMARY KEY AUTOINCREMENT,"
					+ "audit_order_id INTEGER NOT NULL,phase_code TEXT NOT NULL,phase_name TEXT NOT NULL,start_time TEXT NOT NULL,"
					+ "end_time TEXT NOT NULL,location_id INTEGER NOT NULL,required_headcount INTEGER NOT NULL DEFAULT 1,"
					+ "lead_required INTEGER NOT NULL DEFAULT 0,continuity_group TEXT NOT NULL DEFAULT '',delivery_mode TEXT NOT NULL DEFAULT 'ONSITE',"
					+ "status TEXT NOT NULL DEFAULT 'PLANNED',created_at TEXT NOT NULL,updated_at TEXT NOT NULL,version INTEGER NOT NULL DEFAULT 0,"
					+ "UNIQUE(audit_order_id,phase_code),FOREIGN KEY(audit_order_id) REFERENCES audit_orders(id) ON DELETE CASCADE)");
			stmt.execute("CREATE TABLE IF NOT EXISTS audit_phase_skills (id INTEGER PRIMARY KEY AUTOINCREMENT,"
					+ "phase_id INTEGER NOT NULL,skill_id INTEGER NOT NULL,minimum_count INTEGER NOT NULL DEFAULT 1,"
					+ "lead_only INTEGER NOT NULL DEFAULT 0,UNIQUE(phase_id,skill_id,lead_only),"
					+ "FOREIGN KEY(phase_id) REFERENCES audit_order_phases(id) ON DELETE CASCADE)");
			for (String sql : List.of(
					"ALTER TABLE shifts ADD COLUMN audit_order_phase_id INTEGER",
					"ALTER TABLE shifts ADD COLUMN published_plan_id INTEGER",
					"ALTER TABLE shifts ADD COLUMN required_role TEXT NOT NULL DEFAULT 'AUDITOR'")) {
				try { stmt.execute(sql); } catch (SQLException ignored) { }
			}
			stmt.execute("CREATE TABLE IF NOT EXISTS schedule_plans (id INTEGER PRIMARY KEY AUTOINCREMENT,"
					+ "structure_id INTEGER NOT NULL,name TEXT NOT NULL,window_start TEXT NOT NULL,window_end TEXT NOT NULL,"
					+ "status TEXT NOT NULL DEFAULT 'DRAFT',revision INTEGER NOT NULL DEFAULT 1,parent_plan_id INTEGER,solver_score TEXT,"
					+ "created_by TEXT NOT NULL,submitted_by TEXT,approved_by TEXT,published_by TEXT,rejection_reason TEXT NOT NULL DEFAULT '',"
					+ "created_at TEXT NOT NULL,updated_at TEXT NOT NULL,submitted_at TEXT,approved_at TEXT,published_at TEXT,version INTEGER NOT NULL DEFAULT 0)");
			stmt.execute("CREATE TABLE IF NOT EXISTS schedule_plan_assignments (id INTEGER PRIMARY KEY AUTOINCREMENT,"
					+ "plan_id INTEGER NOT NULL,shift_id INTEGER NOT NULL,phase_id INTEGER,slot_index INTEGER NOT NULL DEFAULT 1,employee_id INTEGER,"
					+ "locked INTEGER NOT NULL DEFAULT 0,assignment_role TEXT NOT NULL DEFAULT 'AUDITOR',risk_code TEXT NOT NULL DEFAULT '',"
					+ "change_reason TEXT NOT NULL DEFAULT '',shift_version INTEGER NOT NULL DEFAULT 0,employee_status_updated_at TEXT NOT NULL DEFAULT '',"
					+ "UNIQUE(plan_id,shift_id),FOREIGN KEY(plan_id) REFERENCES schedule_plans(id) ON DELETE CASCADE)");
			stmt.execute("CREATE TABLE IF NOT EXISTS import_jobs (id INTEGER PRIMARY KEY AUTOINCREMENT,token TEXT NOT NULL UNIQUE,"
					+ "structure_id INTEGER NOT NULL,file_name TEXT NOT NULL,file_sha256 TEXT NOT NULL,status TEXT NOT NULL DEFAULT 'PREVIEW',"
					+ "row_count INTEGER NOT NULL DEFAULT 0,error_count INTEGER NOT NULL DEFAULT 0,warning_count INTEGER NOT NULL DEFAULT 0,"
					+ "summary_json TEXT NOT NULL DEFAULT '{}',payload_json TEXT NOT NULL DEFAULT '{}',errors_json TEXT NOT NULL DEFAULT '[]',"
					+ "created_by TEXT NOT NULL,created_at TEXT NOT NULL,confirmed_by TEXT,confirmed_at TEXT)");
			stmt.execute("CREATE TABLE IF NOT EXISTS audit_events (id INTEGER PRIMARY KEY AUTOINCREMENT,structure_id INTEGER,"
					+ "actor TEXT NOT NULL,action TEXT NOT NULL,entity_type TEXT NOT NULL,entity_id TEXT NOT NULL DEFAULT '',"
					+ "details_json TEXT NOT NULL DEFAULT '{}',created_at TEXT NOT NULL)");
			for (String sql : List.of(
					"CREATE INDEX IF NOT EXISTS idx_audit_orders_structure_status ON audit_orders(structure_id,status)",
					"CREATE INDEX IF NOT EXISTS idx_audit_phases_order_start ON audit_order_phases(audit_order_id,start_time)",
					"CREATE INDEX IF NOT EXISTS idx_phase_skills_phase ON audit_phase_skills(phase_id)",
					"CREATE INDEX IF NOT EXISTS idx_shifts_phase ON shifts(audit_order_phase_id)",
					"CREATE INDEX IF NOT EXISTS idx_plans_structure_status ON schedule_plans(structure_id,status,updated_at)",
					"CREATE INDEX IF NOT EXISTS idx_plan_assignments_plan ON schedule_plan_assignments(plan_id)",
					"CREATE INDEX IF NOT EXISTS idx_import_jobs_structure_created ON import_jobs(structure_id,created_at)",
					"CREATE INDEX IF NOT EXISTS idx_audit_events_structure_created ON audit_events(structure_id,created_at)")) stmt.execute(sql);
		} catch (Exception exception) {
			throw new IllegalStateException("Cannot ensure internal dispatch workflow", exception);
		}
	}

	/**
	 * @brief Retrieves all locations from the database ordered by their display order.
	 * @return a list of Location objects, or an empty list if none are found or an error occurs
	 */
	public List<Location> getLocations(int structureId) {
		List<LocationEntity> entities = LocationEntity.list(
				"structureId = ?1 order by displayOrder, id", structureId);
		Map<Integer, SkillEntity> skills = SkillEntity.<SkillEntity>list("order by skillOrder, id").stream()
				.collect(Collectors.toMap(s -> s.id, s -> s, (a, b) -> a, LinkedHashMap::new));
		Set<Integer> locationIds = entities.stream().map(entity -> entity.id).collect(Collectors.toSet());
		Map<Integer, List<LocationSkillEntity>> links = loadInChunks(locationIds,
				chunk -> LocationSkillEntity.<LocationSkillEntity>list("locationId in ?1", chunk)).stream()
				.collect(Collectors.groupingBy(link -> link.locationId));
		List<Location> locations = new ArrayList<>(entities.size());
		for (LocationEntity entity : entities) {
			Location location = entity.toDto();
			location.setRequiredSkill(new ArrayList<>());
			location.setOptionalSkill(new ArrayList<>());
			for (LocationSkillEntity link : links.getOrDefault(entity.id, List.of())) {
				SkillEntity skill = skills.get(link.skillId);
				if (skill == null) continue;
				Skill dto = new Skill(skill.id, skill.name,
						skill.skillOrder != null ? skill.skillOrder : 0, true, skill.active);
				if (link.skillTypeId == LocationSkillEntity.TYPE_REQUIRED) location.getRequiredSkill().add(dto);
				else if (link.skillTypeId == LocationSkillEntity.TYPE_OPTIONAL) location.getOptionalSkill().add(dto);
			}
			locations.add(location);
		}
		return locations;
	}

	/** Ensures the two stable bridge types exist before rebuilding bridge FKs. */
	private void ensureSkillTypeTable() {
		try (Connection connection = DatabaseConnection.connect(dbName)) {
			ensureSkillTypeTable(connection);
		} catch (Exception exception) {
			throw new IllegalStateException("Cannot ensure skill_type table", exception);
		}
	}

	/** Package-visible for isolated migration tests; never opens the configured live DB. */
	static void ensureSkillTypeTable(Connection connection) throws SQLException {
		try (Statement statement = connection.createStatement()) {
			statement.execute("CREATE TABLE IF NOT EXISTS skill_type ("
					+ "id INTEGER PRIMARY KEY AUTOINCREMENT, description TEXT)");
		}
		Map<String, Boolean> requiredWithoutDefault = new LinkedHashMap<>();
		Set<String> columns = new HashSet<>();
		try (Statement statement = connection.createStatement();
				 ResultSet result = statement.executeQuery("PRAGMA table_info('skill_type')")) {
			while (result.next()) {
				String column = result.getString("name").toLowerCase();
				columns.add(column);
				boolean required = result.getInt("notnull") == 1 && result.getString("dflt_value") == null
						&& result.getInt("pk") == 0;
				requiredWithoutDefault.put(column, required);
			}
		}
		if (!columns.contains("description") && !columns.contains("name")) {
			try (Statement statement = connection.createStatement()) {
				statement.execute("ALTER TABLE skill_type ADD COLUMN description TEXT");
			}
			columns.add("description");
			requiredWithoutDefault.put("description", false);
		}
		for (Map.Entry<String, Boolean> column : requiredWithoutDefault.entrySet()) {
			if (column.getValue() && !"name".equals(column.getKey()) && !"description".equals(column.getKey()))
				throw new SQLException("Cannot seed skill_type: unsupported required column " + column.getKey());
		}
		List<String> labelColumns = new ArrayList<>();
		if (columns.contains("description")) labelColumns.add("description");
		if (columns.contains("name")) labelColumns.add("name");
		for (int id = 1; id <= 2; id++) {
			String label = id == 1 ? "required" : "optional";
			boolean exists;
			try (PreparedStatement statement = connection.prepareStatement(
					"SELECT 1 FROM skill_type WHERE id = ?")) {
				statement.setInt(1, id);
				try (ResultSet result = statement.executeQuery()) { exists = result.next(); }
			}
			if (!exists) {
				String insertColumns = "id," + String.join(",", labelColumns);
				String placeholders = "?," + labelColumns.stream().map(ignored -> "?").collect(Collectors.joining(","));
				try (PreparedStatement statement = connection.prepareStatement(
						"INSERT INTO skill_type (" + insertColumns + ") VALUES (" + placeholders + ")")) {
					statement.setInt(1, id);
					for (int index = 0; index < labelColumns.size(); index++) statement.setString(index + 2, label);
					if (statement.executeUpdate() != 1) throw new SQLException("Cannot seed skill_type id " + id);
				}
			} else {
				for (String column : labelColumns) {
					try (PreparedStatement statement = connection.prepareStatement(
							"UPDATE skill_type SET " + column + " = ? WHERE id = ? AND " + column + " IS NULL")) {
						statement.setString(1, label);
						statement.setInt(2, id);
						statement.executeUpdate();
					}
				}
			}
		}
		try (Statement statement = connection.createStatement();
				 ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM skill_type WHERE id IN (1,2)")) {
			if (!result.next() || result.getInt(1) != 2)
				throw new SQLException("Cannot verify stable skill_type ids 1 and 2");
		}
	}

	/**
	 * @brief {@code structure_id} column on {@code skills} for legacy DBs (idempotent).
	 *
	 * @details Skills belong to exactly one structure. On a historical database not managed by
	 *          Flyway, the column must be added here: existing rows are assigned to the first
	 *          structure because the catalog used to be single and shared.
	 *
	 *          Replaces creation of the {@code structure_skills} bridge, which no longer exists
	 *          since migration V5: recreating it at every startup would resurrect the old model
	 *          beneath the new one.
	 */
	private void ensureSkillsStructureColumn() {
		try (Connection conn = DatabaseConnection.connect(dbName);
			 Statement stmt = conn.createStatement()) {
			boolean hasColumn = false;
			try (ResultSet rs = stmt.executeQuery("PRAGMA table_info(skills);")) {
				while (rs.next()) {
					if ("structure_id".equalsIgnoreCase(rs.getString("name"))) { hasColumn = true; break; }
				}
			}
			if (!hasColumn) {
				stmt.execute("ALTER TABLE skills ADD COLUMN structure_id INTEGER NOT NULL DEFAULT 1;");
			}
			stmt.execute("UPDATE skills SET structure_id = (SELECT MIN(id) FROM structures) " +
				"WHERE structure_id IS NULL OR structure_id = 0;");
			stmt.execute("CREATE INDEX IF NOT EXISTS idx_skills_structure " +
				"ON skills(structure_id, skill_order);");
		} catch (Exception e) {
			logger.log(Level.SEVERE, "Error ensuring skills.structure_id column", e);
		}
	}

	/** Returns the entity IDs referenced by shifts for lightweight CRUD delete guards. */
	/**
	 * @brief 被班次引用到的员工 / 地点，用于前端禁用删除。
	 *
	 * @details 返回两组员工，语义不同、不可混用：
	 *          {@code employeeIds}      —— 有**任意**班次（含历史）。地点关联判断用。
	 *          {@code activeEmployeeIds} —— 有**未结束**班次（进行中 + 未来）。
	 *          只有后者才应阻止删除：历史班次说明这个人干过活，不代表他现在还在排班表上，
	 *          否则任何人只要被排过一次班就永久无法删除。
	 */
	public Map<String, List<Integer>> getScheduleUsage(int structureId) {
		Set<Integer> employeeIds = new HashSet<>();
		Set<Integer> locationIds = new HashSet<>();
		List<Object[]> usage = em.createQuery(
				"select distinct sh.employeeId, sh.locationId from ShiftEntity sh, LocationEntity l "
				+ "where l.id = sh.locationId and l.structureId = ?1", Object[].class)
				.setParameter(1, structureId).getResultList();
		for (Object[] row : usage) {
			if (row[0] != null) employeeIds.add(((Number) row[0]).intValue());
			locationIds.add(((Number) row[1]).intValue());
		}

		// 在途 = end_time >= now（含正在进行的班次）。时间列是 'yyyy-MM-dd HH:mm:ss' 文本，
		// 字典序与时间序一致，可直接用字符串比较。
		String now = LocalDateTime.now().format(dbFormatter);
		List<Integer> upcoming = em.createQuery(
				"select distinct sh.employeeId from ShiftEntity sh, LocationEntity l "
				+ "where l.id = sh.locationId and l.structureId = ?1 and sh.employeeId is not null "
				+ "and sh.endTime >= ?2", Integer.class)
				.setParameter(1, structureId).setParameter(2, now).getResultList();
		Set<Integer> activeEmployeeIds = new HashSet<>(upcoming);

		return Map.of("employeeIds", new ArrayList<>(employeeIds),
				"activeEmployeeIds", new ArrayList<>(activeEmployeeIds),
				"locationIds", new ArrayList<>(locationIds));
	}

	
	
	
	
	
	
	
	












	
	
	
	
	
	
	
	
	
	
	
	
	

	
	
	
	
	/**
	 * @brief Retrieves all shifts from the database with their associated skills.
	 * @return a list of all Shift objects with required and optional skills populated
	 */
	public List<Shift> getShifts(int structureId) {
	    return getShifts(structureId, null, null);
	}

	/**
	 * @brief Retrieves shifts for a structure, optionally filtered to a time window.
	 * @param structureId the structure to load shifts for
	 * @param windowStart inclusive lower bound (may be null → no filter)
	 * @param windowEnd   exclusive upper bound (may be null → no filter)
	 * @details A shift BELONGS to the [windowStart, windowEnd) window when its start_time falls
	 *          within it — the same semantics used by writes (deleteShiftsInWindow,
	 *          applyTemplateToWindow, unpinWeek) and reports. The old intersection semantics made
	 *          an overnight shift crossing midnight appear in BOTH weeks: it was counted twice by
	 *          the solver's weekly constraints, and "Populate from template" (which deletes by
	 *          start) displayed it without removing it. Comparison is textual: dates use fixed-width,
	 *          lexicographically sortable "yyyy-MM-dd HH:mm:ss" (dbFormatter). With both bounds null:
	 *          all shifts.
	 */
	public List<Shift> getShifts(int structureId, LocalDateTime windowStart, LocalDateTime windowEnd) {
		boolean windowed = windowStart != null && windowEnd != null;
		String hql = "select sh from ShiftEntity sh, LocationEntity l " +
				"where l.id = sh.locationId and l.structureId = ?1" +
				(windowed ? " and sh.startTime >= ?2 and sh.startTime < ?3" : "") +
				" order by sh.startTime, sh.id";
		var query = em.createQuery(hql, ShiftEntity.class).setParameter(1, structureId);
		if (windowed) {
			query.setParameter(2, windowStart.format(dbFormatter));
			query.setParameter(3, windowEnd.format(dbFormatter));
		}
		return shiftDtos(query.getResultList());
	}

	/** Builds shift DTOs and populates the skill catalog with used flags in bulk. */
	private List<Shift> shiftDtos(List<ShiftEntity> entities) {
		if (entities.isEmpty()) return new ArrayList<>();
		Set<Integer> locationIds = entities.stream().map(entity -> entity.locationId).collect(Collectors.toSet());
		Map<Integer, LocationEntity> locations = loadInChunks(locationIds,
				chunk -> LocationEntity.<LocationEntity>list("id in ?1", chunk)).stream()
				.collect(Collectors.toMap(location -> location.id, location -> location));
		List<Skill> allSkills = SkillEntity.<SkillEntity>list("order by skillOrder, id").stream()
				.map(skill -> new Skill(skill.id, skill.name,
						skill.skillOrder != null ? skill.skillOrder : 0, false, skill.active)).toList();
		Set<Integer> ids = entities.stream().map(entity -> entity.id).collect(Collectors.toSet());
		Set<Integer> phaseIds = entities.stream().map(entity -> entity.auditOrderPhaseId)
				.filter(java.util.Objects::nonNull).collect(Collectors.toSet());
		Map<Integer, AuditOrderPhaseEntity> phases = loadInChunks(phaseIds,
				chunk -> AuditOrderPhaseEntity.<AuditOrderPhaseEntity>list("id in ?1", chunk)).stream()
				.collect(Collectors.toMap(phase -> phase.id, phase -> phase));
		Map<Integer, Integer> phaseSlots = new HashMap<>();
		Map<Integer, Map<Integer, Set<Integer>>> used = new HashMap<>();
		for (ShiftSkillEntity link : loadInChunks(ids,
				chunk -> ShiftSkillEntity.<ShiftSkillEntity>list("shiftId in ?1", chunk))) {
			used.computeIfAbsent(link.shiftId, ignored -> new HashMap<>())
					.computeIfAbsent(link.skillTypeId, ignored -> new HashSet<>()).add(link.skillId);
		}
		List<Shift> shifts = new ArrayList<>(entities.size());
		for (ShiftEntity entity : entities) {
			LocationEntity location = locations.get(entity.locationId);
			Map<Integer, Set<Integer>> byType = used.getOrDefault(entity.id, Collections.emptyMap());
			Shift shift = new Shift(entity.id,
					EmployeeDateEntity.parseDbDateTime(entity.startTime),
					EmployeeDateEntity.parseDbDateTime(entity.endTime),
					entity.locationId, location != null ? location.name : null,
					copySkillsWithUsage(allSkills, byType.getOrDefault(1, Collections.emptySet())),
					copySkillsWithUsage(allSkills, byType.getOrDefault(2, Collections.emptySet())), null);
			shift.setEmployeeId(entity.employeeId);
			shift.setPinned(entity.pinned);
			shift.setVersion(entity.version);
			shift.setAuditOrderCode(entity.auditOrderCode);
			shift.setAuditStage(entity.auditStage);
			shift.setRequiredRole(entity.requiredRole);
			if (entity.auditOrderPhaseId != null) {
				AuditOrderPhaseEntity phase = phases.get(entity.auditOrderPhaseId);
				shift.setContinuityGroup(phase == null ? null : phase.continuityGroup);
				shift.setPhaseSlotIndex(phaseSlots.merge(entity.auditOrderPhaseId, 1, Integer::sum));
			}
			if (location != null) {
				shift.setLocationLatitude(location.latitude);
				shift.setLocationLongitude(location.longitude);
			}
			shifts.add(shift);
		}
		return shifts;
	}

	private List<Skill> copySkillsWithUsage(List<Skill> allSkills, Set<Integer> usedIds) {
		List<Skill> result = new ArrayList<>(allSkills.size());
		for (Skill skill : allSkills)
			result.add(new Skill(skill.getId(), skill.getName(), skill.getOrder(),
					usedIds.contains(skill.getId()), skill.isActive()));
		return result;
	}

	
	
	
	
	
	
	
	
	/**
	 * @brief Returns the first/last shift date for a structure (lightweight, no N+1).
	 * @param structureId the structure to inspect
	 * @return a map {"min": "yyyy-MM-dd HH:mm:ss"|null, "max": "..."|null}. Both null if no shifts.
	 * @details Used by the frontend to position the timeline on the month of the first shift without
	 *          downloading every shift. O(1) index query, no skill join.
	 */
	public Map<String, String> getShiftDateRange(int structureId) {
		Object[] values = em.createQuery(
				"select min(sh.startTime), max(sh.startTime) from ShiftEntity sh, LocationEntity l " +
				"where l.id = sh.locationId and l.structureId = ?1", Object[].class)
				.setParameter(1, structureId).getSingleResult();
		Map<String, String> range = new HashMap<>();
		range.put("min", (String) values[0]);
		range.put("max", (String) values[1]);
		return range;
	}

	/**
	 * @brief Retrieves all shifts for a specific location with their skills.
	 * @param location_id the unique identifier of the location
	 * @return a list of Shift objects belonging to the specified location
	 */
	public List<Shift> getLocationShifts(int location_id) {
		
		List<Shift> shifts = new ArrayList<>();
		
		// Query filtered by location_id.
		String shiftQuery = "SELECT shifts.id AS id, shifts.location_id, locations.name AS location_desc, shifts.start_time, shifts.end_time FROM shifts JOIN locations ON shifts.location_id = locations.id WHERE shifts.location_id = ?;";

		try (Connection conn = DatabaseConnection.connect(dbName)) {
			
			// Retrieve shifts for the specified location.
			try (PreparedStatement stmt = conn.prepareStatement(shiftQuery)) {
				stmt.setInt(1, location_id); // Pass location_id as a parameter.

				try (ResultSet rs = stmt.executeQuery()) {

					while (rs.next()) {
						
						int shift_id = rs.getInt("id");

						String locationDesc = rs.getString("location_desc");

						LocalDateTime startTime = rs.getTimestamp("start_time").toLocalDateTime();
						LocalDateTime endTime = rs.getTimestamp("end_time").toLocalDateTime();

						// Date validation.
						if (endTime.isBefore(startTime) || startTime.equals(endTime)) {
							logger.warning("Skipping invalid shift with id: " + shift_id);
							continue;
						}

						// Retrieve required and optional skills.
						//List<Skill> requiredSkills = GetLocationSkills(shift_id, 1, conn);
						
						//List<Skill> optionalSkills = GetLocationSkills(shift_id, 2, conn);
						
						
                        List<Skill> requiredSkills = GetShiftSkills(shift_id, 1, conn);
						
                        
						List<Skill> optionalSkills = GetShiftSkills(shift_id, 2, conn);
						
						
				
						// Add the shift to the list.
						shifts.add(new Shift(shift_id, startTime, endTime, location_id, locationDesc, requiredSkills,optionalSkills, null));
						
					}
				}
			}

		} catch (Exception e) {
			logger.log(Level.SEVERE, "Error while fetching shifts from the database", e);
		}

		return shifts;
	}

	public List<Shift> getLocationShiftsOrm(int locationId) {
		return shiftDtos(ShiftEntity.<ShiftEntity>list("locationId = ?1 order by startTime, id", locationId))
				.stream().filter(shift -> shift.getStart() != null && shift.getEnd() != null
						&& shift.getEnd().isAfter(shift.getStart())).toList();
	}

	
	
	
	/**
	 * @brief Retrieves a single shift by its ID with associated skills.
	 * @param shift_id the unique identifier of the shift
	 * @return the Shift object, or null if not found
	 */
	public Shift getShift(int shift_id) {

	    Shift shift = null;

	    // Query to retrieve shift data.
	    String shiftQuery = "SELECT shifts.id AS id, start_time, end_time, shifts.location_id, " +
	                        "locations.name AS location_desc " +
	                        "FROM shifts " +
	                        "JOIN locations ON shifts.location_id = locations.id " +
	                        "WHERE shifts.id = ?;";

	    try (Connection conn = DatabaseConnection.connect(dbName)) {

	        try (PreparedStatement stmt = conn.prepareStatement(shiftQuery)) {
	            stmt.setInt(1, shift_id); // Set shift_id as a parameter.
	            try (ResultSet rs = stmt.executeQuery()) {

	                if (rs.next()) {
	                    // Retrieve shift data.
	                    int location_id = rs.getInt("location_id");
	                    LocalDateTime startTime = rs.getTimestamp("start_time").toLocalDateTime();
	                    LocalDateTime endTime = rs.getTimestamp("end_time").toLocalDateTime();
	                    String locationDesc = rs.getString("location_desc");

	                    // Retrieve required and optional skills.
	                    List<Skill> requiredSkills = GetShiftSkills(shift_id, 1, conn);
	                    List<Skill> optionalSkills = GetShiftSkills(shift_id, 2, conn);

	                    // Create the Shift object.
	                    shift = new Shift(shift_id, startTime, endTime, location_id, locationDesc, requiredSkills, optionalSkills, null);

	                } else {
	                    logger.warning("No shift found with id: " + shift_id);
	                }
	            }
	        }

	    } catch (Exception e) {
	        logger.log(Level.SEVERE, "Error while fetching shift from the database", e);
	    }

	    return shift;
	}

	
	
	
	
	
	
	




	
	
	




	
	
	

	
	
	
	
	






	
	
	
	
	
	
	
	
	/**
	 * @brief Retrieves shift details by shift ID, including associated skills.
	 * @param shifts_id the unique identifier of the shift
	 * @return a list of Shift objects matching the given ID (typically one or empty)
	 */
	public List<Shift> getLocationDatesByIdShift(int shifts_id) {
		List<Shift> shifts = new ArrayList<>();
		
		logger.info("TEST: " + shifts_id);
		

		// Query filtered by shifts_id.
		String shiftQuery = "SELECT shifts.id AS id, " + "shifts.location_id, " + "locations.name AS location_desc, "
				+ "shifts.start_time, " + "shifts.end_time " + "FROM shifts "
				+ "JOIN locations ON shifts.location_id = locations.id " + "WHERE shifts.id = ?;";

	
		try (Connection conn = DatabaseConnection.connect(dbName)) {
		
			// Retrieve shifts for the specified location.
			try (PreparedStatement stmt = conn.prepareStatement(shiftQuery)) {

				stmt.setInt(1, shifts_id); // Pass location_id as a parameter.

				try (ResultSet rs = stmt.executeQuery()) {

					while (rs.next()) {

						int shiftId = rs.getInt("id");
						int location_id = rs.getInt("location_id");
						String locationDesc = rs.getString("location_desc");
						LocalDateTime startTime = rs.getTimestamp("start_time").toLocalDateTime();
						LocalDateTime endTime = rs.getTimestamp("end_time").toLocalDateTime();

						
						// Date validation.
						if (endTime.isBefore(startTime) || startTime.equals(endTime)) {
							logger.warning("Skipping invalid shift with id: " + shiftId);
							continue;
						}

						// Retrieve required and optional skills.
						List<Skill> requiredSkills = GetShiftSkills(shifts_id, 1 ,conn);
						
						List<Skill> optionalSkills = GetShiftSkills(shifts_id, 2, conn);
			
						logger.info("Required Skills for shift " + shifts_id + ": " + requiredSkills);
						logger.info("Optional Skills for shift " + shifts_id + ": " + optionalSkills);
						
						
						shifts.add(new Shift(shiftId, startTime, endTime, location_id, locationDesc, requiredSkills, optionalSkills, null));
						
					}
				}
			}

		} catch (Exception e) {
			logger.log(Level.SEVERE, "Error while fetching shifts from the database", e);
		}

		return shifts;
	}

	
	
	
	
	

	
	
	




	
	
	
	

	
	
	



	
	

	
	
	
	
	
	
	/**
	 * @brief Retrieves all skills from the database ordered by display order.
	 * @return a list of all Skill objects
	 */
	public List<Skill> getSkills() {
		
		String query = "SELECT id, skill_order, name, active FROM skills ORDER BY skill_order;";

		List<Skill> skills = new ArrayList<>();
		try (Connection conn = DatabaseConnection.connect(dbName);
				PreparedStatement stmt = conn.prepareStatement(query);
				ResultSet rs = stmt.executeQuery()) {
			while (rs.next()) {
				int skill_id = rs.getInt("id"); // Read the ID.
				int order = rs.getInt("skill_order"); // Read the ID.
				String name = rs.getString("name"); // Read the name.
				skills.add(new Skill(skill_id, name, order, true, rs.getInt("active") == 1));
			}
		} catch (Exception e) {
			logger.log(Level.SEVERE, "Errore durante il recupero delle skills dal database", e);
		}
		return skills;
	}

	
	
	
	







	
	


	
	/**
	 * @brief Returns the total number of employees in the database.
	 * @return the employee count, or 0 if an error occurs
	 */
	public int getEmployeeCount() {

		String query = "SELECT COUNT(*) AS employee_count FROM employees;"; // Count rows in the employees table.
		try (Connection conn = DatabaseConnection.connect(dbName);
				PreparedStatement stmt = conn.prepareStatement(query);
				ResultSet rs = stmt.executeQuery()) {
			if (rs.next()) {
				return rs.getInt("employee_count"); // Return the count.
			}
		} catch (Exception e) {
			logger.log(Level.SEVERE, "Error while counting employees in the database", e);
		}
		return 0; // Return 0 on error.
	}

	
	
	
	
	
	/**
	 * @brief Retrieves date entries for a specific employee, optionally filtered by date type.
	 * @details When date_type_id is 0, all date types are returned; otherwise,
	 *          only dates matching the specified type are retrieved.
	 * @param employee_id the unique identifier of the employee
	 * @param date_type_id the date type filter (0 = all, 1 = desired, 2 = undesired, 3 = unavailable)
	 * @return a list of EmployeeDate objects for the given employee
	 */
	public List<EmployeeDate> getEmployeeDates(int employee_id, int date_type_id) {
		String query;
		
		List<EmployeeDate> employeeDates = new ArrayList<>();

		// Log the input.
		//logger.info("getEmployeeDates() called with employee_id: " + employee_id + ", date_type_id: " + date_type_id);

		
		try (Connection conn = DatabaseConnection.connect(dbName)) {

			if (date_type_id == 0) {
				query = "SELECT id, employee_id, date_start, date_end, date_type_id FROM employee_dates WHERE employee_id = ? ORDER BY date_start, date_type_id;";
			} else {
				query = "SELECT id, employee_id, date_start, date_end, date_type_id FROM employee_dates WHERE employee_id = ? AND date_type_id = ? ORDER BY date_start, date_type_id;";
			}

			//logger.info("Prepared query: " + query);

			try (PreparedStatement stmt = conn.prepareStatement(query)) {
				// Set parameters.
				stmt.setInt(1, employee_id);
				if (date_type_id != 0) {
					stmt.setInt(2, date_type_id);
				}

				//logger.info("Parameters set: employee_id=" + employee_id + ", date_type_id=" + date_type_id);

				try (ResultSet rs = stmt.executeQuery()) {
					// Iterate over results.
					while (rs.next()) {

						int IdFetched = rs.getInt("id");
						int employeeIdFetched = rs.getInt("employee_id");
						int date_type_idFetched = rs.getInt("date_type_id");

						LocalDateTime dateStart = rs.getTimestamp("date_start").toLocalDateTime();
						LocalDateTime dateEnd = rs.getTimestamp("date_end").toLocalDateTime();

						EmployeeDate employeeDate = new EmployeeDate();
						employeeDate.setId(IdFetched);
						employeeDate.setDateTypeId(date_type_idFetched);
						employeeDate.setEmployeeId(employeeIdFetched);
						employeeDate.setDateStart(dateStart);
						employeeDate.setDateEnd(dateEnd);

						employeeDates.add(employeeDate);

						//logger.info("Row found: id=" + IdFetched + ", employee_id=" + employeeIdFetched + ", date_start=" + dateStart + ", date_end=" + dateEnd);

					}

					if (employeeDates.isEmpty()) {
						//logger.warning("No results found for employee_id: " + employee_id + ", date_type_id: " + date_type_id);
					}
					
				}
			}
			
		} catch (Exception e) {
			logger.log(Level.SEVERE, "Errore durante il recupero delle date per employee_id: " + employee_id, e);
		}

		
		//logger.info("Total dates retrieved: " + employeeDates.size());
		
		return employeeDates;
	}

	
	
	
	
	

	
	
	
	
	

	
	
	
	
	


	
	
	
	
	
	

	

	
	
	
	
	
	
	
	
	
	
	
	

	/**
	 * @brief Retrieves all employees with their skills and date information.
	 * @details For each employee, fetches desired, undesired, and unavailable dates
	 *          as well as associated skills from the database.
	 * @return a list of fully populated Employee objects
	 */
	public List<Employee> getEmployees(int structureId) {
		// Per-structure cache: getEmployees has a costly N+1 (1+3N JDBC connections, one per
		// employee × three date types). Employee records rarely change, so keep them in memory
		// and reload from the DB only after a record update (invalidateEmployeesCache).
		// Same pattern as _transCache.
		synchronized (_employeesCacheLock) {
			if (_employeesCache == null) _employeesCache = new HashMap<>();
			List<Employee> cached = _employeesCache.get(structureId);
			if (cached != null) return cached;
			List<Employee> loaded = Collections.unmodifiableList(loadEmployeesOrm(structureId));
			_employeesCache.put(structureId, loaded);
			return loaded;
		}
	}

	/** Loads employees, dates, and the skill catalog exclusively through Panache. */
	private List<Employee> loadEmployeesOrm(int structureId) {
		List<EmployeeEntity> entities = EmployeeEntity.list("structureId = ?1 order by id", structureId);
		Map<Integer, Employee> employees = new LinkedHashMap<>();
		for (EmployeeEntity entity : entities) {
			Employee employee = new Employee(entity.id, entity.code, entity.firstName, entity.lastName,
					new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
			employee.setEmail(entity.email);
			employee.setActive(entity.active);
			employee.setOperationalStatus(entity.operationalStatus);
			employee.setBusyUntil(parseNullableDateTime(entity.busyUntil));
			employee.setStatusUpdatedAt(parseNullableDateTime(entity.statusUpdatedAt));
			employee.setCurrentLocationId(entity.currentLocationId);
			employee.setBufferBeforeMinutes(entity.bufferBeforeMinutes);
			employee.setBufferAfterMinutes(entity.bufferAfterMinutes);
			employees.put(entity.id, employee);
		}
		if (employees.isEmpty()) return new ArrayList<>();

		Set<Integer> employeeIds = employees.keySet();
		for (EmployeeDateEntity entity : loadInChunks(employeeIds,
				chunk -> EmployeeDateEntity.<EmployeeDateEntity>list(
						"employeeId in ?1 order by employeeId, dateStart, dateTypeId, id", chunk))) {
			Employee employee = employees.get(entity.employeeId);
			if (employee == null) continue;
			EmployeeDate date = entity.toDto();
			switch (entity.dateTypeId) {
				case EmployeeDateEntity.TYPE_DESIRED -> employee.getDesiredDates().add(date);
				case EmployeeDateEntity.TYPE_UNDESIRED -> employee.getUndesiredDates().add(date);
				case EmployeeDateEntity.TYPE_UNAVAILABLE -> employee.getUnavailableDates().add(date);
				default -> { }
			}
		}
		List<Skill> catalog = SkillEntity.<SkillEntity>list("order by skillOrder, id").stream()
				.map(skill -> new Skill(skill.id, skill.name,
						skill.skillOrder != null ? skill.skillOrder : 0, false, skill.active)).toList();
		Map<Integer, Set<Integer>> used = new HashMap<>();
		Map<Integer, List<AuditorQualification>> qualifications = new HashMap<>();
		for (EmployeeSkillEntity link : loadInChunks(employeeIds,
				chunk -> EmployeeSkillEntity.<EmployeeSkillEntity>list("employeeId in ?1", chunk))) {
			used.computeIfAbsent(link.employeeId, ignored -> new HashSet<>()).add(link.skillId);
			AuditorQualification qualification = new AuditorQualification();
			qualification.setSkillId(link.skillId);
			qualification.setCertificateNumber(link.certificateNumber);
			qualification.setQualificationLevel(link.qualificationLevel);
			qualification.setValidFrom(parseNullableDate(link.validFrom));
			qualification.setValidUntil(parseNullableDate(link.validUntil));
			qualification.setStatus(link.qualificationStatus);
			qualifications.computeIfAbsent(link.employeeId, ignored -> new ArrayList<>()).add(qualification);
		}
		for (Employee employee : employees.values()) {
			employee.setSkills(copySkillsWithUsage(catalog,
					used.getOrDefault(employee.getId(), Collections.emptySet())));
			employee.setQualifications(qualifications.getOrDefault(employee.getId(), List.of()));
		}
		return new ArrayList<>(employees.values());
	}

	private static LocalDateTime parseNullableDateTime(String value) {
		return value == null || value.isBlank() ? null : EmployeeDateEntity.parseDbDateTime(value);
	}

	private static LocalDate parseNullableDate(String value) {
		return value == null || value.isBlank() ? null : LocalDate.parse(value);
	}

	/**
	 * @brief Retrieves a single employee by ID with all associated data.
	 * @details Fetches employee details, desired/undesired/unavailable dates,
	 *          and skill associations.
	 * @param employee_id the unique identifier of the employee
	 * @return the fully populated Employee object, or null if not found
	 */
	public Employee GetEmployeeById(int employee_id) {

		String query = "SELECT id, code, first_name, last_name, email, active FROM employees WHERE id = ?;";

		try (Connection conn = DatabaseConnection.connect(dbName);

				PreparedStatement stmt = conn.prepareStatement(query)) {

			stmt.setInt(1, employee_id);

			try (ResultSet rs = stmt.executeQuery()) {
				if (rs.next()) {
					// Retrieve primary data.
					String code = rs.getString("code");
					String firstName = rs.getString("first_name");
					String lastName = rs.getString("last_name");
					String email = rs.getString("email");

					// Logger.getLogger(DemoDataRepository.class.getName()).info("Processing
					// employee: " + employee_id + ", " + firstName + " " + lastName);

					// Retrieve date data.
					List<EmployeeDate> desiredDates = getEmployeeDates(employee_id, 1);
					List<EmployeeDate> undesiredDates = getEmployeeDates(employee_id, 2);
					List<EmployeeDate> unavailableDates = getEmployeeDates(employee_id, 3);

					// Retrieve skills.
					List<Skill> skills = GetEmployeeSkills(employee_id, conn);

					// Create and return the Employee object.
					Employee employee = new Employee(employee_id, code, firstName, lastName, desiredDates, undesiredDates,
							unavailableDates, skills);
					employee.setActive(rs.getInt("active") == 1);
					employee.setEmail(email);
					return employee;
				}

			}

		} catch (Exception e) {
			Logger.getLogger(DemoDataRepository.class.getName()).log(Level.SEVERE, "Error finding employee by ID", e);
		}

		return null;
	}

	
	
	

	
	
	
	


	
	

	
	
	

	

	
	
	
	
	/**
	 * @brief Inserts a base employee record (without skills or dates).
	 * @param employee the Employee object containing code, first name, and last name
	 * @return the auto-generated ID of the new employee, or -1 on failure
	 */
	public int addEmployeeBase(Employee employee) {

		String query = "INSERT INTO employees (code, first_name, last_name, email) VALUES (?, ?, ?, ?);";
		int generatedId = -1; // The generated ID will be returned to the caller.

		try (Connection conn = DatabaseConnection.connect(dbName);
				PreparedStatement stmt = conn.prepareStatement(query, PreparedStatement.RETURN_GENERATED_KEYS)) {

			// Set values to insert.
			stmt.setString(1, employee.getCode());
			stmt.setString(2, employee.getFirstName());
			stmt.setString(3, employee.getLastName());
			stmt.setString(4, employee.getEmail() == null ? "" : employee.getEmail());

			// Execute the insert.
			int affectedRows = stmt.executeUpdate();

			if (affectedRows > 0) {
				// Retrieve the generated ID.
				try (ResultSet generatedKeys = stmt.getGeneratedKeys()) {
					if (generatedKeys.next()) {
						generatedId = generatedKeys.getInt(1);
					}
				}
			}
		} catch (Exception e) {
			logger.log(Level.SEVERE, "Errore durante l'inserimento dell'impiegato nella tabella employees", e);
		}

		if (generatedId > 0) invalidateEmployeesCache();
		return generatedId;
	}

	
	
	
	
	private List<Skill> GetEmployeeSkills(int employee_id, Connection conn) {
	    String query = "SELECT s.id, s.name, s.skill_order, " +
	                   "CASE WHEN es.employee_id IS NOT NULL THEN TRUE ELSE FALSE END AS used " +
	                   "FROM skills s " +
	                   "LEFT JOIN employee_skills es ON s.id = es.skill_id AND es.employee_id = ?";
	    List<Skill> skills = new ArrayList<>();

	    try (PreparedStatement stmt = conn.prepareStatement(query)) {
	        stmt.setInt(1, employee_id);

	        try (ResultSet rs = stmt.executeQuery()) {
	            while (rs.next()) {
	                // Retrieve skill data from the ResultSet.
	                int skill_id = rs.getInt("id");
	                String skill_name = rs.getString("name");
	                int skill_order = rs.getInt("skill_order");
	                boolean used = rs.getBoolean("used"); // Computed by CASE WHEN.

	                // Create a new Skill object.
	                Skill skill = new Skill(skill_id, skill_name, skill_order, used);
	                skills.add(skill); // Add the skill to the list.
	            }
	        }

	    } catch (Exception e) {
	        Logger.getLogger(DemoDataRepository.class.getName()).log(Level.SEVERE,
	                "Errore durante il recupero della lista completa delle Skill", e);
	    }

	    return skills; // Return the skill list.
	}

	
	
	
	
	
	
	private List<Skill> GetShiftSkills(int shift_id, int skill_type_id, Connection conn) {
	    String query = "SELECT s.id, s.name, s.skill_order, " +
	                   "CASE WHEN ss.shift_id IS NOT NULL THEN TRUE ELSE FALSE END AS used " +
	                   "FROM skills s " +
	                   "LEFT JOIN shift_skills ss ON s.id = ss.skill_id " +
	                   "AND ss.shift_id = ? AND ss.skill_type_id = ?"; 

	    List<Skill> skills = new ArrayList<>();

	    try (PreparedStatement stmt = conn.prepareStatement(query)) {
	        // Set query parameters.
	        stmt.setInt(1, shift_id);
	        stmt.setInt(2, skill_type_id);

	        try (ResultSet rs = stmt.executeQuery()) {
	            while (rs.next()) {
	                // Retrieve skill data from the ResultSet.
	                int skill_id = rs.getInt("id");
	                String skill_name = rs.getString("name");
	                int skill_order = rs.getInt("skill_order");
	                boolean used = rs.getBoolean("used"); 

	                // Create a new Skill object and add it to the list.
	                Skill skill = new Skill(skill_id, skill_name, skill_order, used);
	                skills.add(skill);
	            }
	        }
	    } catch (Exception e) {
	        // Log the error.
	        Logger.getLogger(DemoDataRepository.class.getName())
	              .log(Level.SEVERE, "Errore durante il recupero delle Skill per la Location", e);
	    }

	    return skills; // Return the skill list.
	}

	
	
	
	
	/**
	 * @brief Retrieves all skills with a usage flag indicating association with a specific employee.
	 * @details Returns every skill in the system, with the "used" flag set to true
	 *          for skills that are associated with the given employee.
	 * @param employee_id the unique identifier of the employee (0 to mark all as unused)
	 * @return a list of Skill objects with the "used" flag set accordingly
	 */
	public List<Skill> getEmployeeSkills(int employee_id) {

		// Query to retrieve all skills.
		String queryAllSkills = "SELECT id AS skill_id, skill_order AS skill_order, name AS skill_name FROM skills ORDER BY skill_order;";

		// Query to check whether a skill is associated with the employee.
		String queryEmployeeSkills = "SELECT skill_id FROM employee_skills WHERE employee_id = ?;";

		List<Skill> skills = new ArrayList<>();
		Set<Integer> employeeSkillIds = new HashSet<>();

		try (Connection conn = DatabaseConnection.connect(dbName)) {
			// Check whether employeeId is valid (not 0).
			if (employee_id > 0) {
				// Retrieve skills associated with the employee.
				try (PreparedStatement stmt = conn.prepareStatement(queryEmployeeSkills)) {
					stmt.setInt(1, employee_id);
					try (ResultSet rs = stmt.executeQuery()) {
						while (rs.next()) {
							employeeSkillIds.add(rs.getInt("skill_id")); // Add associated skill IDs.
						}
					}
				}
			}

			// Retrieve all skills from the database.
			try (PreparedStatement stmt = conn.prepareStatement(queryAllSkills); ResultSet rs = stmt.executeQuery()) {
				while (rs.next()) {
					int skill_id = rs.getInt("skill_id");
					int order = rs.getInt("skill_order");
					String skillName = rs.getString("skill_name");

					// If employeeId is 0, every skill is unused (isUsed = false).
					boolean isUsed = employee_id > 0 && employeeSkillIds.contains(skill_id);

					skills.add(new Skill(skill_id, skillName, order, isUsed));
				}
			}

		} catch (Exception e) {
			logger.log(Level.SEVERE, "Error while fetching employee skills", e);
		}

		return skills;
	}


	
	
	
	
	
	/**
	 * @brief Adds unavailable dates for a specific employee using batch insert.
	 * @param employeeId the unique identifier of the employee
	 * @param unavailableDates the set of dates when the employee is unavailable
	 */
	public void addUnavailableDates(int employeeId, Set<LocalDate> unavailableDates) {
		String query = "INSERT INTO employee_unavailable_dates (employee_id, date) VALUES (?, ?);";

		try (Connection conn = DatabaseConnection.connect(dbName);
				PreparedStatement stmt = conn.prepareStatement(query)) {

			for (LocalDate date : unavailableDates) {
				stmt.setInt(1, employeeId);
				stmt.setDate(2, java.sql.Date.valueOf(date));
				stmt.addBatch(); // Add to the batch.
			}

			stmt.executeBatch(); // Execute the batch.
			invalidateEmployeesCache();
		} catch (Exception e) {
			logger.log(Level.SEVERE, "Errore durante l'inserimento delle date indisponibili", e);
		}
	}

	
	
	
	/**
	 * @brief Adds desired or other typed dates for a specific employee using batch insert.
	 * @param employeeId the unique identifier of the employee
	 * @param desiredDates the set of dates to add
	 * @param date_type_id the type of date (1 = desired, 2 = undesired, 3 = unavailable)
	 */
	public void addDesiredDates(int employeeId, Set<LocalDate> desiredDates, int date_type_id) {

		String query = "INSERT INTO employee_dates (employee_id, date, date_type_id) VALUES (?, ?,?);";

		try (Connection conn = DatabaseConnection.connect(dbName);
				PreparedStatement stmt = conn.prepareStatement(query)) {

			for (LocalDate date : desiredDates) {
				stmt.setInt(1, employeeId);
				stmt.setDate(2, java.sql.Date.valueOf(date));
				stmt.setInt(3, date_type_id);
				stmt.addBatch();
			}

			stmt.executeBatch(); // Execute the batch.
			invalidateEmployeesCache();

		} catch (Exception e) {
			logger.log(Level.SEVERE, "Errore durante l'inserimento delle date desiderate", e);
		}
	}

	

	
	
	
	

	private void addShift(Connection conn, Shift shift, List<Integer> requiredSkills, List<Integer> optionalSkills) throws SQLException {

		String shiftQuery = "INSERT INTO shifts (location_id, start_time, end_time) VALUES (?, ?, ?);";

		String requiredSkillQuery = "INSERT INTO shift_skills (shift_id, skill_id, skill_type_id) VALUES (?, ?, 1);";

		String optionalSkillQuery = "INSERT INTO shift_skills (shift_id, skill_id, skill_type_id) VALUES (?, ?, 2);";

		try (PreparedStatement shiftStmt = conn.prepareStatement(shiftQuery,
				PreparedStatement.RETURN_GENERATED_KEYS);
				PreparedStatement requiredSkillStmt = conn.prepareStatement(requiredSkillQuery);
				PreparedStatement optionalSkillStmt = conn.prepareStatement(optionalSkillQuery)) {

			// Insert the shift.
			shiftStmt.setInt(1, shift.getLocation_id());
			shiftStmt.setString(2, shift.getStart().format(dbFormatter));
			shiftStmt.setString(3, shift.getEnd().format(dbFormatter));

			/*
			logger.info("Tentativo di inserimento dello shift con i seguenti valori:");
			logger.info("Location ID: " + shift.getLocation_id());
			logger.info("Start Time: " + shift.getStart().format(dbFormatter));
			logger.info("End Time: " + shift.getEnd().format(dbFormatter));
           */
			
			int affectedRows = shiftStmt.executeUpdate();

			if (affectedRows == 0) {
				throw new SQLException("Inserimento dello shift fallito, nessuna riga inserita.");
			}

			// Retrieve the generated ID.
			int shiftId;
			try (ResultSet generatedKeys = shiftStmt.getGeneratedKeys()) {
				if (generatedKeys.next()) {
					shiftId = generatedKeys.getInt(1);
					shift.setId(shiftId);
					//logger.info("Shift added successfully: " + shift);
				} else {
					throw new SQLException("Inserimento dello shift fallito, nessun ID generato.");
				}
			}

			// Insert required skills.
			if (requiredSkills != null && !requiredSkills.isEmpty()) {
				for (Integer skillId : requiredSkills) {
					requiredSkillStmt.setInt(1, shiftId);
					requiredSkillStmt.setInt(2, skillId);
					requiredSkillStmt.addBatch();
				}
				requiredSkillStmt.executeBatch();
				//logger.info("Required skills saved successfully for shift ID: " + shiftId);
			}

			// Insert optional skills.
			if (optionalSkills != null && !optionalSkills.isEmpty()) {
				for (Integer skillId : optionalSkills) {
					optionalSkillStmt.setInt(1, shiftId);
					optionalSkillStmt.setInt(2, skillId);
					optionalSkillStmt.addBatch();
				}
				optionalSkillStmt.executeBatch();
				//logger.info("Optional skills saved successfully for shift ID: " + shiftId);
			}

		}
	}

	// =======================================================================
	// Shift templates (recurring weekly pattern per structure)
	// =======================================================================

	private static final DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss");

	/** @brief Creates the shift-template tables idempotently. Called by schema initialization. */
	private void ensureShiftTemplatesTable() {
		try (Connection conn = DatabaseConnection.connect(dbName);
			 Statement stmt = conn.createStatement()) {
			stmt.execute(
				"CREATE TABLE IF NOT EXISTS shift_templates (" +
				"  id INTEGER PRIMARY KEY AUTOINCREMENT," +
				"  structure_id INTEGER NOT NULL," +
				"  day_of_week INTEGER NOT NULL," +   // 0=Mon … 6=Sun
				"  start_time TEXT NOT NULL," +       // "HH:mm:ss"
				"  end_time TEXT NOT NULL," +
				"  location_id INTEGER NOT NULL" +
				");");
			stmt.execute(
				"CREATE TABLE IF NOT EXISTS shift_template_skills (" +
				"  id INTEGER PRIMARY KEY AUTOINCREMENT," +
				"  template_id INTEGER NOT NULL," +
				"  skill_id INTEGER NOT NULL," +
				"  skill_type_id INTEGER NOT NULL" +  // 1=required, 2=optional
				");");
			// Saved templates: a company may have many, each with a description.
			// The header groups shift_templates rows through header_id.
			stmt.execute(
				"CREATE TABLE IF NOT EXISTS shift_template_headers (" +
				"  id INTEGER PRIMARY KEY AUTOINCREMENT," +
				"  structure_id INTEGER NOT NULL," +
				"  description TEXT NOT NULL DEFAULT ''," +
				"  created_at TEXT NOT NULL DEFAULT ''" +
				");");
			try { stmt.execute("ALTER TABLE shift_templates ADD COLUMN header_id INTEGER;"); } catch (Exception ignored) {}
			try { stmt.execute("CREATE INDEX IF NOT EXISTS idx_shift_templates_header ON shift_templates(header_id);"); } catch (Exception ignored) {}
			try { stmt.execute("CREATE INDEX IF NOT EXISTS idx_shift_templates_structure ON shift_templates(structure_id);"); } catch (Exception ignored) {}
		} catch (Exception e) {
			logger.log(Level.SEVERE, "Error ensuring shift_templates table", e);
		}
	}

	/** @brief USED skills (required or optional) for a shift template. */
	private List<Skill> getUsedTemplateSkills(int templateId, int skillTypeId, Connection conn) {
		String query = "SELECT s.id, s.name, s.skill_order FROM skills s " +
			"JOIN shift_template_skills sts ON s.id = sts.skill_id " +
			"WHERE sts.template_id = ? AND sts.skill_type_id = ?;";
		List<Skill> skills = new ArrayList<>();
		try (PreparedStatement stmt = conn.prepareStatement(query)) {
			stmt.setInt(1, templateId);
			stmt.setInt(2, skillTypeId);
			try (ResultSet rs = stmt.executeQuery()) {
				while (rs.next())
					skills.add(new Skill(rs.getInt("id"), rs.getString("name"), rs.getInt("skill_order"), true));
			}
		} catch (Exception e) {
			logger.log(Level.SEVERE, "Error fetching template skills", e);
		}
		return skills;
	}

	/**
	 * @brief Distinct days (yyyy-MM-dd) having at least one shift for a structure.
	 * @details Used by the Configuration calendar to highlight days with shifts when choosing the
	 *          source week for prepopulation. Lightweight (no skill join).
	 */
	public List<String> getShiftDays(int structureId) {
		return em.createQuery(
				"select distinct substring(sh.startTime, 1, 10) from ShiftEntity sh, LocationEntity l " +
				"where l.id = sh.locationId and l.structureId = ?1 order by substring(sh.startTime, 1, 10)",
				String.class).setParameter(1, structureId).getResultList();
	}

	private static void persistTemplateSkills(int templateId, List<Skill> skills, int type) {
		if (skills == null) return;
		for (Skill skill : skills) {
			if (skill == null || !skill.isUsed()) continue;
			ShiftTemplateSkillEntity link = new ShiftTemplateSkillEntity();
			link.templateId = templateId;
			link.skillId = skill.getId();
			link.skillTypeId = type;
			link.persist();
		}
	}

	/** ORM step: saves a week as a named template in a single transaction. */
	@Transactional
	public int addSavedTemplateFromWeekOrm(int structureId, LocalDateTime weekStart, String description) {
		ShiftTemplateHeaderEntity header = new ShiftTemplateHeaderEntity();
		header.structureId = structureId;
		header.description = description != null ? description.trim() : "";
		header.createdAt = LocalDateTime.now().format(dbFormatter);
		header.persist();
		Set<Integer> activeLocations = LocationEntity.<LocationEntity>list(
				"structureId = ?1 and active = ?2", structureId, true).stream()
				.map(location -> location.id).collect(Collectors.toSet());
		for (Shift shift : getShifts(structureId, weekStart, weekStart.plusDays(7))) {
			if (shift.getStart() == null || shift.getEnd() == null
					|| !activeLocations.contains(shift.getLocation_id())) continue;
			ShiftTemplateEntity template = new ShiftTemplateEntity();
			template.structureId = structureId;
			template.dayOfWeek = (shift.getStart().getDayOfWeek().getValue() + 6) % 7;
			template.startTime = shift.getStart().toLocalTime().format(timeFormatter);
			template.endTime = shift.getEnd().toLocalTime().format(timeFormatter);
			template.locationId = shift.getLocation_id();
			template.headerId = header.id;
			template.persist();
			persistTemplateSkills(template.id, shift.getRequiredSkill(), 1);
			persistTemplateSkills(template.id, shift.getOptionalSkill(), 2);
		}
		return header.id;
	}

	private void deleteShiftsInWindowOrm(int structureId, LocalDateTime start, LocalDateTime end) {
		String startText = start.format(dbFormatter);
		String endText = end.format(dbFormatter);
		List<ShiftEntity> shifts = em.createQuery(
				"select sh from ShiftEntity sh, LocationEntity l "
				+ "where l.id = sh.locationId and l.structureId = ?1 "
				+ "and sh.startTime >= ?2 and sh.startTime < ?3", ShiftEntity.class)
				.setParameter(1, structureId).setParameter(2, startText).setParameter(3, endText)
				.getResultList();
		for (ShiftEntity shift : shifts) {
			ShiftSkillEntity.delete("shiftId", shift.id);
			shift.delete();
		}
	}

	private int instantiateTemplatesOrm(List<ShiftTemplateEntity> templates, int structureId,
			LocalDateTime start, LocalDateTime end) {
		if (templates == null || templates.isEmpty()) return -2;
		Set<Integer> activeLocations = LocationEntity.<LocationEntity>list(
				"structureId = ?1 and active = ?2", structureId, true).stream()
				.map(location -> location.id).collect(Collectors.toSet());
		// Validate the complete source before deleting a single real shift. This also
		// protects databases that already contained poisoned legacy template rows.
		for (ShiftTemplateEntity template : templates) {
			if (template.structureId != structureId || template.dayOfWeek < 0 || template.dayOfWeek > 6
					|| !activeLocations.contains(template.locationId)) return -2;
			try {
				LocalTime templateStart = LocalTime.parse(template.startTime);
				LocalTime templateEnd = LocalTime.parse(template.endTime);
				if (!templateEnd.isAfter(templateStart)) return -2;
			} catch (RuntimeException invalidTime) {
				return -2;
			}
		}
		Map<Integer, List<ShiftTemplateSkillEntity>> skillsByTemplate = new HashMap<>();
		Set<Integer> templateIds = templates.stream().map(template -> template.id).collect(Collectors.toSet());
		for (ShiftTemplateSkillEntity skill : loadInChunks(templateIds,
				chunk -> ShiftTemplateSkillEntity.<ShiftTemplateSkillEntity>list("templateId in ?1", chunk)))
			skillsByTemplate.computeIfAbsent(skill.templateId, ignored -> new ArrayList<>()).add(skill);
		Set<Integer> referencedSkillIds = skillsByTemplate.values().stream().flatMap(List::stream)
				.map(skill -> skill.skillId).collect(Collectors.toSet());
		Set<Integer> existingSkillIds = loadInChunks(referencedSkillIds,
				chunk -> SkillEntity.<SkillEntity>list("id in ?1", chunk)).stream()
				.map(skill -> skill.id).collect(Collectors.toSet());
		Set<String> uniqueLinks = new HashSet<>();
		for (Map.Entry<Integer, List<ShiftTemplateSkillEntity>> entry : skillsByTemplate.entrySet()) {
			if (!templateIds.contains(entry.getKey())) return -2;
			for (ShiftTemplateSkillEntity skill : entry.getValue()) {
				if (!existingSkillIds.contains(skill.skillId) || (skill.skillTypeId != 1 && skill.skillTypeId != 2)
						|| !uniqueLinks.add(skill.templateId + ":" + skill.skillId + ":" + skill.skillTypeId)) return -2;
			}
		}
		deleteShiftsInWindowOrm(structureId, start, end);
		int created = 0;
		for (LocalDate day = start.toLocalDate(); !day.isAfter(end.toLocalDate()); day = day.plusDays(1)) {
			int dayOfWeek = (day.getDayOfWeek().getValue() + 6) % 7;
			for (ShiftTemplateEntity template : templates) {
				if (template.dayOfWeek != dayOfWeek || !activeLocations.contains(template.locationId)) continue;
				LocalDateTime candidateStart = LocalDateTime.of(day, LocalTime.parse(template.startTime));
				LocalDateTime candidateEnd = LocalDateTime.of(day, LocalTime.parse(template.endTime));
				if (candidateStart.isBefore(start) || !candidateStart.isBefore(end) || candidateEnd.isAfter(end)) continue;
				ShiftEntity shift = new ShiftEntity();
				shift.locationId = template.locationId;
				shift.startTime = candidateStart.format(dbFormatter);
				shift.endTime = candidateEnd.format(dbFormatter);
				shift.persist();
				for (ShiftTemplateSkillEntity source : skillsByTemplate.getOrDefault(template.id, List.of())) {
					ShiftSkillEntity link = new ShiftSkillEntity();
					link.shiftId = shift.id;
					link.skillId = source.skillId;
					link.skillTypeId = source.skillTypeId;
					link.persist();
				}
				created++;
			}
		}
		return created;
	}

	@Transactional
	public int applySavedTemplateToWindowOrm(int headerId, int structureId,
			LocalDateTime start, LocalDateTime end) {
		if (!validTemplateWindow(start, end) || structureId <= 0) return -1;
		if (ShiftTemplateHeaderEntity.count("id = ?1 and structureId = ?2", headerId, structureId) == 0) return -1;
		return instantiateTemplatesOrm(ShiftTemplateEntity.list(
				"headerId = ?1 order by dayOfWeek, startTime", headerId), structureId, start, end);
	}

	@Transactional
	public int applyTemplateToWindowOrm(int structureId, LocalDateTime start, LocalDateTime end) {
		if (!validTemplateWindow(start, end) || structureId <= 0) return -1;
		return instantiateTemplatesOrm(ShiftTemplateEntity.list(
				"structureId = ?1 and headerId is null order by dayOfWeek, startTime", structureId),
				structureId, start, end);
	}

	private static boolean validTemplateWindow(LocalDateTime start, LocalDateTime end) {
		return start != null && end != null && start.isBefore(end)
				&& java.time.temporal.ChronoUnit.DAYS.between(start.toLocalDate(), end.toLocalDate()) <= 62;
	}

	@Transactional
	public void prepopulateTemplateFromWeekOrm(int structureId, LocalDateTime weekStart) {
		List<ShiftTemplateEntity> old = ShiftTemplateEntity.list(
				"structureId = ?1 and headerId is null", structureId);
		for (ShiftTemplateEntity template : old) {
			ShiftTemplateSkillEntity.delete("templateId", template.id);
			template.delete();
		}
		Set<Integer> activeLocations = LocationEntity.<LocationEntity>list(
				"structureId = ?1 and active = ?2", structureId, true).stream()
				.map(location -> location.id).collect(Collectors.toSet());
		for (Shift shift : getShifts(structureId, weekStart, weekStart.plusDays(7))) {
			if (!activeLocations.contains(shift.getLocation_id())) continue;
			ShiftTemplateEntity template = new ShiftTemplateEntity();
			template.structureId = structureId;
			template.dayOfWeek = (shift.getStart().getDayOfWeek().getValue() + 6) % 7;
			template.startTime = shift.getStart().toLocalTime().format(timeFormatter);
			template.endTime = shift.getEnd().toLocalTime().format(timeFormatter);
			template.locationId = shift.getLocation_id();
			template.persist();
			persistTemplateSkills(template.id, shift.getRequiredSkill(), 1);
			persistTemplateSkills(template.id, shift.getOptionalSkill(), 2);
		}
	}


	/**
	 * @brief Saves the real week as a NEW named template (does not replace anything).
	 * @details Creates a header with the description and copies the week's shift pattern
	 *          (day/time/location/used skills) into shift_templates with that header_id.
	 *          Does NOT save assigned employees (templates have none).
	 * @return the created header ID, or -1 on error.
	 */
	public int addSavedTemplateFromWeek(int structureId, LocalDateTime weekStart, String description) {
		ensureSchemaInitialized();
		List<Shift> weekShifts = getShifts(structureId, weekStart, weekStart.plusDays(7));
		try (Connection conn = DatabaseConnection.connect(dbName)) {
			conn.setAutoCommit(false);
			Set<Integer> activeLocations = getActiveLocationIds(conn, structureId);
			int headerId;
			String hq = "INSERT INTO shift_template_headers (structure_id, description, created_at) VALUES (?, ?, datetime('now','localtime'));";
			try (PreparedStatement stmt = conn.prepareStatement(hq, Statement.RETURN_GENERATED_KEYS)) {
				stmt.setInt(1, structureId);
				stmt.setString(2, description != null ? description.trim() : "");
				stmt.executeUpdate();
				try (ResultSet keys = stmt.getGeneratedKeys()) {
					if (!keys.next()) { conn.rollback(); return -1; }
					headerId = keys.getInt(1);
				}
			}
			String q = "INSERT INTO shift_templates (structure_id, day_of_week, start_time, end_time, location_id, header_id) VALUES (?, ?, ?, ?, ?, ?);";
			for (Shift s : weekShifts) {
				if (s.getStart() == null || s.getEnd() == null) continue;
				if (!activeLocations.contains(s.getLocation_id())) continue;
				int dow = (s.getStart().getDayOfWeek().getValue() + 6) % 7;
				int templateId;
				try (PreparedStatement stmt = conn.prepareStatement(q, Statement.RETURN_GENERATED_KEYS)) {
					stmt.setInt(1, structureId);
					stmt.setInt(2, dow);
					stmt.setString(3, s.getStart().toLocalTime().format(timeFormatter));
					stmt.setString(4, s.getEnd().toLocalTime().format(timeFormatter));
					stmt.setInt(5, s.getLocation_id());
					stmt.setInt(6, headerId);
					stmt.executeUpdate();
					try (ResultSet keys = stmt.getGeneratedKeys()) {
						if (!keys.next()) continue;
						templateId = keys.getInt(1);
					}
				}
				List<Integer> req = new ArrayList<>();
				if (s.getRequiredSkill() != null)
					for (Skill sk : s.getRequiredSkill()) if (sk.isUsed()) req.add(sk.getId());
				List<Integer> opt = new ArrayList<>();
				if (s.getOptionalSkill() != null)
					for (Skill sk : s.getOptionalSkill()) if (sk.isUsed()) opt.add(sk.getId());
				insertTemplateSkills(conn, templateId, req, 1);
				insertTemplateSkills(conn, templateId, opt, 2);
			}
			conn.commit();
			return headerId;
		} catch (Exception e) {
			logger.log(Level.SEVERE, "Error saving named template from week", e);
			return -1;
		}
	}


	/** @brief Shift rows of a saved template (for apply), with used skills. */
	private List<ShiftTemplate> getShiftTemplatesByHeader(int headerId, Connection conn) throws SQLException {
		List<ShiftTemplate> templates = new ArrayList<>();
		String query = "SELECT t.id, t.structure_id, t.day_of_week, t.start_time, t.end_time, " +
			"t.location_id, l.name AS location_desc " +
			"FROM shift_templates t JOIN locations l ON t.location_id = l.id " +
			"WHERE t.header_id = ? ORDER BY t.day_of_week, t.start_time;";
		try (PreparedStatement stmt = conn.prepareStatement(query)) {
			stmt.setInt(1, headerId);
			try (ResultSet rs = stmt.executeQuery()) {
				while (rs.next()) {
					int id = rs.getInt("id");
					templates.add(new ShiftTemplate(
						id, rs.getInt("structure_id"), rs.getInt("day_of_week"),
						rs.getString("start_time"), rs.getString("end_time"),
						rs.getInt("location_id"), rs.getString("location_desc"),
						getUsedTemplateSkills(id, 1, conn), getUsedTemplateSkills(id, 2, conn)));
				}
			}
		}
		return templates;
	}

	/**
	 * @brief Applies a saved template to the [start, end) window (REPLACES window shifts).
	 * @return number of shifts created.
	 */
	public int applySavedTemplateToWindow(int headerId, int structureId, LocalDateTime start, LocalDateTime end) {
		ensureSchemaInitialized();
		int created = 0;
		try (Connection conn = DatabaseConnection.connect(dbName)) {
			conn.setAutoCommit(false);
			List<ShiftTemplate> templates = getShiftTemplatesByHeader(headerId, conn);
			if (templates.isEmpty()) { conn.rollback(); return -2; }
			Set<Integer> activeLocations = getActiveLocationIds(conn, structureId);
			deleteShiftsInWindow(conn, structureId, start, end);
			for (LocalDate d = start.toLocalDate(); !d.isAfter(end.toLocalDate()); d = d.plusDays(1)) {
				int dow = (d.getDayOfWeek().getValue() + 6) % 7;
				for (ShiftTemplate tpl : templates) {
					if (tpl.getDayOfWeek() != dow) continue;
					if (!activeLocations.contains(tpl.getLocationId())) continue;
					LocalDateTime s = LocalDateTime.of(d, LocalTime.parse(tpl.getStartTime()));
					LocalDateTime e = LocalDateTime.of(d, LocalTime.parse(tpl.getEndTime()));
					if (s.isBefore(start) || !s.isBefore(end) || e.isAfter(end)) continue;
					Shift shift = new Shift(0, s, e, tpl.getLocationId(), null, null, null, null);
					List<Integer> req = tpl.getRequiredSkills().stream().map(Skill::getId).collect(Collectors.toList());
					List<Integer> opt = tpl.getOptionalSkills().stream().map(Skill::getId).collect(Collectors.toList());
					addShift(conn, shift, req, opt);
					created++;
				}
			}
			conn.commit();
		} catch (Exception e) {
			logger.log(Level.SEVERE, "Error applying saved template", e);
			throw new RuntimeException("Error applying saved template", e);
		}
		return created;
	}




	private void insertTemplateSkills(Connection conn, int templateId, List<Integer> skillIds, int skillTypeId) throws SQLException {
		if (skillIds == null || skillIds.isEmpty()) return;
		String q = "INSERT INTO shift_template_skills (template_id, skill_id, skill_type_id) VALUES (?, ?, ?);";
		try (PreparedStatement stmt = conn.prepareStatement(q)) {
			for (Integer sid : skillIds) {
				stmt.setInt(1, templateId);
				stmt.setInt(2, sid);
				stmt.setInt(3, skillTypeId);
				stmt.addBatch();
			}
			stmt.executeBatch();
		}
	}




	/** @brief Removes all shift templates for a structure (used by prepopulation). */
	private void deleteAllTemplatesForStructure(Connection conn, int structureId) throws SQLException {
		// Only the "working" template (header_id IS NULL): named saved templates must not be touched.
		try (PreparedStatement s = conn.prepareStatement(
				"DELETE FROM shift_template_skills WHERE template_id IN (SELECT id FROM shift_templates WHERE structure_id = ? AND header_id IS NULL);")) {
			s.setInt(1, structureId);
			s.executeUpdate();
		}
		try (PreparedStatement s = conn.prepareStatement("DELETE FROM shift_templates WHERE structure_id = ? AND header_id IS NULL;")) {
			s.setInt(1, structureId);
			s.executeUpdate();
		}
	}

	/** @brief IDs of active locations only (to exclude disabled ones from templates and population). */
	private Set<Integer> getActiveLocationIds(Connection conn, int structureId) throws SQLException {
		Set<Integer> ids = new HashSet<>();
		try (PreparedStatement stmt = conn.prepareStatement(
				"SELECT id FROM locations WHERE structure_id = ? AND active = 1;")) {
			stmt.setInt(1, structureId);
			try (ResultSet rs = stmt.executeQuery()) {
				while (rs.next()) ids.add(rs.getInt(1));
			}
		}
		return ids;
	}

	/**
	 * @brief Builds the weekly template from the real shifts of a week.
	 * @param structureId structure
	 * @param weekStart Monday at 00:00 of the week to copy
	 * @details Replaces the structure's entire template. Each shift in the week becomes a shift
	 *          template based on (day of week, time of day, location, skills). Shifts at disabled
	 *          locations are skipped: future population would turn them into shifts invisible in
	 *          Shift Management but still binding for the solver.
	 */
	public void prepopulateTemplateFromWeek(int structureId, LocalDateTime weekStart) {
		ensureSchemaInitialized();
		List<Shift> weekShifts = getShifts(structureId, weekStart, weekStart.plusDays(7));
		try (Connection conn = DatabaseConnection.connect(dbName)) {
			conn.setAutoCommit(false);
			Set<Integer> activeLocations = getActiveLocationIds(conn, structureId);
			deleteAllTemplatesForStructure(conn, structureId);
			String q = "INSERT INTO shift_templates (structure_id, day_of_week, start_time, end_time, location_id) VALUES (?, ?, ?, ?, ?);";
			for (Shift s : weekShifts) {
				if (s.getStart() == null || s.getEnd() == null) continue;
				if (!activeLocations.contains(s.getLocation_id())) continue;
				int dow = (s.getStart().getDayOfWeek().getValue() + 6) % 7; // 1=Mon..7=Sun → 0=Mon..6=Sun
				int templateId;
				try (PreparedStatement stmt = conn.prepareStatement(q, Statement.RETURN_GENERATED_KEYS)) {
					stmt.setInt(1, structureId);
					stmt.setInt(2, dow);
					stmt.setString(3, s.getStart().toLocalTime().format(timeFormatter));
					stmt.setString(4, s.getEnd().toLocalTime().format(timeFormatter));
					stmt.setInt(5, s.getLocation_id());
					stmt.executeUpdate();
					try (ResultSet keys = stmt.getGeneratedKeys()) {
						if (!keys.next()) continue;
						templateId = keys.getInt(1);
					}
				}
				List<Integer> req = new ArrayList<>();
				if (s.getRequiredSkill() != null)
					for (Skill sk : s.getRequiredSkill()) if (sk.isUsed()) req.add(sk.getId());
				List<Integer> opt = new ArrayList<>();
				if (s.getOptionalSkill() != null)
					for (Skill sk : s.getOptionalSkill()) if (sk.isUsed()) opt.add(sk.getId());
				insertTemplateSkills(conn, templateId, req, 1);
				insertTemplateSkills(conn, templateId, opt, 2);
			}
			conn.commit();
		} catch (Exception e) {
			logger.log(Level.SEVERE, "Error prepopulating template from week", e);
		}
	}

	/** @brief Deletes shifts (and their skills) starting in [start, end) for the structure. */
	private void deleteShiftsInWindow(Connection conn, int structureId, LocalDateTime start, LocalDateTime end) throws SQLException {
		String startS = start.format(dbFormatter);
		String endS = end.format(dbFormatter);
			try (PreparedStatement s = conn.prepareStatement(
					"DELETE FROM shift_skills WHERE shift_id IN (" +
					"SELECT sh.id FROM shifts sh JOIN locations l ON sh.location_id = l.id " +
					"WHERE l.structure_id = ? AND sh.start_time >= ? AND sh.start_time < ?);")) {
				s.setInt(1, structureId); s.setString(2, startS); s.setString(3, endS);
				s.executeUpdate();
			}
			try (PreparedStatement s = conn.prepareStatement(
					"DELETE FROM shifts WHERE location_id IN (SELECT id FROM locations WHERE structure_id = ?) " +
					"AND start_time >= ? AND start_time < ?;")) {
				s.setInt(1, structureId); s.setString(2, startS); s.setString(3, endS);
				s.executeUpdate();
		}
	}

	/**
	 * @brief Populates [start, end) with weekly-template shifts (REPLACE mode).
	 * @details First deletes existing shifts starting within the window, then instantiates the shift
	 *          templates for the corresponding day of week on each date (in month view, the pattern
	 *          repeats across every week). Created shifts are UNASSIGNED (the solver assigns them).
	 * @return the number of shifts created.
	 */
	/** @brief Structure's "working" template (header_id IS NULL), with used skills.
	 *         Internal use by applyTemplateToWindow (REST reads have migrated to Panache). */
	private List<ShiftTemplate> getWorkTemplates(int structureId) {
		List<ShiftTemplate> templates = new ArrayList<>();
		String query = "SELECT t.id, t.structure_id, t.day_of_week, t.start_time, t.end_time, " +
			"t.location_id, l.name AS location_desc " +
			"FROM shift_templates t JOIN locations l ON t.location_id = l.id " +
			"WHERE t.structure_id = ? AND t.header_id IS NULL ORDER BY t.day_of_week, t.start_time;";
		try (Connection conn = DatabaseConnection.connect(dbName);
			 PreparedStatement stmt = conn.prepareStatement(query)) {
			stmt.setInt(1, structureId);
			try (ResultSet rs = stmt.executeQuery()) {
				while (rs.next()) {
					int id = rs.getInt("id");
					templates.add(new ShiftTemplate(
						id, rs.getInt("structure_id"), rs.getInt("day_of_week"),
						rs.getString("start_time"), rs.getString("end_time"),
						rs.getInt("location_id"), rs.getString("location_desc"),
						getUsedTemplateSkills(id, 1, conn), getUsedTemplateSkills(id, 2, conn)));
				}
			}
		} catch (Exception e) {
			logger.log(Level.SEVERE, "Error fetching work templates", e);
		}
		return templates;
	}

	public int applyTemplateToWindow(int structureId, LocalDateTime start, LocalDateTime end) {
		ensureSchemaInitialized();
		List<ShiftTemplate> templates = getWorkTemplates(structureId);
		if (templates.isEmpty()) return -2;
		int created = 0;
		try (Connection conn = DatabaseConnection.connect(dbName)) {
			conn.setAutoCommit(false);
			// Templates remain visible/manageable in Configuration even for disabled locations,
			// but must NOT be instantiated here: they would create ghost shifts (invisible in
			// Shift Management, which renders only active locations, but binding for the solver).
			Set<Integer> activeLocations = getActiveLocationIds(conn, structureId);
			deleteShiftsInWindow(conn, structureId, start, end);
			for (LocalDate d = start.toLocalDate(); !d.isAfter(end.toLocalDate()); d = d.plusDays(1)) {
				int dow = (d.getDayOfWeek().getValue() + 6) % 7;
				for (ShiftTemplate tpl : templates) {
					if (tpl.getDayOfWeek() != dow) continue;
					if (!activeLocations.contains(tpl.getLocationId())) continue;
					LocalDateTime s = LocalDateTime.of(d, LocalTime.parse(tpl.getStartTime()));
					LocalDateTime e = LocalDateTime.of(d, LocalTime.parse(tpl.getEndTime()));
					if (s.isBefore(start) || !s.isBefore(end) || e.isAfter(end)) continue;
					Shift shift = new Shift(0, s, e, tpl.getLocationId(), null, null, null, null);
					List<Integer> req = tpl.getRequiredSkills().stream().map(Skill::getId).collect(Collectors.toList());
					List<Integer> opt = tpl.getOptionalSkills().stream().map(Skill::getId).collect(Collectors.toList());
					addShift(conn, shift, req, opt);
					created++;
				}
			}
			conn.commit();
		} catch (Exception e) {
			logger.log(Level.SEVERE, "Error applying shift template", e);
			throw new RuntimeException("Error applying shift template", e);
		}
		return created;
	}

	/**
	 * @brief Persists the solver solution's shift-to-employee assignments in bulk.
	 * @details Updates employee_id for the specified shifts (single transaction, batch) and clears
	 *          the pinned flag: the UI exposes no "pin" concept (a remnant of the legacy
	 *          /save_week_assignments flow), so an explicit user save is the new truth and must not
	 *          leave shifts frozen for future solves. A null employeeId means an unassigned shift.
	 * @return number of updated shifts, or -1 on error.
	 */
	public int saveShiftAssignments(List<ShiftAssignment> assignments, String windowStart, String windowEnd) {
		if (assignments == null || assignments.isEmpty()) return 0;
		// Server-side defense: if the solve window is known, UPDATE touches only shifts within it.
		// A context shift (outside the window, whose ID might arrive in the payload) remains outside
		// the WHERE clause and is never rewritten. Without a window (whole-company solve), preserve
		// ID-based behavior.
		boolean bounded = windowStart != null && !windowStart.isBlank()
			&& windowEnd != null && !windowEnd.isBlank();
		String query = bounded
			? "UPDATE shifts SET employee_id = ?, pinned = 0 WHERE id = ? AND start_time >= ? AND start_time < ?;"
			: "UPDATE shifts SET employee_id = ?, pinned = 0 WHERE id = ?;";
		try (Connection conn = DatabaseConnection.connect(dbName)) {
			conn.setAutoCommit(false);
			int updated = 0;
			try (PreparedStatement stmt = conn.prepareStatement(query)) {
				for (ShiftAssignment a : assignments) {
					if (a.getEmployeeId() != null) stmt.setInt(1, a.getEmployeeId());
					else stmt.setNull(1, Types.INTEGER);
					stmt.setInt(2, a.getShiftId());
					if (bounded) {
						stmt.setString(3, windowStart);
						stmt.setString(4, windowEnd);
					}
					stmt.addBatch();
				}
				for (int n : stmt.executeBatch()) if (n > 0) updated += n;
			}
			conn.commit();
			return updated;
		} catch (Exception e) {
			logger.log(Level.SEVERE, "Error saving shift assignments", e);
			return -1;
		}
	}

	@Transactional
	public int saveShiftAssignmentsOrm(List<ShiftAssignment> assignments, int structureId,
			String windowStart, String windowEnd) {
		if (assignments == null || assignments.isEmpty()) return 0;
		if (structureId <= 0) return -1;
		boolean bounded = windowStart != null && !windowStart.isBlank()
				&& windowEnd != null && !windowEnd.isBlank();
		Set<Integer> shiftIds = assignments.stream().map(ShiftAssignment::getShiftId).collect(Collectors.toSet());
		Map<Integer, ShiftEntity> shifts = loadInChunks(shiftIds,
				chunk -> ShiftEntity.<ShiftEntity>list("id in ?1", chunk)).stream()
				.collect(Collectors.toMap(shift -> shift.id, shift -> shift));
		Set<Integer> locationIds = shifts.values().stream().map(shift -> shift.locationId).collect(Collectors.toSet());
		Map<Integer, LocationEntity> locations = loadInChunks(locationIds,
				chunk -> LocationEntity.<LocationEntity>list("id in ?1", chunk)).stream()
				.collect(Collectors.toMap(location -> location.id, location -> location));
		Set<Integer> employeeIds = assignments.stream().map(ShiftAssignment::getEmployeeId)
				.filter(java.util.Objects::nonNull).collect(Collectors.toSet());
		Map<Integer, EmployeeEntity> employees = loadInChunks(employeeIds,
				chunk -> EmployeeEntity.<EmployeeEntity>list("id in ?1", chunk)).stream()
				.collect(Collectors.toMap(employee -> employee.id, employee -> employee));
		for (ShiftAssignment assignment : assignments) {
			ShiftEntity shift = shifts.get(assignment.getShiftId());
			if (shift == null) return -1;
			LocationEntity location = locations.get(shift.locationId);
			if (location == null || location.structureId != structureId || !location.active) return -1;
			EmployeeEntity employee = assignment.getEmployeeId() != null
					? employees.get(assignment.getEmployeeId()) : null;
			if (assignment.getEmployeeId() != null
					&& (employee == null || employee.structureId != structureId || !employee.active)) return -1;
		}
		// Check revisions BEFORE writing any row: either save the entire solution or none of it.
		// Saving half would leave an inconsistent schedule, worse than either alternative.
		for (ShiftAssignment assignment : assignments) {
			ShiftEntity shift = shifts.get(assignment.getShiftId());
			if (bounded && (shift.startTime.compareTo(windowStart) < 0
					|| shift.startTime.compareTo(windowEnd) >= 0)) continue;
			if (assignment.getVersion() != null && assignment.getVersion() != shift.version)
				return STALE_SHIFTS;
			if (assignment.getEmployeeId() != null && assignment.getEmployeeStatusUpdatedAt() != null) {
				EmployeeEntity employee = employees.get(assignment.getEmployeeId());
				String currentRevision = employee.statusUpdatedAt == null ? "" : employee.statusUpdatedAt;
				if (!assignment.getEmployeeStatusUpdatedAt().equals(currentRevision)) return STALE_EMPLOYEES;
			}
		}
		// 同一次保存里，同一个人不得落在两个时间重叠的班次上。
		// 候选页只能看到**已落库**的班次，同一订单里还没保存的空名额互相看不见，
		// 于是同一个人很容易被同时点到两个名额上；这条路（求解器保存）又不校验冲突，
		// 真写下去就是一条实打实的重复派工。这里只兜「本次保存内部」的重复：
		// 历史上本来就带风险的其他分配仍然放行，否则"只补一个空缺"就做不成了。
		Map<Integer, List<ShiftEntity>> savedRowsByEmployee = new HashMap<>();
		for (ShiftAssignment assignment : assignments) {
			if (assignment.getEmployeeId() == null) continue;
			ShiftEntity shift = shifts.get(assignment.getShiftId());
			if (bounded && (shift.startTime.compareTo(windowStart) < 0
					|| shift.startTime.compareTo(windowEnd) >= 0)) continue;
			savedRowsByEmployee.computeIfAbsent(assignment.getEmployeeId(), key -> new ArrayList<>()).add(shift);
		}
		for (List<ShiftEntity> rows : savedRowsByEmployee.values()) {
			if (rows.size() < 2) continue;
			rows.sort((left, right) -> left.startTime.compareTo(right.startTime));
			for (int index = 1; index < rows.size(); index++) {
				// 升序后只需比较相邻两条：只要有重叠，必然有一对相邻重叠。
				if (rows.get(index - 1).endTime.compareTo(rows.get(index).startTime) > 0)
					return DUPLICATE_ASSIGNMENT;
			}
		}
		int updated = 0;
		for (ShiftAssignment assignment : assignments) {
			ShiftEntity shift = shifts.get(assignment.getShiftId());
			if (bounded && (shift.startTime.compareTo(windowStart) < 0
					|| shift.startTime.compareTo(windowEnd) >= 0)) continue;
			// The solver save path edits shifts directly, bypassing the review workflow. When it
			// changes the assignment of a shift that a published plan put in place, the shift no
			// longer reflects any publication: clear the provenance marker instead of leaving a
			// stale link that makes the schedule look synchronized with the plan when it is not.
			if (shift.publishedPlanId != null
					&& !java.util.Objects.equals(shift.employeeId, assignment.getEmployeeId())) {
				shift.publishedPlanId = null;
			}
			shift.employeeId = assignment.getEmployeeId();
			shift.pinned = false;
			updated++;
		}
		return updated;
	}

	/**
	 * @brief Outcome of {@link #saveShiftAssignmentsOrm} when a shift changed after solving.
	 * @details Distinct from -1 (invalid data) because the cause differs and the user needs a
	 *          different message: they did nothing wrong and only need to run the solver again.
	 */
	public static final int STALE_SHIFTS = -2;
	public static final int STALE_EMPLOYEES = -3;
	/**
	 * @brief 同一次保存里同一个人被排到两个时间重叠的班次上。
	 * @details 与 {@link #STALE_SHIFTS} 一样属于「客户端要换个做法」而不是「数据非法」：
	 *          调度员把一个人同时点进了同一订单的两个名额。
	 */
	public static final int DUPLICATE_ASSIGNMENT = -4;

	/**
	 * @brief Employee date-constraint summary: counts by type, only for employees with constraints.
	 * @details Populates the "Employee Date Preferences" page table: one row for each employee in
	 *          the structure who has at least one constraint.
	 * @return list of maps {employee_id, full_name, desired, undesired, unavailable, total}
	 */
	public List<Map<String, Object>> getEmployeeDatesSummary(int structureId) {
		List<Map<String, Object>> summary = new ArrayList<>();
		String query =
			"SELECT e.id, e.first_name || ' ' || e.last_name AS full_name, " +
			"  SUM(CASE WHEN ed.date_type_id = 1 THEN 1 ELSE 0 END) AS desired, " +
			"  SUM(CASE WHEN ed.date_type_id = 2 THEN 1 ELSE 0 END) AS undesired, " +
			"  SUM(CASE WHEN ed.date_type_id = 3 THEN 1 ELSE 0 END) AS unavailable, " +
			"  COUNT(*) AS total " +
			"FROM employees e JOIN employee_dates ed ON ed.employee_id = e.id " +
			"WHERE e.structure_id = ? " +
			"GROUP BY e.id ORDER BY full_name;";
		try (Connection conn = DatabaseConnection.connect(dbName);
			 PreparedStatement stmt = conn.prepareStatement(query)) {
			stmt.setInt(1, structureId);
			try (ResultSet rs = stmt.executeQuery()) {
				while (rs.next()) {
					Map<String, Object> row = new LinkedHashMap<>();
					row.put("employee_id", rs.getInt("id"));
					row.put("full_name", rs.getString("full_name"));
					row.put("desired", rs.getInt("desired"));
					row.put("undesired", rs.getInt("undesired"));
					row.put("unavailable", rs.getInt("unavailable"));
					row.put("total", rs.getInt("total"));
					summary.add(row);
				}
			}
		} catch (Exception e) {
			logger.log(Level.SEVERE, "Error fetching employee dates summary", e);
		}
		return summary;
	}

	public List<Map<String, Object>> getEmployeeDatesSummaryOrm(int structureId) {
		List<Map<String, Object>> result = new ArrayList<>();
		List<Object[]> rows = em.createQuery(
				"select e.id, e.firstName, e.lastName, "
				+ "sum(case when ed.dateTypeId = 1 then 1 else 0 end), "
				+ "sum(case when ed.dateTypeId = 2 then 1 else 0 end), "
				+ "sum(case when ed.dateTypeId = 3 then 1 else 0 end), count(ed.id) "
				+ "from EmployeeEntity e, EmployeeDateEntity ed "
				+ "where ed.employeeId = e.id and e.structureId = ?1 "
				+ "group by e.id, e.firstName, e.lastName order by e.firstName, e.lastName, e.id",
				Object[].class).setParameter(1, structureId).getResultList();
		for (Object[] rowData : rows) {
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("employee_id", ((Number) rowData[0]).intValue());
			row.put("full_name", rowData[1] + " " + rowData[2]);
			row.put("desired", ((Number) rowData[3]).longValue());
			row.put("undesired", ((Number) rowData[4]).longValue());
			row.put("unavailable", ((Number) rowData[5]).longValue());
			row.put("total", ((Number) rowData[6]).longValue());
			result.add(row);
		}
		return result;
	}

	// =======================================================================
	// Email templates (subject + HTML body with placeholders, per structure)
	// =======================================================================

	/** @brief Creates the email-template table idempotently. Called by schema initialization. */
	private void ensureEmailTemplatesTable() {
		try (Connection conn = DatabaseConnection.connect(dbName);
			 Statement stmt = conn.createStatement()) {
			stmt.execute(
				"CREATE TABLE IF NOT EXISTS email_templates (" +
				"  id INTEGER PRIMARY KEY AUTOINCREMENT," +
				"  structure_id INTEGER NOT NULL UNIQUE," +
				"  subject TEXT NOT NULL DEFAULT ''," +
				"  body TEXT NOT NULL DEFAULT ''," +
				"  FOREIGN KEY (structure_id) REFERENCES structures(id) ON DELETE CASCADE" +
				");");
		} catch (Exception e) {
			logger.log(Level.SEVERE, "Error ensuring email_templates table", e);
		}
	}

	/** @brief Creates the SMTP-settings table idempotently. Called by schema initialization. */
	private void ensureEmailSettingsTable() {
		try (Connection conn = DatabaseConnection.connect(dbName);
			 Statement stmt = conn.createStatement()) {
			stmt.execute(
				"CREATE TABLE IF NOT EXISTS email_settings (" +
				"  id INTEGER PRIMARY KEY CHECK (id = 1)," +   // single global row
				"  host TEXT NOT NULL DEFAULT ''," +
				"  port INTEGER NOT NULL DEFAULT 587," +
				"  start_tls INTEGER NOT NULL DEFAULT 1," +
				"  username TEXT NOT NULL DEFAULT ''," +
				"  password TEXT NOT NULL DEFAULT ''," +
				"  mail_from TEXT NOT NULL DEFAULT ''" +
				");");
		} catch (Exception e) {
			logger.log(Level.SEVERE, "Error ensuring email_settings table", e);
		}
	}

	/** @brief Saved SMTP settings (password INCLUDED: the resource masks it), or null if never saved. */
	public EmailSettings getEmailSettings() {
		ensureSchemaInitialized();
		String query = "SELECT host, port, start_tls, username, password, mail_from FROM email_settings WHERE id = 1;";
		try (Connection conn = DatabaseConnection.connect(dbName);
			 PreparedStatement stmt = conn.prepareStatement(query);
			 ResultSet rs = stmt.executeQuery()) {
			if (rs.next()) {
				EmailSettings s = new EmailSettings();
				s.setHost(rs.getString("host"));
				s.setPort(rs.getInt("port"));
				s.setStartTls(rs.getInt("start_tls") == 1);
				s.setUsername(rs.getString("username"));
				s.setPassword(rs.getString("password"));
				s.setMailFrom(rs.getString("mail_from"));
				return s;
			}
		} catch (Exception e) {
			logger.log(Level.SEVERE, "Error fetching email settings", e);
		}
		return null;
	}

	/** @brief Saves SMTP settings (single-row upsert). Empty password means keep the saved one. */
	public boolean saveEmailSettings(EmailSettings s) {
		ensureSchemaInitialized();
		String query = "INSERT INTO email_settings (id, host, port, start_tls, username, password, mail_from) " +
			"VALUES (1, ?, ?, ?, ?, ?, ?) " +
			"ON CONFLICT(id) DO UPDATE SET host = excluded.host, port = excluded.port, " +
			"start_tls = excluded.start_tls, username = excluded.username, " +
			"password = CASE WHEN excluded.password = '' THEN email_settings.password ELSE excluded.password END, " +
			"mail_from = excluded.mail_from;";
		try (Connection conn = DatabaseConnection.connect(dbName);
			 PreparedStatement stmt = conn.prepareStatement(query)) {
			stmt.setString(1, s.getHost() != null ? s.getHost().trim() : "");
			stmt.setInt(2, s.getPort() > 0 ? s.getPort() : 587);
			stmt.setInt(3, s.isStartTls() ? 1 : 0);
			stmt.setString(4, s.getUsername() != null ? s.getUsername().trim() : "");
			stmt.setString(5, s.getPassword() != null ? s.getPassword() : "");
			stmt.setString(6, s.getMailFrom() != null ? s.getMailFrom().trim() : "");
			stmt.executeUpdate();
			return true;
		} catch (Exception e) {
			logger.log(Level.SEVERE, "Error saving email settings", e);
			return false;
		}
	}

	/** @brief Creates the email-delivery log table idempotently. Called by schema initialization. */
	/**
	 * @brief Application-user table on the legacy bootstrap path.
	 *
	 * @details Must remain aligned with `db/migration/{sqlite,postgresql}/V2__app_users.sql`.
	 *          It exists in two places for a precise reason: Flyway is disabled under the default
	 *          profile (`quarkus.flyway.active=false`), and the actual SQLite database schema is
	 *          created here. Adding the table only to the migration would leave the desktop
	 *          installation without a user table, therefore without login.
	 */
	private void ensureAppUsersTable() {
		try (Connection conn = DatabaseConnection.connect(dbName);
			 Statement stmt = conn.createStatement()) {
			stmt.execute(
				"CREATE TABLE IF NOT EXISTS app_users (" +
				"  id INTEGER PRIMARY KEY AUTOINCREMENT," +
				"  username TEXT NOT NULL UNIQUE," +
				"  password_hash TEXT NOT NULL," +   // bcrypt hash only, never plaintext
				"  role TEXT NOT NULL," +            // ADMIN | CAPOSALA
				"  active INTEGER NOT NULL DEFAULT 1," +
				"  display_name TEXT," +
				"  email TEXT," +
				"  created_at TEXT NOT NULL," +
				"  last_login_at TEXT" +
				");");
			stmt.execute("CREATE INDEX IF NOT EXISTS idx_app_users_username ON app_users(username);");
			// Retrofit legacy DBs that already have the table without the email column
			// (same try/ignore pattern as ensureShiftColumns/ensureLocationColumns).
			try { stmt.execute("ALTER TABLE app_users ADD COLUMN email TEXT"); } catch (SQLException ignored) {}
			try { stmt.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_app_users_email ON app_users(email);"); } catch (SQLException ignored) {}
		} catch (Exception e) {
			logger.log(Level.SEVERE, "Error ensuring app_users table", e);
		}
	}

	private void ensureEmailLogTable() {
		try (Connection conn = DatabaseConnection.connect(dbName);
			 Statement stmt = conn.createStatement()) {
			stmt.execute(
				"CREATE TABLE IF NOT EXISTS email_log (" +
				"  id INTEGER PRIMARY KEY AUTOINCREMENT," +
				"  structure_id INTEGER NOT NULL," +
				"  employee_id INTEGER NOT NULL," +
				"  period_slug TEXT NOT NULL," +      // "2026-06-29" (week) or "2026-06" (month)
				"  period_label TEXT NOT NULL DEFAULT ''," +
				"  sent_to TEXT NOT NULL DEFAULT ''," +
				"  filename TEXT NOT NULL DEFAULT ''," +
				"  sent_at TEXT NOT NULL," +          // local "yyyy-MM-dd HH:mm:ss"
				"  UNIQUE(structure_id, employee_id, period_slug)," +
				"  FOREIGN KEY (structure_id) REFERENCES structures(id) ON DELETE CASCADE," +
				"  FOREIGN KEY (employee_id) REFERENCES employees(id) ON DELETE CASCADE" +
				");");
			stmt.execute("CREATE INDEX IF NOT EXISTS idx_email_log_structure_period " +
				"ON email_log(structure_id, period_slug);");
		} catch (Exception e) {
			logger.log(Level.SEVERE, "Error ensuring email_log table", e);
		}
	}

	/** @brief Records a successful email delivery (upsert): keeps the latest per employee+period. */
	public void logEmailSent(int structureId, int employeeId, String periodSlug, String periodLabel,
							 String sentTo, String filename) {
		String query = "INSERT INTO email_log (structure_id, employee_id, period_slug, period_label, sent_to, filename, sent_at) " +
			"VALUES (?, ?, ?, ?, ?, ?, ?) " +
			"ON CONFLICT(structure_id, employee_id, period_slug) DO UPDATE SET " +
			"period_label = excluded.period_label, sent_to = excluded.sent_to, " +
			"filename = excluded.filename, sent_at = excluded.sent_at;";
		try (Connection conn = DatabaseConnection.connect(dbName);
			 PreparedStatement stmt = conn.prepareStatement(query)) {
			stmt.setInt(1, structureId);
			stmt.setInt(2, employeeId);
			stmt.setString(3, periodSlug != null ? periodSlug : "");
			stmt.setString(4, periodLabel != null ? periodLabel : "");
			stmt.setString(5, sentTo != null ? sentTo : "");
			stmt.setString(6, filename != null ? filename : "");
			stmt.setString(7, LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
			stmt.executeUpdate();
		} catch (Exception e) {
			logger.log(Level.SEVERE, "Error logging sent email", e);
		}
	}

	/** @brief Creates the PDF-template table idempotently, one per structure. */
	private void ensurePdfTemplatesTable() {
		try (Connection conn = DatabaseConnection.connect(dbName);
			 Statement stmt = conn.createStatement()) {
			stmt.execute(
				"CREATE TABLE IF NOT EXISTS pdf_templates (" +
				"  id INTEGER PRIMARY KEY AUTOINCREMENT," +
				"  structure_id INTEGER NOT NULL UNIQUE," +
				"  header_text TEXT NOT NULL DEFAULT ''," +
				"  footer_text TEXT NOT NULL DEFAULT ''," +
				"  logo_data_url TEXT NOT NULL DEFAULT ''," +
				"  primary_color TEXT NOT NULL DEFAULT '#2980B9'," +
				"  FOREIGN KEY (structure_id) REFERENCES structures(id) ON DELETE CASCADE" +
				");");
		} catch (Exception e) {
			logger.log(Level.SEVERE, "Error ensuring pdf_templates table", e);
		}
	}

	/** Verifies that the selected employee belongs to the requested structure. */
	public boolean employeeBelongsToStructure(int employeeId, int structureId) {
		String query = "SELECT 1 FROM employees WHERE id = ? AND structure_id = ?;";
		try (Connection conn = DatabaseConnection.connect(dbName);
			 PreparedStatement stmt = conn.prepareStatement(query)) {
			stmt.setInt(1, employeeId);
			stmt.setInt(2, structureId);
			try (ResultSet rs = stmt.executeQuery()) {
				return rs.next();
			}
		} catch (Exception e) {
			logger.log(Level.SEVERE, "Error checking employee structure", e);
			return false;
		}
	}

	/** @brief Structure delivery log for a period (latest delivery per employee). */
	public List<EmailLogEntry> getEmailLog(int structureId, String periodSlug) {
		ensureSchemaInitialized();
		List<EmailLogEntry> entries = new ArrayList<>();
		String query = "SELECT employee_id, sent_at, sent_to FROM email_log " +
			"WHERE structure_id = ? AND period_slug = ?;";
		try (Connection conn = DatabaseConnection.connect(dbName);
			 PreparedStatement stmt = conn.prepareStatement(query)) {
			stmt.setInt(1, structureId);
			stmt.setString(2, periodSlug != null ? periodSlug : "");
			try (ResultSet rs = stmt.executeQuery()) {
				while (rs.next())
					entries.add(new EmailLogEntry(rs.getInt("employee_id"), rs.getString("sent_at"), rs.getString("sent_to")));
			}
		} catch (Exception e) {
			logger.log(Level.SEVERE, "Error fetching email log", e);
		}
		return entries;
	}

	/** @brief Structure email template, or an empty template if none has been saved. */
	public EmailTemplate getEmailTemplate(int structureId) {
		ensureSchemaInitialized();
		String query = "SELECT id, structure_id, subject, body FROM email_templates WHERE structure_id = ?;";
		try (Connection conn = DatabaseConnection.connect(dbName);
			 PreparedStatement stmt = conn.prepareStatement(query)) {
			stmt.setInt(1, structureId);
			try (ResultSet rs = stmt.executeQuery()) {
				if (rs.next()) {
					return new EmailTemplate(rs.getInt("id"), rs.getInt("structure_id"),
						rs.getString("subject"), rs.getString("body"));
				}
			}
		} catch (Exception e) {
			logger.log(Level.SEVERE, "Error fetching email template", e);
		}
		return new EmailTemplate(0, structureId, "", "");
	}

	/** @brief Saves (upserts) the structure email template. @return true on success. */
	public boolean saveEmailTemplate(int structureId, String subject, String body) {
		ensureSchemaInitialized();
		String query = "INSERT INTO email_templates (structure_id, subject, body) VALUES (?, ?, ?) " +
			"ON CONFLICT(structure_id) DO UPDATE SET subject = excluded.subject, body = excluded.body;";
		try (Connection conn = DatabaseConnection.connect(dbName);
			 PreparedStatement stmt = conn.prepareStatement(query)) {
			stmt.setInt(1, structureId);
			stmt.setString(2, subject != null ? subject : "");
			stmt.setString(3, body != null ? body : "");
			stmt.executeUpdate();
			return true;
		} catch (Exception e) {
			logger.log(Level.SEVERE, "Error saving email template", e);
			return false;
		}
	}

	public SolverSettings getSolverSettings(int structureId) {
		ensureSchemaInitialized();
		try (Connection conn=DatabaseConnection.connect(dbName); PreparedStatement st=conn.prepareStatement("SELECT * FROM solver_settings WHERE structure_id=?")) {
			st.setInt(1, structureId);
			try (ResultSet r=st.executeQuery()) { if (r.next()) {
				SolverSettings s = new SolverSettings(r.getInt("id"), structureId,
				r.getInt("max_solve_seconds"), r.getInt("unimproved_seconds"), r.getInt("minimum_rest_hours"),
				r.getInt("max_shifts_per_day"), r.getInt("desired_date_weight"), r.getInt("undesired_date_weight"),
				r.getInt("balance_weight"), r.getInt("optional_skill_weight"));
				s.setBalanceByHours(r.getBoolean("balance_by_hours")); s.setMaxWeeklyHours(r.getInt("max_weekly_hours"));
				s.setMinWeeklyShifts(r.getInt("min_weekly_shifts")); s.setMaxWeeklyShifts(r.getInt("max_weekly_shifts"));
				s.setMaxConsecutiveDays(r.getInt("max_consecutive_days")); s.setMinDaysOffPerWeek(r.getInt("min_days_off_per_week"));
				s.setAllowUnassigned(r.getBoolean("allow_unassigned")); s.setUnassignedWeight(r.getInt("unassigned_weight"));
				s.setSameLocationWeight(r.getInt("same_location_weight")); s.setNightBalanceWeight(r.getInt("night_balance_weight"));
				s.setNightStartHour(r.getInt("night_start_hour")); s.setNightEndHour(r.getInt("night_end_hour"));
				s.setStopWhenFeasible(r.getBoolean("stop_when_feasible"));
				s.setContextDays(r.getInt("context_days"));
				s.setDiminishedWindowSeconds(r.getInt("diminished_window_seconds"));
				s.setDiminishedRatioPct(r.getInt("diminished_ratio_pct"));
				s.setWeeklyShiftWeight(r.getInt("weekly_shift_weight"));
				s.setDaysOffWeight(r.getInt("days_off_weight"));
				s.setConsecutiveDaysWeight(r.getInt("consecutive_days_weight")); return s;
			} }
		} catch(Exception e){logger.log(Level.SEVERE,"Error loading solver settings",e);}
		return new SolverSettings(0, structureId, 30, 0, 10, 1, 1, 1, 1, 1);
	}

	private void ensureSkillColumns() {
		try (Connection conn = DatabaseConnection.connect(dbName); Statement stmt = conn.createStatement()) {
			try { stmt.execute("ALTER TABLE skills ADD COLUMN active INTEGER NOT NULL DEFAULT 1"); } catch (SQLException ignored) {}
		} catch (Exception e) {
			logger.log(Level.SEVERE, "Error during skill column migration", e);
		}
	}

	public boolean saveSolverSettings(int structureId, SolverSettings s) {
		ensureSchemaInitialized();
		String sql="INSERT INTO solver_settings (structure_id,max_solve_seconds,unimproved_seconds,minimum_rest_hours,max_shifts_per_day,desired_date_weight,undesired_date_weight,balance_weight,optional_skill_weight,balance_by_hours,max_weekly_hours,min_weekly_shifts,max_weekly_shifts,max_consecutive_days,min_days_off_per_week,allow_unassigned,unassigned_weight,same_location_weight,night_balance_weight,night_start_hour,night_end_hour,stop_when_feasible,context_days,diminished_window_seconds,diminished_ratio_pct,weekly_shift_weight,days_off_weight,consecutive_days_weight) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(structure_id) DO UPDATE SET max_solve_seconds=excluded.max_solve_seconds,unimproved_seconds=excluded.unimproved_seconds,minimum_rest_hours=excluded.minimum_rest_hours,max_shifts_per_day=excluded.max_shifts_per_day,desired_date_weight=excluded.desired_date_weight,undesired_date_weight=excluded.undesired_date_weight,balance_weight=excluded.balance_weight,optional_skill_weight=excluded.optional_skill_weight,balance_by_hours=excluded.balance_by_hours,max_weekly_hours=excluded.max_weekly_hours,min_weekly_shifts=excluded.min_weekly_shifts,max_weekly_shifts=excluded.max_weekly_shifts,max_consecutive_days=excluded.max_consecutive_days,min_days_off_per_week=excluded.min_days_off_per_week,allow_unassigned=excluded.allow_unassigned,unassigned_weight=excluded.unassigned_weight,same_location_weight=excluded.same_location_weight,night_balance_weight=excluded.night_balance_weight,night_start_hour=excluded.night_start_hour,night_end_hour=excluded.night_end_hour,stop_when_feasible=excluded.stop_when_feasible,context_days=excluded.context_days,diminished_window_seconds=excluded.diminished_window_seconds,diminished_ratio_pct=excluded.diminished_ratio_pct,weekly_shift_weight=excluded.weekly_shift_weight,days_off_weight=excluded.days_off_weight,consecutive_days_weight=excluded.consecutive_days_weight";
		try(Connection conn=DatabaseConnection.connect(dbName);PreparedStatement st=conn.prepareStatement(sql)){
			st.setInt(1,structureId);st.setInt(2,s.getMaxSolveSeconds());st.setInt(3,s.getUnimprovedSeconds());
			st.setInt(4,s.getMinimumRestHours());st.setInt(5,s.getMaxShiftsPerDay());st.setInt(6,s.getDesiredDateWeight());
			st.setInt(7,s.getUndesiredDateWeight());st.setInt(8,s.getBalanceWeight());st.setInt(9,s.getOptionalSkillWeight());
			st.setBoolean(10,s.isBalanceByHours());st.setInt(11,s.getMaxWeeklyHours());st.setInt(12,s.getMinWeeklyShifts());
			st.setInt(13,s.getMaxWeeklyShifts());st.setInt(14,s.getMaxConsecutiveDays());st.setInt(15,s.getMinDaysOffPerWeek());
			st.setBoolean(16,s.isAllowUnassigned());st.setInt(17,s.getUnassignedWeight());st.setInt(18,s.getSameLocationWeight());
			st.setInt(19,s.getNightBalanceWeight());st.setInt(20,s.getNightStartHour());st.setInt(21,s.getNightEndHour());
			st.setBoolean(22,s.isStopWhenFeasible());
			st.setInt(23,s.getContextDays());
			st.setInt(24,s.getDiminishedWindowSeconds());st.setInt(25,s.getDiminishedRatioPct());
			st.setInt(26,s.getWeeklyShiftWeight());st.setInt(27,s.getDaysOffWeight());st.setInt(28,s.getConsecutiveDaysWeight());
			st.executeUpdate();return true;
		}catch(Exception e){logger.log(Level.SEVERE,"Error saving solver settings",e);return false;}
	}

	/** @brief Structure general settings, or defaults if not configured. */
	public GeneralSettings getGeneralSettings(int structureId) {
		ensureSchemaInitialized();
		try (Connection conn=DatabaseConnection.connect(dbName); PreparedStatement st=conn.prepareStatement("SELECT * FROM general_settings WHERE structure_id=?")) {
			st.setInt(1, structureId);
			try (ResultSet r=st.executeQuery()) { if (r.next()) {
				return new GeneralSettings(r.getInt("id"), structureId,
					r.getString("shift_window_mode"), r.getBoolean("auto_populate_from_template"));
			} }
		} catch(Exception e){logger.log(Level.SEVERE,"Error loading general settings",e);}
		return new GeneralSettings(0, structureId, "month", false);
	}

	/** @brief Saves (upserts) the structure general settings. */
	public boolean saveGeneralSettings(int structureId, GeneralSettings s) {
		ensureSchemaInitialized();
		String sql="INSERT INTO general_settings (structure_id,shift_window_mode,auto_populate_from_template) VALUES (?,?,?) "
			+ "ON CONFLICT(structure_id) DO UPDATE SET shift_window_mode=excluded.shift_window_mode,auto_populate_from_template=excluded.auto_populate_from_template";
		try(Connection conn=DatabaseConnection.connect(dbName);PreparedStatement st=conn.prepareStatement(sql)){
			st.setInt(1,structureId);st.setString(2,s.getShiftWindowMode());st.setBoolean(3,s.isAutoPopulateFromTemplate());
			st.executeUpdate();return true;
		}catch(Exception e){logger.log(Level.SEVERE,"Error saving general settings",e);return false;}
	}

	/** @brief Structure PDF template, or defaults if not configured. */
	public PdfTemplate getPdfTemplate(int structureId) {
		ensureSchemaInitialized();
		String query = "SELECT id, structure_id, header_text, footer_text, logo_data_url, primary_color " +
			"FROM pdf_templates WHERE structure_id = ?;";
		try (Connection conn = DatabaseConnection.connect(dbName);
			 PreparedStatement stmt = conn.prepareStatement(query)) {
			stmt.setInt(1, structureId);
			try (ResultSet rs = stmt.executeQuery()) {
				if (rs.next()) return new PdfTemplate(rs.getInt("id"), rs.getInt("structure_id"),
					rs.getString("header_text"), rs.getString("footer_text"),
					rs.getString("logo_data_url"), rs.getString("primary_color"));
			}
		} catch (Exception e) {
			logger.log(Level.SEVERE, "Error fetching PDF template", e);
		}
		return new PdfTemplate(0, structureId, "", "", "", "#2980B9");
	}

	/** @brief Saves (upserts) the structure PDF template. */
	public boolean savePdfTemplate(int structureId, String headerText, String footerText,
								   String logoDataUrl, String primaryColor) {
		ensureSchemaInitialized();
		String query = "INSERT INTO pdf_templates " +
			"(structure_id, header_text, footer_text, logo_data_url, primary_color) VALUES (?, ?, ?, ?, ?) " +
			"ON CONFLICT(structure_id) DO UPDATE SET header_text = excluded.header_text, " +
			"footer_text = excluded.footer_text, logo_data_url = excluded.logo_data_url, " +
			"primary_color = excluded.primary_color;";
		try (Connection conn = DatabaseConnection.connect(dbName);
			 PreparedStatement stmt = conn.prepareStatement(query)) {
			stmt.setInt(1, structureId);
			stmt.setString(2, headerText != null ? headerText : "");
			stmt.setString(3, footerText != null ? footerText : "");
			stmt.setString(4, logoDataUrl != null ? logoDataUrl : "");
			stmt.setString(5, primaryColor != null ? primaryColor : "#2980B9");
			stmt.executeUpdate();
			return true;
		} catch (Exception e) {
			logger.log(Level.SEVERE, "Error saving PDF template", e);
			return false;
		}
	}

	/** @brief Deletes the PDF template associated with the structure. */
	public boolean deletePdfTemplate(int structureId) {
		ensureSchemaInitialized();
		try (Connection conn = DatabaseConnection.connect(dbName);
			 PreparedStatement stmt = conn.prepareStatement("DELETE FROM pdf_templates WHERE structure_id = ?;")) {
			stmt.setInt(1, structureId);
			return stmt.executeUpdate() > 0;
		} catch (Exception e) {
			logger.log(Level.SEVERE, "Error deleting PDF template", e);
			return false;
		}
	}

	
	
	
	

	
	
	
	
	
	
	
	
	
	
	
	
	/**
	 * @brief Runs schema migrations/seeding once per application lifetime.
	 * @details Idempotent and thread-safe (double-checked locking). Individual ensure*Table methods
	 *          are themselves idempotent; this guard only avoids rerunning them on every request,
	 *          which caused unnecessary writes and lock contention on the SQLite DB.
	 */
	private void ensureSchemaInitialized() {
	    if (schemaInitialized) return;
	    synchronized (_schemaLock) {
	        if (schemaInitialized) return;
	        if (!legacySqliteBootstrap) {
	            schemaInitialized = true;
	            return;
	        }
	        ensureShiftColumns();
	        ensureSkillColumns();
	        ensureStructuresTable();
	        ensureAuditorDispatchColumns();
	        ensureInternalDispatchWorkflow();
	        ensureLanguagesTable();
	        ensureLabelsTable();
	        ensureLocalizzazioniTable();
	        ensureShiftTemplatesTable();
	        ensureEmailTemplatesTable();
	        ensurePdfTemplatesTable();
	        ensureSolverSettingsTable();
	        ensureGeneralSettingsTable();
	        ensureHomeUiSettingsTable();
	        ensureEmailLogTable();
	        ensureEmailSettingsTable();
        ensureSkillTypeTable();
        ensureSkillsStructureColumn();
        ensureAppUsersTable();
	        migrateShiftSkillsForeignKey();
	        ensurePerformanceIndexes();
	        validateRequiredSchema();
	        schemaInitialized = true;
	    }
	}

	/**
	 * Rebuilds the shift_skills bridge when an old installation has the shift_id FK pointing to
	 * shifts_backup (or has no FK at all). The copy keeps one row per association and discards
	 * references that no longer exist.
	 */
	private void migrateShiftSkillsForeignKey() {
		try (Connection connection = DatabaseConnection.connect(dbName)) {
			migrateShiftSkillsForeignKey(connection);
		} catch (Exception exception) {
			throw new IllegalStateException("Cannot migrate shift_skills foreign key", exception);
		}
	}

	/** Package-visible so migration semantics can be tested against disposable SQLite files. */
	static void migrateShiftSkillsForeignKey(Connection connection) throws SQLException {
		if (hasExpectedShiftSkillsForeignKeys(connection)) {
			validateShiftSkillsForeignKey(connection);
			return;
		}
		assertSupportedShiftSkillsMetadata(connection);
		long preservedSequence = 0;
		try (Statement statement = connection.createStatement();
				 ResultSet result = statement.executeQuery("SELECT COALESCE(MAX(id),0) FROM shift_skills")) {
			if (result.next()) preservedSequence = result.getLong(1);
		}
		try (PreparedStatement statement = connection.prepareStatement(
				"SELECT seq FROM sqlite_sequence WHERE name = ?")) {
			statement.setString(1, "shift_skills");
			try (ResultSet result = statement.executeQuery()) {
				if (result.next()) preservedSequence = Math.max(preservedSequence, result.getLong(1));
			}
		} catch (SQLException noSequenceTable) {
			// The rebuilt AUTOINCREMENT table creates sqlite_sequence if a very old DB lacked it.
		}

		// SQLite allows changing foreign_keys only outside a transaction.
		try (Statement statement = connection.createStatement()) {
			statement.execute("PRAGMA foreign_keys = OFF");
		}
		connection.setAutoCommit(false);
		try (Statement statement = connection.createStatement()) {
			statement.execute("DROP TABLE IF EXISTS shift_skills_orm_migration");
			statement.execute("CREATE TABLE shift_skills_orm_migration ("
					+ "id INTEGER PRIMARY KEY AUTOINCREMENT,"
					+ "shift_id INTEGER NOT NULL,"
					+ "skill_id INTEGER NOT NULL,"
					+ "skill_type_id INTEGER NOT NULL,"
					+ "UNIQUE(shift_id, skill_id, skill_type_id),"
					+ "FOREIGN KEY (shift_id) REFERENCES shifts(id) ON DELETE CASCADE,"
					+ "FOREIGN KEY (skill_id) REFERENCES skills(id) ON DELETE CASCADE,"
					+ "FOREIGN KEY (skill_type_id) REFERENCES skill_type(id) ON DELETE CASCADE"
					+ ")");
			statement.executeUpdate("INSERT INTO shift_skills_orm_migration "
					+ "(id, shift_id, skill_id, skill_type_id) "
					+ "SELECT MIN(ss.id), ss.shift_id, ss.skill_id, ss.skill_type_id "
					+ "FROM shift_skills ss "
					+ "JOIN shifts sh ON sh.id = ss.shift_id "
					+ "JOIN skills sk ON sk.id = ss.skill_id "
					+ "JOIN skill_type st ON st.id = ss.skill_type_id "
					+ "WHERE ss.skill_type_id IN (1,2) "
					+ "GROUP BY ss.shift_id, ss.skill_id, ss.skill_type_id");
			statement.execute("DROP TABLE shift_skills");
			statement.execute("ALTER TABLE shift_skills_orm_migration RENAME TO shift_skills");
			try (PreparedStatement update = connection.prepareStatement(
					"UPDATE sqlite_sequence SET seq = ? WHERE name = 'shift_skills'")) {
				update.setLong(1, preservedSequence);
				if (update.executeUpdate() == 0) {
					try (PreparedStatement insert = connection.prepareStatement(
							"INSERT INTO sqlite_sequence(name,seq) VALUES('shift_skills',?)")) {
						insert.setLong(1, preservedSequence);
						insert.executeUpdate();
					}
				}
			}
			connection.commit();
		} catch (Exception exception) {
			connection.rollback();
			if (exception instanceof SQLException sqlException) throw sqlException;
			throw new SQLException("Cannot rebuild shift_skills", exception);
		} finally {
			connection.setAutoCommit(true);
		}
		try (Statement statement = connection.createStatement()) {
			statement.execute("PRAGMA foreign_keys = ON");
		}
		validateShiftSkillsForeignKey(connection);
	}

	private static boolean hasExpectedShiftSkillsForeignKeys(Connection connection) throws SQLException {
		Map<String, String> expected = Map.of(
				"shift_id", "shifts", "skill_id", "skills", "skill_type_id", "skill_type");
		Set<String> found = new HashSet<>();
		int rows = 0;
		try (Statement statement = connection.createStatement();
				 ResultSet result = statement.executeQuery("PRAGMA foreign_key_list('shift_skills')")) {
			while (result.next()) {
				rows++;
				String from = result.getString("from").toLowerCase();
				String target = result.getString("table").toLowerCase();
				if (!expected.containsKey(from) || !expected.get(from).equals(target)
						|| !"id".equalsIgnoreCase(result.getString("to"))
						|| !"CASCADE".equalsIgnoreCase(result.getString("on_delete"))) return false;
				found.add(from);
			}
		}
		return rows == 3 && found.equals(expected.keySet());
	}

	private static void assertSupportedShiftSkillsMetadata(Connection connection) throws SQLException {
		try (Statement statement = connection.createStatement();
				 ResultSet result = statement.executeQuery("SELECT type,name,sql FROM sqlite_master "
						+ "WHERE tbl_name='shift_skills' AND type IN ('index','trigger')")) {
			while (result.next()) {
				String name = result.getString("name");
				String sql = result.getString("sql");
				if (sql == null || "idx_shift_skills_shift_type_skill".equalsIgnoreCase(name)) continue;
				throw new SQLException("Cannot rebuild shift_skills with unsupported "
						+ result.getString("type") + " " + name);
			}
		}
	}

	/** Fail fast instead of caching a partially completed legacy migration as successful. */
	private void validateRequiredSchema() {
		Map<String, Set<String>> requiredColumns = new LinkedHashMap<>();
		requiredColumns.put("structures", Set.of("id", "name", "address", "phone"));
		requiredColumns.put("employees", Set.of("id", "code", "first_name", "last_name", "structure_id", "active", "email"));
		requiredColumns.put("employee_dates", Set.of("id", "employee_id", "date_start", "date_end", "date_type_id"));
		requiredColumns.put("employee_skills", Set.of("id", "employee_id", "skill_id"));
		requiredColumns.put("locations", Set.of("id", "name", "l_order", "code", "structure_id", "active"));
		requiredColumns.put("location_skills", Set.of("id", "location_id", "skill_id", "skill_type_id"));
		requiredColumns.put("shifts", Set.of("id", "location_id", "start_time", "end_time", "employee_id", "pinned"));
		requiredColumns.put("audit_orders", Set.of("id", "structure_id", "order_code", "status", "version"));
		requiredColumns.put("audit_order_phases", Set.of("id", "audit_order_id", "phase_code", "start_time", "end_time", "location_id"));
		requiredColumns.put("audit_phase_skills", Set.of("id", "phase_id", "skill_id", "minimum_count"));
		requiredColumns.put("schedule_plans", Set.of("id", "structure_id", "status", "revision", "version"));
		requiredColumns.put("schedule_plan_assignments", Set.of("id", "plan_id", "shift_id", "shift_version"));
		requiredColumns.put("import_jobs", Set.of("id", "token", "structure_id", "status"));
		requiredColumns.put("audit_events", Set.of("id", "actor", "action", "entity_type", "created_at"));
		requiredColumns.put("shift_skills", Set.of("id", "shift_id", "skill_id", "skill_type_id"));
		requiredColumns.put("skill_type", Set.of("id"));
		requiredColumns.put("skills", Set.of("id", "name", "skill_order", "active"));
		requiredColumns.put("languages", Set.of("id", "code", "description", "active"));
		requiredColumns.put("labels", Set.of("id", "key", "description"));
		requiredColumns.put("localizzazioni", Set.of("id", "entity_type", "entity_id", "field_name", "language_id", "value"));
		requiredColumns.put("shift_templates", Set.of("id", "structure_id", "day_of_week", "start_time", "end_time", "location_id", "header_id"));
		requiredColumns.put("shift_template_headers", Set.of("id", "structure_id", "description", "created_at"));
		requiredColumns.put("shift_template_skills", Set.of("id", "template_id", "skill_id", "skill_type_id"));
		requiredColumns.put("general_settings", Set.of("id", "structure_id", "shift_window_mode", "auto_populate_from_template"));
		requiredColumns.put("home_ui_settings", Set.of("id", "cover_key", "title_key", "body_key", "hint_key"));
		requiredColumns.put("email_templates", Set.of("id", "structure_id", "subject", "body"));
		requiredColumns.put("pdf_templates", Set.of("id", "structure_id", "header_text", "footer_text", "logo_data_url", "primary_color"));
		requiredColumns.put("email_settings", Set.of("id", "host", "port", "start_tls", "username", "password", "mail_from"));
		requiredColumns.put("email_log", Set.of("id", "structure_id", "employee_id", "period_slug", "period_label",
				"sent_to", "filename", "sent_at"));
		requiredColumns.put("solver_settings", Set.of("id", "structure_id", "max_solve_seconds", "unimproved_seconds",
				"minimum_rest_hours", "max_shifts_per_day", "desired_date_weight", "undesired_date_weight",
				"balance_weight", "optional_skill_weight", "balance_by_hours", "max_weekly_hours",
				"min_weekly_shifts", "max_weekly_shifts", "max_consecutive_days", "min_days_off_per_week",
				"allow_unassigned", "unassigned_weight", "same_location_weight", "night_balance_weight",
				"night_start_hour", "night_end_hour", "stop_when_feasible",
				"context_days", "diminished_window_seconds", "diminished_ratio_pct", "weekly_shift_weight",
				"days_off_weight", "consecutive_days_weight"));
		try (Connection connection = DatabaseConnection.connect(dbName)) {
			for (String table : requiredColumns.keySet()) {
				try (PreparedStatement statement = connection.prepareStatement(
						"SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name=?")) {
					statement.setString(1, table);
					try (ResultSet result = statement.executeQuery()) {
						if (!result.next() || result.getInt(1) != 1)
							throw new IllegalStateException("Required table missing after migration: " + table);
					}
				}
			}
			for (Map.Entry<String, Set<String>> requirement : requiredColumns.entrySet()) {
				Set<String> present = new HashSet<>();
				try (Statement statement = connection.createStatement();
					 ResultSet result = statement.executeQuery("PRAGMA table_info(" + requirement.getKey() + ")")) {
					while (result.next()) present.add(result.getString("name"));
				}
				if (!present.containsAll(requirement.getValue())) {
					Set<String> missing = new HashSet<>(requirement.getValue());
					missing.removeAll(present);
					throw new IllegalStateException("Required columns missing after migration: "
							+ requirement.getKey() + "." + missing);
				}
			}
			Set<String> skillTypeColumns = new HashSet<>();
			try (Statement statement = connection.createStatement();
					 ResultSet result = statement.executeQuery("PRAGMA table_info('skill_type')")) {
				while (result.next()) skillTypeColumns.add(result.getString("name").toLowerCase());
			}
			if (!skillTypeColumns.contains("description") && !skillTypeColumns.contains("name"))
				throw new IllegalStateException("Required label column missing: skill_type.description/name");
			validateShiftSkillsForeignKey(connection);
			validateGlobalForeignKeys(connection);
		} catch (Exception exception) {
			throw new IllegalStateException("Cannot validate migrated database schema", exception);
		}
	}

	private static void validateShiftSkillsForeignKey(Connection connection) throws SQLException {
		if (!hasExpectedShiftSkillsForeignKeys(connection))
			throw new IllegalStateException("shift_skills does not have the complete CASCADE foreign-key set");
		try (Statement statement = connection.createStatement();
				 ResultSet result = statement.executeQuery("PRAGMA foreign_key_list('shift_skills')")) {
			while (result.next()) {
				if ("shifts_backup".equalsIgnoreCase(result.getString("table")))
					throw new IllegalStateException("shift_skills still references shifts_backup");
			}
		}
		try (Statement statement = connection.createStatement();
				 ResultSet result = statement.executeQuery("PRAGMA foreign_key_check('shift_skills')")) {
			if (result.next()) throw new IllegalStateException("shift_skills contains invalid foreign-key references");
		}
	}

	/** Global, read-only FK audit. It reports legacy violations and never repairs/deletes rows. */
	static void validateGlobalForeignKeys(Connection connection) throws SQLException {
		List<String> violations = new ArrayList<>();
		try (Statement statement = connection.createStatement();
				 ResultSet result = statement.executeQuery("PRAGMA foreign_key_check")) {
			while (result.next()) {
				violations.add(result.getString("table") + "[rowid=" + result.getString("rowid")
						+ "] -> " + result.getString("parent") + " (fk=" + result.getString("fkid") + ")");
				if (violations.size() >= 20) break;
			}
		}
		if (!violations.isEmpty())
			logger.warning("Legacy foreign-key violations (left untouched): " + String.join(", ", violations));
	}

	/**
	 * @brief Generates a complete demo schedule by loading employees, locations, and shifts.
	 * @details Assembles an EmployeeSchedule from all employees, locations, and shifts
	 *          stored in the database, ready to be submitted to the solver.
	 * @return a fully populated EmployeeSchedule object
	 */
	public SolverSettings getSolverSettingsOrm(int structureId) {
		SolverSettingsEntity entity = SolverSettingsEntity.find("structureId", structureId).firstResult();
		return entity != null ? entity.toDto()
				: new SolverSettings(0, structureId, 30, 0, 10, 1, 1, 1, 1, 1);
	}

	/**
	 * @brief Writes a data translation (skill, location, ...) without first checking whether it exists.
	 *
	 * @details This used to be "find, and insert if missing": two nearly simultaneous saves of the
	 *          same row both found nothing, both inserted, and the second violated the unique index.
	 *          The damage was not a lost translation but the rollback: the write lives inside a
	 *          larger transaction, and the user's entire save was canceled with an incomprehensible
	 *          500 error.
	 *
	 *          The unique constraint also includes {@code field_name}, so it must be filtered:
	 *          otherwise, once an entity has a second translated field, the update would affect
	 *          the wrong row.
	 *
	 *          {@code ON CONFLICT} is understood by both engines.
	 */
	@Transactional
	public void upsertDataTranslation(String entityType, int entityId, String fieldName,
			int languageId, String value) {
		executeNativeUpdate("INSERT INTO localizzazioni (entity_type,entity_id,field_name,language_id,value) "
				+ "VALUES (?1,?2,?3,?4,?5) "
				+ "ON CONFLICT(entity_type,entity_id,field_name,language_id) DO UPDATE SET value=excluded.value",
				entityType, entityId, fieldName, languageId, value);
	}

	private int executeNativeUpdate(String sql, Object... parameters) {
		var query = em.createNativeQuery(sql);
		for (int index = 0; index < parameters.length; index++) query.setParameter(index + 1, parameters[index]);
		return query.executeUpdate();
	}

	@Transactional
	public boolean saveSolverSettingsOrm(int structureId, SolverSettings settings) {
		String columns = "max_solve_seconds,unimproved_seconds,minimum_rest_hours,max_shifts_per_day,"
				+ "desired_date_weight,undesired_date_weight,balance_weight,optional_skill_weight,balance_by_hours,"
				+ "max_weekly_hours,min_weekly_shifts,max_weekly_shifts,max_consecutive_days,min_days_off_per_week,"
				+ "allow_unassigned,unassigned_weight,same_location_weight,night_balance_weight,night_start_hour,"
				+ "night_end_hour,stop_when_feasible,context_days,diminished_window_seconds,"
				+ "diminished_ratio_pct,weekly_shift_weight,days_off_weight,consecutive_days_weight";
		String updates = java.util.Arrays.stream(columns.split(","))
				.map(column -> column + "=excluded." + column).collect(Collectors.joining(","));
		return executeNativeUpdate("INSERT INTO solver_settings (structure_id," + columns + ") VALUES "
				+ "(?1,?2,?3,?4,?5,?6,?7,?8,?9,?10,?11,?12,?13,?14,?15,?16,?17,?18,?19,?20,"
				+ "?21,?22,?23,?24,?25,?26,?27,?28) ON CONFLICT(structure_id) DO UPDATE SET " + updates,
				structureId, settings.getMaxSolveSeconds(), settings.getUnimprovedSeconds(),
				settings.getMinimumRestHours(), settings.getMaxShiftsPerDay(), settings.getDesiredDateWeight(),
				settings.getUndesiredDateWeight(), settings.getBalanceWeight(), settings.getOptionalSkillWeight(),
				settings.isBalanceByHours() ? 1 : 0, settings.getMaxWeeklyHours(), settings.getMinWeeklyShifts(),
				settings.getMaxWeeklyShifts(), settings.getMaxConsecutiveDays(), settings.getMinDaysOffPerWeek(),
				settings.isAllowUnassigned() ? 1 : 0, settings.getUnassignedWeight(), settings.getSameLocationWeight(),
				settings.getNightBalanceWeight(), settings.getNightStartHour(), settings.getNightEndHour(),
				settings.isStopWhenFeasible() ? 1 : 0, settings.getContextDays(),
				settings.getDiminishedWindowSeconds(), settings.getDiminishedRatioPct(), settings.getWeeklyShiftWeight(),
				settings.getDaysOffWeight(), settings.getConsecutiveDaysWeight()) > 0;
	}

	public GeneralSettings getGeneralSettingsOrm(int structureId) {
		GeneralSettingsEntity entity = GeneralSettingsEntity.find("structureId", structureId).firstResult();
		return entity == null ? new GeneralSettings(0, structureId, "month", false)
				: new GeneralSettings(entity.id, structureId, entity.shiftWindowMode, entity.autoPopulateFromTemplate);
	}

	public HomeUiSettings getHomeUiSettingsOrm() {
		HomeUiSettingsEntity entity = HomeUiSettingsEntity.findById(1);
		return entity == null ? new HomeUiSettings(1, "", "", "home.title", "home.body", "home.hint")
				: new HomeUiSettings(entity.id, entity.coverKey, entity.coverDataUrl, entity.titleKey, entity.bodyKey, entity.hintKey);
	}

	@Transactional
	public boolean saveHomeUiSettingsOrm(HomeUiSettings settings) {
		return executeNativeUpdate("INSERT INTO home_ui_settings "
				+ "(id, cover_key, cover_data_url, title_key, body_key, hint_key) VALUES (1,?1,?2,?3,?4,?5) "
				+ "ON CONFLICT(id) DO UPDATE SET cover_key=excluded.cover_key,cover_data_url=excluded.cover_data_url,"
				+ "title_key=excluded.title_key,body_key=excluded.body_key,hint_key=excluded.hint_key",
				settings.getCoverKey() != null ? settings.getCoverKey() : "",
				settings.getCoverDataUrl() != null ? settings.getCoverDataUrl() : "",
				settings.getTitleKey() != null ? settings.getTitleKey() : "home.title",
				settings.getBodyKey() != null ? settings.getBodyKey() : "home.body",
				settings.getHintKey() != null ? settings.getHintKey() : "home.hint") > 0;
	}

	@Transactional
	public boolean saveGeneralSettingsOrm(int structureId, GeneralSettings settings) {
		return executeNativeUpdate("INSERT INTO general_settings "
				+ "(structure_id,shift_window_mode,auto_populate_from_template) VALUES (?1,?2,?3) "
				+ "ON CONFLICT(structure_id) DO UPDATE SET shift_window_mode=excluded.shift_window_mode,"
				+ "auto_populate_from_template=excluded.auto_populate_from_template",
				structureId, settings.getShiftWindowMode(), settings.isAutoPopulateFromTemplate() ? 1 : 0) > 0;
	}

	public PdfTemplate getPdfTemplateOrm(int structureId) {
		PdfTemplateEntity entity = PdfTemplateEntity.find("structureId", structureId).firstResult();
		return entity == null ? new PdfTemplate(0, structureId, "", "", "", "#2980B9")
				: new PdfTemplate(entity.id, structureId, entity.headerText, entity.footerText,
						entity.logoDataUrl, entity.primaryColor);
	}

	@Transactional
	public boolean savePdfTemplateOrm(int structureId, String header, String footer, String logo, String color) {
		return executeNativeUpdate("INSERT INTO pdf_templates "
				+ "(structure_id,header_text,footer_text,logo_data_url,primary_color) VALUES (?1,?2,?3,?4,?5) "
				+ "ON CONFLICT(structure_id) DO UPDATE SET header_text=excluded.header_text,"
				+ "footer_text=excluded.footer_text,logo_data_url=excluded.logo_data_url,primary_color=excluded.primary_color",
				structureId, header != null ? header : "", footer != null ? footer : "",
				logo != null ? logo : "", color != null ? color : "#2980B9") > 0;
	}

	@Transactional
	public boolean deletePdfTemplateOrm(int structureId) {
		return PdfTemplateEntity.delete("structureId", structureId) > 0;
	}

	public EmailTemplate getEmailTemplateOrm(int structureId) {
		EmailTemplateEntity entity = EmailTemplateEntity.find("structureId", structureId).firstResult();
		return entity == null ? new EmailTemplate(0, structureId, "", "")
				: new EmailTemplate(entity.id, structureId, entity.subject, entity.body);
	}

	@Transactional
	public boolean saveEmailTemplateOrm(int structureId, String subject, String body) {
		return executeNativeUpdate("INSERT INTO email_templates (structure_id,subject,body) VALUES (?1,?2,?3) "
				+ "ON CONFLICT(structure_id) DO UPDATE SET subject=excluded.subject,body=excluded.body",
				structureId, subject != null ? subject : "", body != null ? body : "") > 0;
	}

	public EmailSettings getEmailSettingsOrm() {
		EmailSettingsEntity entity = EmailSettingsEntity.findById(1);
		if (entity == null) return null;
		EmailSettings settings = new EmailSettings();
		settings.setHost(entity.host); settings.setPort(entity.port); settings.setStartTls(entity.startTls);
		settings.setUsername(entity.username); settings.setPassword(entity.password); settings.setMailFrom(entity.mailFrom);
		return settings;
	}

	@Transactional
	public boolean saveEmailSettingsOrm(EmailSettings settings) {
		return executeNativeUpdate("INSERT INTO email_settings "
				+ "(id,host,port,start_tls,username,password,mail_from) VALUES (1,?1,?2,?3,?4,?5,?6) "
				+ "ON CONFLICT(id) DO UPDATE SET host=excluded.host,port=excluded.port,start_tls=excluded.start_tls,"
				+ "username=excluded.username,password=CASE WHEN excluded.password='' THEN email_settings.password "
				+ "ELSE excluded.password END,mail_from=excluded.mail_from",
				settings.getHost() != null ? settings.getHost().trim() : "",
				settings.getPort() > 0 ? settings.getPort() : 587, settings.isStartTls() ? 1 : 0,
				settings.getUsername() != null ? settings.getUsername().trim() : "",
				settings.getPassword() != null ? settings.getPassword() : "",
				settings.getMailFrom() != null ? settings.getMailFrom().trim() : "") > 0;
	}

	public boolean employeeBelongsToStructureOrm(int employeeId, int structureId) {
		return EmployeeEntity.count("id = ?1 and structureId = ?2", employeeId, structureId) > 0;
	}

	public Employee findEmployeeByIdOrm(int employeeId) {
		EmployeeEntity entity = EmployeeEntity.findById(employeeId);
		if (entity == null) return null;
		return loadEmployeesOrm(entity.structureId).stream()
				.filter(employee -> employee.getId() == employeeId).findFirst().orElse(null);
	}

	public List<EmailLogEntry> getEmailLogOrm(int structureId, String periodSlug) {
		return EmailLogEntity.<EmailLogEntity>list("structureId = ?1 and periodSlug = ?2",
				structureId, periodSlug != null ? periodSlug : "").stream()
				.map(entity -> new EmailLogEntry(entity.employeeId, entity.sentAt, entity.sentTo)).toList();
	}

	/**
	 * @brief Records an email-delivery audit while revalidating the employee in the same transaction.
	 * @details The entire SMTP delivery occurs between reading the employee and this write: during
	 *          that window the employee may have been deleted or moved to another structure. The FK
	 *          would catch deletion, but not a move: the audit would be written under the original
	 *          structure, violating per-structure ownership. The check lives here, not in the caller,
	 *          because only here can it share the INSERT transaction and thus avoid the same race.
	 * @return true if the audit was written, false if the employee no longer belongs to the structure.
	 */
	@Transactional
	public boolean logEmailSentOrm(int structureId, int employeeId, String periodSlug, String periodLabel,
			String sentTo, String filename) {
		if (!employeeBelongsToStructureOrm(employeeId, structureId)) return false;
		String slug = periodSlug != null ? periodSlug : "";
		em.createNativeQuery("INSERT INTO email_log "
				+ "(structure_id,employee_id,period_slug,period_label,sent_to,filename,sent_at) "
				+ "VALUES (?1,?2,?3,?4,?5,?6,?7) ON CONFLICT(structure_id,employee_id,period_slug) "
				+ "DO UPDATE SET period_label=excluded.period_label,sent_to=excluded.sent_to,"
				+ "filename=excluded.filename,sent_at=excluded.sent_at")
				.setParameter(1, structureId).setParameter(2, employeeId).setParameter(3, slug)
				.setParameter(4, periodLabel != null ? periodLabel : "")
				.setParameter(5, sentTo != null ? sentTo : "")
				.setParameter(6, filename != null ? filename : "")
				.setParameter(7, LocalDateTime.now().format(dbFormatter)).executeUpdate();
		return true;
	}

	public List<Language> getLanguagesOrm() {
		return LanguageEntity.<LanguageEntity>list("order by id").stream().map(LanguageEntity::toDto).toList();
	}

	@Transactional
	public int addLanguageOrm(Language language) {
		LanguageEntity entity = new LanguageEntity(); entity.code = language.getCode();
		entity.description = language.getDescription(); entity.active = false; entity.persist();
		invalidateTranslationsAfterCommit(); return entity.id;
	}

	@Transactional
	public int updateLanguageOrm(int id, Language language) {
		LanguageEntity entity = LanguageEntity.findById(id); if (entity == null) return 0;
		entity.code = language.getCode(); entity.description = language.getDescription();
		invalidateTranslationsAfterCommit(); return 1;
	}

	@Transactional
	public boolean deleteLanguageByIdOrm(int id) {
		LanguageEntity entity = LanguageEntity.findById(id);
		if (entity == null || entity.active) return false;
		LocalizzazioneEntity.delete("languageId", id);
		entity.delete(); invalidateTranslationsAfterCommit(); return true;
	}

	@Transactional
	public boolean setActiveLanguageOrm(int id) {
		if (LanguageEntity.findById(id) == null) return false;
		for (LanguageEntity entity : LanguageEntity.<LanguageEntity>listAll()) entity.active = entity.id == id;
		invalidateTranslationsAfterCommit();
		return true;
	}

	public EmployeeSchedule generateDemoData(int structureId) {
	    return generateDemoData(structureId, null, null);
	}

	/**
	 * @brief Like generateDemoData(int), but filters shifts to [windowStart, windowEnd).
	 * @details Only SHIFTS are filtered: employees and locations remain complete (the solver must
	 *          be able to assign any employee and see every location). With both bounds null,
	 *          behavior is identical to a complete load (backward compatible for the Locations,
	 *          Employees, and Report pages, which expect every shift).
	 */
	public EmployeeSchedule generateDemoData(int structureId, LocalDateTime windowStart, LocalDateTime windowEnd) {
	    return generateDemoData(structureId, windowStart, windowEnd, false);
	}

	/**
	 * @brief As above, with optional filtering of active items.
	 * @param activeOnly if true, excludes disabled employees and locations (active=0).
	 *        Used by Shift Management and the solver; management pages and Report continue to
	 *        receive everything (disabled items must remain editable and historical reports complete).
	 */
	public EmployeeSchedule generateDemoData(int structureId, LocalDateTime windowStart, LocalDateTime windowEnd, boolean activeOnly) {
	    return generateDemoData(structureId, windowStart, windowEnd, activeOnly, false);
	}

	/**
	 * @brief As above, with optional context loading at window boundaries.
	 * @param includeContext if true (solver payload only), also loads ALREADY ASSIGNED shifts from
	 *        context_days before/after the window as pinned shifts marked context=true: the solver
	 *        does not change or save them, but boundary constraints (overlap, minimum rest, weekly
	 *        hours, consecutive days) see them. Without context, every solve is blind beyond the
	 *        boundary: a night shift on the preceding Sunday would not prevent a Monday morning shift.
	 */
	public EmployeeSchedule generateDemoData(int structureId, LocalDateTime windowStart, LocalDateTime windowEnd, boolean activeOnly, boolean includeContext) {
	    return generateDemoData(structureId, windowStart, windowEnd, activeOnly, includeContext, false);
	}

	/**
	 * @brief As above, optionally restricted to orders that have been approved.
	 * @param approvedOnly if true, drops every shift whose order is not APPROVED. Shifts with no
	 *        order code at all are kept: they are historic/manual rows that never belonged to the
	 *        approval chain, and silently removing them would hide work the planner still has to do.
	 *        Scheduling is driven by {@code app.solver.require-approved-orders} so the behaviour can
	 *        be switched on without rebuilding.
	 */
	public EmployeeSchedule generateDemoData(int structureId, LocalDateTime windowStart, LocalDateTime windowEnd,
	        boolean activeOnly, boolean includeContext, boolean approvedOnly) {

	    ensureSchemaInitialized();

	    EmployeeSchedule employeeSchedule = new EmployeeSchedule();

	    List<Employee> employees = getEmployees(structureId);
	    List<Location> locations = getLocations(structureId);
	    List<Shift> shifts = getShifts(structureId, windowStart, windowEnd);
	    if (activeOnly) {
	        employees = employees.stream().filter(Employee::isActive).collect(Collectors.toList());
	        locations = locations.stream().filter(Location::isActive).collect(Collectors.toList());
	        // SHIFTS at disabled locations must also be excluded, not only the locations:
	        // otherwise the solver must assign them (unassignedHard), consuming employees for
	        // locations the user disabled, while the UI does not even show them because it renders
	        // rows from active locations only.
        Set<Integer> activeLocationIds = locations.stream().map(Location::getId).collect(Collectors.toSet());
        shifts = shifts.stream().filter(s -> activeLocationIds.contains(s.getLocation_id())).collect(Collectors.toList());
	    }
	    if (approvedOnly) {
	        // Only an approved order may be staffed. Rows without an order code are historic/manual
	        // and carry no approval state, so they stay — excluding them would silently drop work.
	        Set<String> approvedCodes = AuditOrderEntity.<AuditOrderEntity>find(
	                "structureId = ?1 and status = 'APPROVED'", structureId).list().stream()
	                .map(order -> order.orderCode).collect(Collectors.toSet());
	        shifts = shifts.stream().filter(shift -> {
	            String code = shift.getAuditOrderCode();
	            return code == null || code.isBlank() || approvedCodes.contains(code);
	        }).collect(Collectors.toList());
	    }

	    Map<Integer, Location> locationById = locations.stream()
	            .collect(Collectors.toMap(Location::getId, Function.identity()));
	    for (Employee employee : employees) {
	        Location current = employee.getCurrentLocationId() == null
	                ? null : locationById.get(employee.getCurrentLocationId());
	        if (current != null) {
	            employee.setCurrentLatitude(current.getLatitude());
	            employee.setCurrentLongitude(current.getLongitude());
	        }
	    }

	    // Hydrate employee references from persisted employee_id
	    Map<Integer, Employee> empMap = employees.stream()
	        .collect(Collectors.toMap(e -> e.getId(), e -> e));
	    shifts.forEach(s -> {
	        if (s.getEmployeeId() != null)
	            s.setEmployee(empMap.get(s.getEmployeeId()));
	        // A pinned shift (@PlanningPin) without a resolvable employee — because the employee was
	        // disabled or employee_id is null — could NEVER be assigned: a permanent, inexplicable
	        // -1 hard score. It must be unpinned.
	        if (s.isPinned() && s.getEmployee() == null) {
	            logger.warning("Shift " + s.getId() + " pinned senza dipendente risolvibile"
	                + (s.getEmployeeId() != null ? " (employee_id=" + s.getEmployeeId() + " non attivo)" : "")
	                + ": pin rimosso per il solve.");
	            s.setPinned(false);
	        }
	    });

	    SolverSettings settings = getSolverSettingsOrm(structureId);

	    // Context shifts pinned at window boundaries (see method documentation).
	    if (includeContext && windowStart != null && windowEnd != null && settings.getContextDays() > 0) {
	        List<Shift> context = new ArrayList<>();
	        context.addAll(getShifts(structureId, windowStart.minusDays(settings.getContextDays()), windowStart));
	        context.addAll(getShifts(structureId, windowEnd, windowEnd.plusDays(settings.getContextDays())));
	        for (Shift s : context) {
	            // Count only a shift assigned to an employee present in the value range: an
	            // unassigned shift constrains nothing, while one belonging to a disabled employee
	            // concerns someone the solver does not schedule anyway.
	            Employee owner = s.getEmployeeId() != null ? empMap.get(s.getEmployeeId()) : null;
	            if (owner == null) continue;
	            s.setEmployee(owner);
	            s.setPinned(true);
	            s.setContext(true);
	            shifts.add(s);
	        }
	    }

		    employeeSchedule.setEmployees(employees);
	    employeeSchedule.setLocations(locations);
	    employeeSchedule.setShifts(shifts);
	    employeeSchedule.setSolverSettings(settings);


	    return employeeSchedule;

	}

/**
	 * @brief Retrieves the number of days in the scheduling period from configuration.
	 * @return the number of days in the schedule, or 0 on error
	 */
	public int getDaysInSchedule() {
		String query = "SELECT days_in_schedule FROM demo_data_parameters LIMIT 1;";
		try (Connection conn = DatabaseConnection.connect(dbName);
				PreparedStatement stmt = conn.prepareStatement(query);
				ResultSet rs = stmt.executeQuery()) {
			if (rs.next()) {
				return rs.getInt("days_in_schedule");
			}
		} catch (Exception e) {
			logger.log(Level.SEVERE, "Error while fetching days in schedule", e);
		}
		return 0;
	}


	/**
	 * @brief Persists solver assignments for a list of shifts, marking them as pinned.
	 * @param shifts the list of solved shifts to save
	 */
	public void saveWeekAssignments(List<Shift> shifts) {
		String sql = "UPDATE shifts SET employee_id = ?, pinned = 1 WHERE id = ?";
		try (Connection conn = DatabaseConnection.connect(dbName);
			 PreparedStatement stmt = conn.prepareStatement(sql)) {
			for (Shift shift : shifts) {
				// Context shifts (outside the window, pinned only for boundary constraints) must NOT
				// be persisted: they are rows from adjacent windows, and rewriting employee_id/pinned
				// would corrupt those assignments.
				if (shift.isContext()) continue;
				Integer empId = (shift.getEmployee() != null) ? shift.getEmployee().getId() : null;
				if (empId != null) {
					stmt.setInt(1, empId);
				} else {
					stmt.setNull(1, Types.INTEGER);
				}
				stmt.setInt(2, shift.getId());
				stmt.addBatch();
			}
			stmt.executeBatch();
		} catch (Exception e) {
			logger.log(Level.SEVERE, "Error while saving week assignments", e);
		}
	}


	/**
	 * @brief Clears pinning for all shifts within the given date range.
	 * @param start inclusive start datetime string (yyyy-MM-dd HH:mm:ss)
	 * @param end exclusive end datetime string (yyyy-MM-dd HH:mm:ss)
	 */
	public void unpinWeek(String start, String end, int structureId) {
		String sql = "UPDATE shifts SET employee_id = NULL, pinned = 0 " +
		             "WHERE start_time >= ? AND start_time < ? " +
		             "AND location_id IN (SELECT id FROM locations WHERE structure_id = ?)";
		try (Connection conn = DatabaseConnection.connect(dbName);
			 PreparedStatement stmt = conn.prepareStatement(sql)) {
			stmt.setString(1, start);
			stmt.setString(2, end);
			stmt.setInt(3, structureId);
			stmt.executeUpdate();
		} catch (Exception e) {
			logger.log(Level.SEVERE, "Error while unpinning week", e);
		}
	}

	@Transactional
	public boolean saveWeekAssignmentsOrm(List<Shift> shifts, int structureId) {
		if (shifts == null || structureId <= 0) return false;
		Set<Integer> shiftIds = shifts.stream().filter(dto -> !dto.isContext()).map(Shift::getId)
				.collect(Collectors.toSet());
		Map<Integer, ShiftEntity> entities = loadInChunks(shiftIds,
				chunk -> ShiftEntity.<ShiftEntity>list("id in ?1", chunk)).stream()
				.collect(Collectors.toMap(entity -> entity.id, entity -> entity));
		Set<Integer> locationIds = entities.values().stream().map(entity -> entity.locationId).collect(Collectors.toSet());
		Map<Integer, LocationEntity> locations = loadInChunks(locationIds,
				chunk -> LocationEntity.<LocationEntity>list("id in ?1", chunk)).stream()
				.collect(Collectors.toMap(location -> location.id, location -> location));
		Set<Integer> employeeIds = shifts.stream().filter(dto -> !dto.isContext() && dto.getEmployee() != null)
				.map(dto -> dto.getEmployee().getId()).collect(Collectors.toSet());
		Map<Integer, EmployeeEntity> employees = loadInChunks(employeeIds,
				chunk -> EmployeeEntity.<EmployeeEntity>list("id in ?1", chunk)).stream()
				.collect(Collectors.toMap(employee -> employee.id, employee -> employee));
		for (Shift dto : shifts) {
			if (dto.isContext()) continue;
			ShiftEntity entity = entities.get(dto.getId());
			if (entity == null) return false;
			LocationEntity location = locations.get(entity.locationId);
			if (location == null || location.structureId != structureId || !location.active) return false;
			EmployeeEntity employee = dto.getEmployee() != null ? employees.get(dto.getEmployee().getId()) : null;
			if (dto.getEmployee() != null
					&& (employee == null || employee.structureId != structureId || !employee.active)) return false;
		}
		for (Shift dto : shifts) {
			if (dto.isContext()) continue;
			ShiftEntity entity = entities.get(dto.getId());
			entity.employeeId = dto.getEmployee() != null ? dto.getEmployee().getId() : null;
			entity.pinned = true;
		}
		return true;
	}

	@Transactional
	public void unpinWeekOrm(String start, String end, int structureId) {
		if (start == null || start.isBlank() || end == null || end.isBlank() || start.compareTo(end) >= 0) return;
		for (ShiftEntity shift : em.createQuery(
				"select sh from ShiftEntity sh, LocationEntity l "
				+ "where l.id = sh.locationId and l.structureId = ?1 "
				+ "and sh.startTime >= ?2 and sh.startTime < ?3", ShiftEntity.class)
				.setParameter(1, structureId).setParameter(2, start).setParameter(3, end)
				.getResultList()) {
			shift.employeeId = null;
			shift.pinned = false;
		}
	}


	// -----------------------------------------------------------------------
	// Translation cache methods
	// -----------------------------------------------------------------------
	public Map<String, Map<String, String>> getAllTranslationsOrm() {
		// Compatibility with existing/clean installations: DDL migrations and seeds remain
		// centralized here until a migration tool is introduced.
		ensureSchemaInitialized();
		synchronized (_cacheLock) {
			if (_transCache != null) return _transCache;
			List<LanguageEntity> languages = LanguageEntity.list("order by id");
			List<LabelEntity> labels = LabelEntity.list("order by labelKey");
			Map<Integer, String> languageCodes = languages.stream()
					.collect(Collectors.toMap(language -> language.id, language -> language.code));
			Map<Integer, String> labelKeys = labels.stream()
					.collect(Collectors.toMap(label -> label.id, label -> label.labelKey));
			Map<String, Map<String, String>> result = new LinkedHashMap<>();
			for (LanguageEntity language : languages) {
				Map<String, String> values = new LinkedHashMap<>();
				for (LabelEntity label : labels) values.put(label.labelKey, "");
				result.put(language.code, values);
			}
			for (LocalizzazioneEntity translation : LocalizzazioneEntity.<LocalizzazioneEntity>listAll()) {
				String code = languageCodes.get(translation.languageId);
				if (code == null) continue;
				if ("labels".equals(translation.entityType) && "value".equals(translation.fieldName)) {
					String key = labelKeys.get(translation.entityId);
					if (key != null) result.get(code).put(key, translation.value);
				} else if ("name".equals(translation.fieldName) && translation.value != null
						&& !translation.value.isBlank()) {
					String prefix = "skills".equals(translation.entityType) ? "skill"
							: "locations".equals(translation.entityType) ? "location" : null;
					if (prefix != null) result.get(code).put(prefix + "." + translation.entityId, translation.value);
				}
			}
			_transCache = result;
			return result;
		}
	}

	/**
	 * @brief Returns all translations as a nested map: langCode → (labelKey → value).
	 *        Result is cached in-memory until invalidated.
	 */
	public Map<String, Map<String, String>> getAllTranslations() {
		// Label/translation seeding must precede cache construction: /translations is typically
		// the FIRST request after deployment (the frontend calls it at startup), and without this
		// guarantee new keys would remain outside the cache until restart or a label change.
		ensureSchemaInitialized();
		synchronized (_cacheLock) {
			if (_transCache != null) return _transCache;
			_transCache = buildTranslationsMap();
			return _transCache;
		}
	}

	private Map<String, Map<String, String>> buildTranslationsMap() {
		Map<String, Map<String, String>> result = new LinkedHashMap<>();
		String sql =
			"SELECT l.key, lang.code, COALESCE(loc.value, '') AS val " +
			"FROM labels l " +
			"CROSS JOIN languages lang " +
			"LEFT JOIN localizzazioni loc " +
			"       ON loc.entity_type = 'labels' " +
			"      AND loc.entity_id   = l.id " +
			"      AND loc.field_name  = 'value' " +
			"      AND loc.language_id = lang.id " +
			"ORDER BY lang.code, l.key;";
		try (Connection conn = DatabaseConnection.connect(dbName);
			 PreparedStatement stmt = conn.prepareStatement(sql);
			 ResultSet rs = stmt.executeQuery()) {
			while (rs.next()) {
				String langCode = rs.getString("code");
				String labelKey = rs.getString("key");
				String value    = rs.getString("val");
				result.computeIfAbsent(langCode, k -> new LinkedHashMap<>()).put(labelKey, value);
			}
		} catch (Exception e) {
			logger.log(Level.SEVERE, "Error building translations map", e);
		}
		// Localizable data names as dynamic keys: "skill.<id>", "location.<id>".
		// The frontend uses them with t(prefix+'.'+id, baseName): if the translation is missing
		// (absent or empty row), i18next falls back to the base name stored in the table.
		addDynamicNameTranslations(result, "skills", "skill");
		addDynamicNameTranslations(result, "locations", "location");
		return result;
	}

	/**
	 * @brief Injects an entity's 'name' field translations into the /translations map as dynamic
	 *        "prefix.<id>" keys for every language.
	 */
	private void addDynamicNameTranslations(Map<String, Map<String, String>> result,
			String entityType, String keyPrefix) {
		String sql =
			"SELECT loc.entity_id AS eid, lang.code, loc.value " +
			"FROM localizzazioni loc JOIN languages lang ON lang.id = loc.language_id " +
			"WHERE loc.entity_type = ? AND loc.field_name = 'name';";
		try (Connection conn = DatabaseConnection.connect(dbName);
			 PreparedStatement stmt = conn.prepareStatement(sql)) {
			stmt.setString(1, entityType);
			try (ResultSet rs = stmt.executeQuery()) {
				while (rs.next()) {
					String value = rs.getString("value");
					if (value == null || value.isBlank()) continue;
					result.computeIfAbsent(rs.getString("code"), k -> new LinkedHashMap<>())
						.put(keyPrefix + "." + rs.getInt("eid"), value);
				}
			}
		} catch (Exception e) {
			logger.log(Level.SEVERE, "Error building dynamic name translations for " + entityType, e);
		}
	}

	/** Public: ORM writes to labels/localizations must invalidate it too. */
	public void invalidateTranslationsCache() {
		synchronized (_cacheLock) {
			_transCache = null;
		}
	}

	/**
	 * @brief Clears the employee cache. Must be called by every write modifying employees,
	 *        employee_dates, or employee_skills. TOTAL invalidation: most write methods do not
	 *        include structureId in their signature. Public: ORM (Panache) writes touching the
	 *        skill catalog must invalidate it too (the cache incorporates skills).
	 */
	public void invalidateEmployeesCache() {
		synchronized (_employeesCacheLock) {
			_employeesCache = null;
		}
	}

	/**
	 * Invalidates the employee snapshot only after the surrounding transaction commits.
	 * Invalidating it earlier allows a concurrent reader to cache the old committed
	 * snapshot between the invalidation and the commit.
	 */
	public void invalidateEmployeesAfterCommit() {
		try {
			transactionRegistry.registerInterposedSynchronization(new Synchronization() {
				@Override public void beforeCompletion() { }
				@Override public void afterCompletion(int status) {
					if (status == Status.STATUS_COMMITTED) invalidateEmployeesCache();
				}
			});
		} catch (IllegalStateException noTransaction) {
			invalidateEmployeesCache();
		}
	}

	/**
	 * @brief Clears ALL runtime caches and forces a schema recheck.
	 * @details Called by BackupService after a DB RESTORE: the file was replaced, so in-memory
	 *          translations and employees are no longer reliable, and an old backup might lack
	 *          recent tables (ensure* methods are idempotent and will rerun on the next request).
	 */
	public void invalidateRuntimeCaches() {
		invalidateTranslationsCache();
		invalidateEmployeesCache();
		synchronized (_schemaLock) {
			schemaInitialized = false;
		}
		ensureSchemaInitialized();
	}

	// -----------------------------------------------------------------------
	// Labels management
	// -----------------------------------------------------------------------

	private void ensureLabelsTable() {
		try (Connection conn = DatabaseConnection.connect(dbName);
			 Statement stmt = conn.createStatement()) {
			stmt.execute(
				"CREATE TABLE IF NOT EXISTS labels (" +
				"  id INTEGER PRIMARY KEY AUTOINCREMENT," +
				"  key TEXT NOT NULL UNIQUE," +
				"  description TEXT NOT NULL" +
				");"
			);
			// --- Buttons ---
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('btn.save','保存');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('btn.cancel','取消');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('btn.add','新增');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('btn.delete','删除');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('btn.edit','编辑');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('btn.close','关闭');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('btn.solve','自动排程');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('btn.stopSolving','停止求解');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('btn.copy','复制');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('btn.preview','预览');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('btn.generatePdf','生成 PDF');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('btn.addEmployee','新增审核员');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('btn.addLocation','新增地点');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('btn.addShift','新增任务');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('btn.handleSkill','管理资格');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('btn.printShifts','打印排班');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('btn.printCoverage','打印覆盖情况');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('btn.saveAndLock','保存并锁定');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('btn.unlock','解锁');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('btn.analyze','分析');");
			// --- Navigation ---
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('nav.title','排班管理');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('nav.languages','语言');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('nav.labels','界面文案');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('nav.structures','部门');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('nav.previousWeek','上一周');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('nav.nextWeek','下一周');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('nav.previousYear','上一年');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('nav.nextYear','下一年');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('badge.weekLocked','本周已锁定');");
			// --- Tabs ---
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('tab.byLocation','按地点');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('tab.byEmployee','按审核员');");

			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('select.none','— 无 —');");
			// Shift Management: save week as template + load from template list
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('btn.saveToTemplate','保存为模板');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('tooltip.saveToTemplate','将当前可见周的任务保存为模板（不含已分配审核员）');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('tooltip.saveToTemplateWeekOnly','仅在周视图中可用');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('modal.saveTemplate','保存为模板');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('modal.loadTemplate','从模板载入');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('hint.saveTemplateNoOperators','仅保存各地点任务，不含已分配审核员。');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('hint.loadTemplateReplaces','应用模板将替换当前可见周期内的排班。');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('savedTemplates.empty','暂无已保存模板。');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('col.createdAt','创建时间');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('btn.apply','应用');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('confirm.deleteSavedTemplate','删除此已保存模板？');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('toast.savedTemplateAdded','模板已保存！');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('toast.savedTemplateDeleted','模板已删除。');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('config.template.workingSchema','周工作模式');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('config.template.listHint','从排班管理保存的模板列表；点击铅笔图标编辑描述与任务。');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('modal.editSavedTemplate','编辑模板');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('tooltip.openSolverSettings','在新标签页打开「求解器参数」');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('hint.clickConstraintForSettings','点击约束可在新标签页打开「求解器参数」。');");
			// Guard against saving infeasible solutions (Solve Result modal)
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('msg.infeasibleSaveWarning','该方案违反了硬约束：保存后这些违反将写入任务。点击「仍然保存」确认。');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('btn.saveAnyway','仍然保存');");
			// System Info section (Configuration → Info)
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('config.menu.info','系统信息');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('config.menu.info.tooltip','系统版本与信息');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('config.info.appTitle','应用');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('config.info.mainTitle','主要组件');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('config.info.description','应用主要组件的版本信息。');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('config.info.checkUpdates','检查更新');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('config.info.error','无法加载后端信息。');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('config.info.component','组件');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('config.info.version','版本');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('config.info.updates','更新');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('config.info.unavailable','不可用');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('config.info.updateAvailable','有可用更新');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('config.info.upToDate','已是最新');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('config.info.secondaryTitle','次要库与组件');");
			// Complete missing localizations (2026-07-13 audit): backups, PDF templates, common entries
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('backup.autoRetentionDays','自动备份保留天数');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('backup.days','天');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('backup.delete','删除此备份');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('backup.deleteConfirm','删除');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('backup.deleteMsg','确定永久删除此备份？');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('backup.deleteTitle','删除备份');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('backup.intervalMinutes','自动备份间隔');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('backup.otherRetentionDays','其他备份保留天数');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('backup.rotationHint','超过保留天数的文件将在轮换时删除；备份数量上限同样生效。');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('backup.rotationSettings','自动轮换');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('backup.validation.autoDays','自动备份保留天数必须在 1 到 3650 天之间。');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('backup.validation.coherence','自动备份保留时长必须至少覆盖两个完整备份间隔。');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('backup.validation.interval','备份间隔必须为 1 到 1440 之间的整数分钟。');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('backup.validation.otherDays','其他备份保留天数必须在 1 到 3650 天之间。');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('backup.autoKeep','自动备份数量上限');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('backup.otherKeep','其他备份数量上限');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('backup.files','个文件');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('backup.validation.autoKeep','自动备份数量上限必须在 1 到 100000 之间。');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('backup.validation.otherKeep','其他备份数量上限必须在 1 到 100000 之间。');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('btn.remove','移除');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('common.close','关闭');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('common.no','否');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('common.yes','是');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('config.menu.pdfTemplate','PDF 模板');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('config.menu.pdfTemplate.tooltip','配置 PDF 徽标、页眉与页脚');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('msg.error.delete','删除失败。');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('pdfTpl.addTitle','新建 PDF 模板');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('pdfTpl.chooseLogo','选择徽标');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('pdfTpl.color','主色调');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('pdfTpl.deleteConfirm','删除 PDF 模板：');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('pdfTpl.deleteTitle','删除 PDF 模板');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('pdfTpl.deleted','PDF 模板已删除。');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('pdfTpl.editTitle','编辑 PDF 模板');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('pdfTpl.footer','页脚文本');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('pdfTpl.header','页眉文本');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('pdfTpl.logo','徽标');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('pdfTpl.logoHint','支持 PNG 或 JPG，最大 5 MB，将自动缩放。');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('pdfTpl.logoInvalid','请选择有效的 PNG 或 JPG 图片。');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('pdfTpl.logoTooLarge','徽标不能超过 5 MB。');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('pdfTpl.noFormats','未配置 PDF 格式。');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('pdfTpl.preview','预览');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('pdfTpl.previewFooter','生成日期与页码');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('pdfTpl.structure','公司');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('pdfTpl.structureLocked','编辑时无法更改所属公司。');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('skills.active','启用');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('toast.backupDeleteError','删除备份失败。');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('toast.backupDeleted','备份已删除。');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('toast.backupSettingsError','备份参数保存失败。');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('toast.backupSettingsSaved','备份参数已保存。');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('toast.endAfterStart','结束时间必须晚于开始时间。');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('toast.errorSolverStart','求解器启动失败。');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('toast.pdfTemplateSaved','PDF 模板已保存。');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('tooltip.edit','编辑');");
			// Shift Templates section: description + window
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('col.company','公司');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('config.template.shiftsConfigured','已配置任务');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('config.template.window','窗口');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('config.template.window.hint','粒度在常规设置中配置。');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('placeholder.templateDescription','模板描述…');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('toast.templateDescSaved','描述已保存！');");
			// Multilingual skill-name translations (tab in the skill modal)
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('hint.skillTranslationFallback','留空的翻译将使用基础名称。');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('tab.all','全部');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('tab.desired','期望');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('tab.undesired','不希望');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('tab.unavailable','不可用');");
			// --- Modal titles ---
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('modal.scoreAnalysis','得分分析');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('modal.addEmployee','新增审核员');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('modal.editEmployee','编辑审核员');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('modal.deleteEmployee','删除审核员');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('modal.employeeDates','审核员日期');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('modal.addLocation','新增地点');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('modal.editLocation','编辑地点');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('modal.deleteLocation','删除地点');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('modal.shiftsList','任务列表');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('modal.skillsForShift','任务所需资格');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('modal.skillsList','资格列表');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('modal.handleShift','处理任务');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('modal.shiftDetail','任务详情');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('modal.availabilityDetail','可用性详情');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('modal.printWeeklyShifts','打印每周排班');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('modal.printCoverageShifts','打印覆盖排班');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('modal.confirm','确认');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('modal.manageLanguages','语言管理');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('modal.manageLabels','文案管理');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('modal.translations','翻译');");
			// --- Form labels ---
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('label.firstName','名');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('label.lastName','姓');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('label.email','邮箱');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('placeholder.email','name@example.com');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('validation.emailInvalid','请输入有效的邮箱地址。');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('label.code','编号');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('label.skills','资格');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('label.locationName','地点名称');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('label.locationCode','地点编号');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('label.locationOrder','地点顺序');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('label.requiredSkills','必需资格');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('label.optionalSkills','可选资格');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('label.location','地点');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('label.startTime','开始时间');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('label.endTime','结束时间');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('label.nurse','护士');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('label.month','月');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('label.facility','设施');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('label.employee','审核员');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('label.type','类型');");
			// --- Column headers ---
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('col.id','ID');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('col.name','名称');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('col.code','编号');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('col.order','顺序');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('col.actions','操作');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('col.type','类型');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('col.dateStart','开始日期');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('col.dateEnd','结束日期');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('col.start','开始');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('col.end','结束');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('col.employee','审核员');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('col.location','地点');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('col.day','星期');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('col.requiredSkills','必需资格');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('col.optionalSkills','可选资格');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('col.active','启用');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('col.description','描述');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('col.key','键名');");
			// --- Placeholders ---
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('placeholder.locationName','请输入地点名称');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('placeholder.locationCode','请输入地点编号');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('placeholder.selectLocation','选择地点');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('placeholder.selectNurse','选择护士…');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('placeholder.selectFacility','选择设施…');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('placeholder.skillName','资格名称');");
			// --- Select options ---
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('option.desired','期望');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('option.undesired','不希望');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('option.unavailable','不可用');");
			// --- Toast titles ---
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('toast.success','操作成功');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('toast.error','错误');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('toast.warning','警告');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('toast.info','提示');");
			// --- Messages: success ---
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('msg.success.saved','已保存！');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('msg.success.scheduleComplete','排班完成！');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('msg.success.weekSaved','本周已保存并锁定。');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('msg.success.weekUnlocked','本周已解锁。');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('msg.success.deleteEmployee','审核员已删除！');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('msg.success.deleteLocation','地点已删除！');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('msg.success.deleteDate','日期已删除。');");
			// --- Messages: errors ---
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('msg.error.loadData','数据加载失败。');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('msg.error.loadSchedule','排班加载失败。');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('msg.error.saveWeek','保存本周失败。');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('msg.error.loadEmployee','审核员数据加载失败。');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('msg.error.duplicateEmployeeCode','审核员编号已被使用，请更换编号。');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('msg.error.editEmployee','编辑审核员失败。');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('msg.error.addEmployee','新增审核员失败。');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('msg.error.deleteEmployee','删除审核员失败。');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('msg.error.loadSkills','资格加载失败。');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('msg.error.loadLocations','地点加载失败。');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('msg.error.duplicateLocationCode','地点编号已被使用。');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('msg.error.updateLocation','更新地点失败。');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('msg.error.addLocation','新增地点失败。');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('msg.error.deleteLocation','删除地点失败。');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('msg.error.deleteShift','删除任务失败。');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('msg.error.addShift','新增任务失败。');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('msg.error.saveSkills','资格保存失败。');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('msg.error.deleteSkill','删除资格失败。');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('msg.error.deleteDate','删除日期失败。');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('msg.error.save','保存失败，请重试。');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('msg.error.retrieveData','数据获取失败，请重试。');");
			// --- Messages: warnings / validation ---
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('msg.warning.requiredFields','所有字段均为必填项！');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('msg.warning.invalidDatetime','日期/时间格式无效！');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('msg.warning.endBeforeStart','结束时间必须晚于开始时间！');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('msg.warning.noValidSkills','没有可保存的有效资格。');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('msg.warning.shiftNotFound','未找到该任务。');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('msg.warning.noShifts','暂无可用任务。');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('msg.warning.insufficientResources','资源不足，无法完成排班。');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('msg.noUnassignedShifts','暂无未分配任务');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('msg.solving','求解中…');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('msg.solved','求解完成');");
			// --- Confirm dialogs ---
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('confirm.deleteEmployee','确定删除此审核员？');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('confirm.deleteLocation','确定删除此地点？');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('confirm.deleteSkill','确定删除此资格？');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('confirm.deleteLabel','删除此文案及其全部翻译？');");
			// --- Validation (report) ---
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('msg.validation.selectNurseMonth','请选择护士和月份。');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('msg.validation.selectFacilityMonth','请选择设施和月份。');");
			// --- PDF ---
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('pdf.titleMonthlyShifts','排班报表');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('pdf.titleCoverage','覆盖排班');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('pdf.filenameShifts','排班');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('pdf.filenameCoverage','覆盖情况');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('pdf.totalHours','总工时');");
			// --- Confirm dialog ---
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('confirm.title','确认');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('btn.confirm','确认');");
			// --- Days (full name) ---
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('day.sun','周日');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('day.mon','周一');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('day.tue','周二');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('day.wed','周三');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('day.thu','周四');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('day.fri','周五');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('day.sat','周六');");
			// --- Days (short 3-letter abbreviation) ---
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('day.sun.s','周日');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('day.mon.s','周一');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('day.tue.s','周二');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('day.wed.s','周三');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('day.thu.s','周四');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('day.fri.s','周五');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('day.sat.s','周六');");
			// --- Months (full name) ---
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('month.jan','1月');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('month.feb','2月');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('month.mar','3月');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('month.apr','4月');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('month.may','5月');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('month.jun','6月');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('month.jul','7月');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('month.aug','8月');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('month.sep','9月');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('month.oct','10月');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('month.nov','11月');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('month.dec','12月');");
			// --- Months (short 3-letter abbreviation) ---
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('month.jan.s','1月');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('month.feb.s','2月');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('month.mar.s','3月');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('month.apr.s','4月');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('month.may.s','5月');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('month.jun.s','6月');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('month.jul.s','7月');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('month.aug.s','8月');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('month.sep.s','9月');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('month.oct.s','10月');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('month.nov.s','11月');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('month.dec.s','12月');");
			// --- Context menu ---
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('ctx.edit','编辑');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('ctx.delete','删除');");
			// --- Date type tabs ---
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('dateType.desired','期望');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('dateType.undesired','不希望');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('dateType.unavailable','不可用');");
			// --- Table column headers (table.* prefix used in HTML) ---
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('table.id','ID');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('table.dateStart','开始日期');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('table.dateEnd','结束日期');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('table.type','类型');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('table.actions','操作');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('table.startDate','开始日期');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('table.endDate','结束日期');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('table.requiredSkills','必需资格');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('table.optionalSkills','可选资格');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('table.name','名称');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('table.order','顺序');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('table.code','编号');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('table.description','描述');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('table.active','启用');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('table.key','键名');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('table.translation','翻译');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('table.constraint','约束');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('table.matches','匹配数');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('table.weight','权重');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('table.score','得分');");
			// --- Modal aliases (keys used in HTML, matching different seed keys) ---
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('modal.employeeDatesList','审核员日期列表');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('modal.shiftDetails','任务详情');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('modal.dateDetails','日期详情');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('modal.printShifts','打印每周排班');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('modal.printCoverage','打印覆盖排班');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('modal.languageManagement','语言管理');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('modal.labelManagement','文案管理');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('modal.deleteLabel','删除文案');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('modal.deleteLanguage','删除语言');");
			// --- Additional buttons ---
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('btn.exportJson','导出 JSON');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('btn.saveAll','全部保存');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('btn.selectSkills','选择资格');");
			// --- Additional form labels ---
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('label.start','开始');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('label.end','结束');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('label.score','得分');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('label.selectMonthForReport','选择生成报表的月份。');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('label.reportPreview','预览：找到的排班');");
			// --- Additional placeholders ---
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('placeholder.locationOrder','请输入地点顺序');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('placeholder.searchKeyOrDescription','搜索键名或描述…');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('placeholder.searchLabels','搜索键名、描述或翻译…');");
			// --- Tooltips ---
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('tooltip.previousYear','上一年');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('tooltip.nextYear','下一年');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('tooltip.previousWeek','上一周');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('tooltip.nextWeek','下一周');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('tooltip.weekLocked','本周已锁定');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('tooltip.saveAndLockWeek','保存并锁定本周');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('tooltip.unlockWeek','解锁本周以编辑');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('tooltip.editEmployee','编辑审核员');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('tooltip.deleteEmployee','删除审核员');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('tooltip.editLocation','编辑地点');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('tooltip.deleteLocation','删除地点');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('tooltip.employeeDatesWithData','管理日期（已有日期）');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('tooltip.employeeDatesEmpty','管理日期（暂无日期）');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('tooltip.locationShiftsWithData','管理排班日期（已有任务）');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('tooltip.locationShiftsEmpty','管理排班日期（暂无任务）');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('tooltip.delete','删除');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('tooltip.clearSearch','清除搜索');");
			// --- i18n sweep: missing frontend keys (Buttons) ---
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('btn.addRange','新增时间段');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('btn.applyTemplate','从模板填充');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('btn.interrupt','中止');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('btn.today','今天');");
			// --- Configuration ---
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('config.title','配置');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('config.menu.general','常规设置');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('config.menu.localizations','界面翻译');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('config.menu.templates','排班模板');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('config.menu.structures','部门');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('config.menu.solverSettings','求解器参数');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('config.menu.solverSettings.tooltip','配置优化引擎参数');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('solverSettings.intro','所选部门的优化参数。');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('config.menu.skills','资格能力');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('config.menu.general.tooltip','配置常规参数');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('config.menu.localizations.tooltip','管理界面文案与翻译');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('config.menu.templates.tooltip','配置排班模板');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('config.menu.structures.tooltip','管理组织部门');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('config.menu.skills.tooltip','管理资格');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('skills.namesRequired','所有名称均为必填项。');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('skills.saved','资格已保存！');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('skills.deleted','资格已删除。');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('skills.addTooltip','新增资格');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('skills.new','新建');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('skills.noResults','暂无资格。');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('skills.newRow','新建');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('skills.namePlaceholder','资格名称');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('skills.inUseTooltip','资格正在使用中，无法删除');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('skills.deleteTitle','删除资格');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('modal.addSkill','新增资格');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('modal.editSkill','编辑资格');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('structures.loadError','部门加载失败。');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('structures.addTooltip','新增部门');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('structures.noResults','暂无部门。');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('structures.active','启用');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('structures.defaultDeleteTooltip','默认部门无法删除');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('structures.deleteTitle','删除部门');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('labels.addTooltip','新增文案');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('labels.searchPlaceholder','按键名或描述搜索…');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('labels.results','{{count}} 条结果');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('labels.noResults','未找到文案。');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('labels.deleteTitle','删除文案');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('labels.showTranslationKeys','显示键名');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('tooltip.showTranslationKeys','显示翻译键名');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('tooltip.hideTranslationKeys','隐藏翻译键名');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('config.section.shiftView','排班视图');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('config.section.template','每周排班模板');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('config.shiftWindow','排班窗口粒度');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('config.shiftWindow.hint','决定排班管理中的窗口宽度与箭头步长。');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('config.window.week','周');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('config.window.month','月');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('config.general.listHint','参数按部门保存，点击铅笔图标编辑。');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('config.general.col.window','粒度');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('config.general.col.autoPopulate','自动填充');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('toast.generalSettingsSaved','常规参数已保存。');");
			String[] solverHelpKeys = {"max_solve_seconds","unimproved_seconds","diminished_window_seconds","diminished_ratio_pct","context_days","minimum_rest_hours","max_shifts_per_day","max_weekly_hours","min_weekly_shifts","max_weekly_shifts","max_consecutive_days","min_days_off_per_week","desired_date_weight","undesired_date_weight","balance_weight","optional_skill_weight","same_location_weight","night_balance_weight","unassigned_weight","night_start_hour","night_end_hour","weekly_shift_weight","days_off_weight","consecutive_days_weight","balance_by_hours","allow_unassigned","stop_when_feasible"};
			for (String hk : solverHelpKeys) stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('solver.help." + hk + "','求解器参数帮助：" + hk + "');");
			// Solver Settings labels/hints/options (first 25 numeric fields, last three boolean).
			for (int i = 0; i < solverHelpKeys.length; i++) {
				String k = solverHelpKeys[i];
				if (i < 25) {
					stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('solver.label." + k + "','求解器参数：" + k + "');");
					stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('solver.hint." + k + "','Hint parametro solver " + k + "');");
				} else {
					stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('solver.opt." + k + "','Opzione solver " + k + "');");
				}
			}
			String[] solverMiscKeys = {"solver.group.processing","solver.group.dailyWeekly","solver.group.weights","solver.group.night","solver.group.options","solver.col.duration","solver.col.minRest","solver.col.maxPerDay","solver.col.balance","solver.balance.hours","solver.balance.shifts","toast.solverSettingsSaved","solver.err.unimproved","solver.err.weekly"};
			for (String k : solverMiscKeys) stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('" + k + "','求解器参数分区');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('config.template.sourceWeek','来源周');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('config.template.prepopulate','从本周预填充');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('config.template.hint','每周循环模板：点击空白区域新增任务，点击任务进行编辑；将用于新窗口的预填充。');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('config.template.hasShifts','有排班的日期');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('config.template.daysWithShifts','有排班的日期');");
			// --- Confirm dialogs ---
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('confirm.applyTemplate','用模板中的任务替换当前可见周期内的排班？该周期内现有任务将被删除。');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('confirm.deleteDateTitle','删除日期');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('confirm.deleteDateMessage','删除此时间段？');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('confirm.deleteEmployeeMessage','删除此审核员？');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('confirm.deleteLocationMessage','删除此地点？');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('confirm.deleteShiftTitle','删除任务');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('confirm.deleteShiftMessage','删除此任务？');");
			// --- Shift context menu ---
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('ctx.addShift','新增任务');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('ctx.editShift','编辑任务');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('ctx.deleteShift','删除任务');");
			// --- Weekdays (two-letter abbreviation) ---
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('dowShort.mon','一');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('dowShort.tue','二');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('dowShort.wed','三');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('dowShort.thu','四');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('dowShort.fri','五');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('dowShort.sat','六');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('dowShort.sun','日');");
			// --- Form labels ---
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('label.dayOfWeek','星期');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('label.name','名称');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('label.description','描述');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('label.key','键名');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('label.order','顺序');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('label.operator','审核员');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('label.translations','翻译');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('label.violatedConstraints','违反的约束');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('label.satisfiedConstraints','已满足约束');");
			// --- Modals ---
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('modal.addShift','新增任务');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('modal.editShift','编辑任务');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('modal.addTemplate','新增模板任务');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('modal.editTemplate','编辑模板任务');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('modal.addLabel','新增文案');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('modal.editLabel','编辑文案');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('modal.solveResult','求解结果');");
			// --- Months (full name) ---
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('month.january','1月');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('month.february','2月');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('month.march','3月');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('month.april','4月');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('month.june','6月');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('month.july','7月');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('month.august','8月');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('month.september','9月');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('month.october','10月');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('month.november','11月');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('month.december','12月');");
			// --- Months (three-letter abbreviation) ---
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('monthShort.jan','1月');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('monthShort.feb','2月');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('monthShort.mar','3月');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('monthShort.apr','4月');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('monthShort.may','5月');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('monthShort.jun','6月');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('monthShort.jul','7月');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('monthShort.aug','8月');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('monthShort.sep','9月');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('monthShort.oct','10月');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('monthShort.nov','11月');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('monthShort.dec','12月');");
			// --- Messages ---
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('msg.assigned','已分配');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('msg.dataNotAvailable','暂无数据。');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('msg.solvingInProgress','求解进行中…');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('msg.noDates','暂无日期。');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('msg.noShiftsForLocation','该地点暂无任务。');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('msg.selectStructure','请选择部门。');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('msg.allConstraintsSatisfied','所有约束均已满足 ✓');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('msg.constraintsViolated','个约束被违反');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('msg.noConstraintData','暂无约束数据。');");
			// --- Navbar ---
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('nav.shifts','排班管理');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('nav.shiftManagement','排班管理');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('nav.employees','审核员');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('nav.locations','地点');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('nav.skills','资格能力');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('nav.dates','审核员时间偏好');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('nav.report','报表');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('nav.config','配置');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('nav.structure','部门');");
			// --- Table ---
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('table.violations','违反次数');");
			// --- Toast ---
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('toast.solveCompleted','求解完成！');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('toast.errorSolverPolling','求解器轮询出错。');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('toast.errorLoadCalendar','日历加载失败。');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('toast.errorLoad','加载失败。');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('toast.errorLoadDates','日期加载失败。');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('toast.errorSave','保存失败。');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('toast.errorEdit','修改失败。');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('toast.selectLocation','请选择地点。');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('toast.locationSkillsLoadFailed','无法加载该地点的资格：保存前请先检查。');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('toast.insertStartEnd','请输入开始和结束时间。');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('toast.invalidDateFormat','日期/时间格式无效。');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('toast.shiftNoCrossDay','任务不能跨越两天。');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('toast.shiftUpdated','任务已更新！');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('toast.shiftAdded','任务已添加！');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('toast.shiftDeleted','任务已删除。');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('toast.locationUpdated','地点已更新！');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('toast.locationAdded','地点已添加！');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('toast.codeDuplicate','地点编号已被使用，请更换。');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('toast.structureUpdated','部门已更新。');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('toast.structureAdded','部门已添加。');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('toast.labelUpdated','文案已更新。');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('toast.labelAdded','文案已添加。');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('toast.keyRequired','必须填写键名。');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('toast.descriptionRequired','必须填写描述。');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('toast.datesSaved','日期已保存！');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('toast.dateDeleted','日期已删除。');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('toast.templateApplied','已按模板创建任务');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('toast.templateUpdated','模板已更新！');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('toast.templateAdded','模板已添加！');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('toast.templateDeleted','模板已删除。');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('toast.templatePrepopulated','模板已按所选周预填充。');");
			// --- Employees/Locations pages: action tooltips, confirmations, toasts ---
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('tooltip.employeeHasShifts','该审核员已有排班任务，无法删除');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('tooltip.locationHasShifts','该地点已有排班任务，无法删除');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('confirm.deleteEmployeeTitle','删除审核员');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('confirm.deleteLocationTitle','删除地点');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('confirm.deletePrefix','确定删除');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('msg.noEmployees','暂无审核员。');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('msg.noLocations','暂无地点。');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('toast.employeeDeleted','审核员已删除。');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('toast.employeeUpdated','审核员已更新！');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('toast.employeeAdded','审核员已添加！');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('toast.employeeCodeDuplicate','审核员编号已被使用，请更换。');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('toast.locationDeleted','地点已删除。');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('toast.errorDelete','删除失败。');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('toast.errorAdd','添加失败。');");
			// --- Active/disabled state (employees and locations) ---
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('label.active','启用');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('label.activeYes','是');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('label.inactive','否');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('hint.inactiveEmployee','停用后将不再出现在排班管理中，求解器也不会为其分配任务。');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('hint.inactiveLocation','停用后将不再出现在排班管理中，求解器将忽略该地点。');");
			// --- Period-navigation tooltips ---
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('tooltip.today','回到今天');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('tooltip.prevPeriod','上一周期');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('tooltip.nextPeriod','下一周期');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('tooltip.applyTemplate','用模板中的任务替换当前可见周期内的排班');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('tooltip.exportJson','导出 JSON');");
			// --- Additional messages ---
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('msg.solving.banner','正在求解排班…');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('msg.unknownLocation','未知地点');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('msg.unassigned','未分配');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('msg.unassignedShifts','有 {0} 个未分配任务。');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('msg.error.loadLabels','文案加载失败。');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('msg.error.loadLanguages','语言加载失败。');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('msg.error.generic','出错了');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('msg.error.loadTranslations','翻译加载失败');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('msg.success.addLabel','文案已添加。');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('msg.success.updateLabel','文案已更新。');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('msg.success.deleteLabel','文案已删除。');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('msg.success.saveTranslations','翻译已保存。');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('msg.success.addLanguage','语言添加成功。');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('msg.success.updateLanguage','语言已更新。');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('msg.success.deleteLanguage','语言已删除。');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('msg.success.activateLanguage','已更新启用语言。');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('msg.warning.labelKeyDescRequired','键名和描述为必填项。');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('msg.warning.langCodeDescRequired','代码和描述为必填项。');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('confirm.deleteLanguage','删除此语言？');");
			// --- Structures ---
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('modal.manageStructures','管理部门');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('modal.addStructure','新增部门');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('modal.editStructure','编辑部门');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('label.structureName','部门名称');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('label.address','地址');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('label.phone','电话');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('placeholder.structureName','请输入部门名称');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('placeholder.address','请输入地址');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('placeholder.phone','请输入电话');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('table.structureName','部门');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('table.address','地址');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('table.phone','电话');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('confirm.deleteStructure','删除此部门？');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('msg.error.deleteStructure','无法删除：该部门正在使用中。');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('msg.error.addStructure','新增部门失败。');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('msg.error.editStructure','编辑部门失败。');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('msg.success.addStructure','部门添加成功。');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('msg.success.updateStructure','部门已更新。');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('msg.success.deleteStructure','部门已删除。');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('msg.warning.structureNameRequired','必须填写部门名称。');");
			// --- Score analysis ---
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('msg.no.score.to.analyze','暂无可分析的得分，请先点击「自动排程」。');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('constraint.missing_required_skill','缺少必需资格');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('constraint.overlapping_shift','任务时间重叠');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('constraint.at_least_10_hours_between_2_shifts','两次任务间至少间隔 10 小时');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('constraint.max_one_shift_per_day','每天最多一个任务');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('constraint.unavailable_employee','审核员不可用');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('constraint.undesired_day_for_employee','审核员不希望日期');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('constraint.desired_day_for_employee','审核员期望日期');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('constraint.balance_employee_shift_assignments','平衡审核员任务分配');");
			// --- Pagination ---
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('pagination.labels','条文案');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('pagination.page','第');");
			stmt.execute("INSERT OR IGNORE INTO labels (key, description) VALUES ('pagination.results','条结果');");
		} catch (Exception e) {
			logger.log(Level.SEVERE, "Error during labels table migration", e);
		}
	}

	// -----------------------------------------------------------------------
	// Localizzazioni management
	// -----------------------------------------------------------------------

	private void ensureLocalizzazioniTable() {
		try (Connection conn = DatabaseConnection.connect(dbName);
			 Statement stmt = conn.createStatement()) {
			stmt.execute(
				"CREATE TABLE IF NOT EXISTS localizzazioni (" +
				"  id INTEGER PRIMARY KEY AUTOINCREMENT," +
				"  entity_type TEXT NOT NULL," +
				"  entity_id   INTEGER NOT NULL," +
				"  field_name  TEXT NOT NULL," +
				"  language_id INTEGER NOT NULL," +
				"  value       TEXT NOT NULL," +
				"  UNIQUE(entity_type, entity_id, field_name, language_id)" +
				");"
			);
		} catch (Exception e) {
			logger.log(Level.SEVERE, "Error during localizzazioni table migration", e);
		}
		seedLabelTranslations();
		seedLabelTranslations2();
		seedLabelTranslations3();
		seedAdditionalLabelTranslations();
		seedLabelTranslations4();
		migrateNavDatesTranslations();
	}

	private void ensureSolverSettingsTable() {
		try (Connection conn = DatabaseConnection.connect(dbName); Statement stmt = conn.createStatement()) {
			stmt.execute("CREATE TABLE IF NOT EXISTS solver_settings (" +
				"id INTEGER PRIMARY KEY AUTOINCREMENT, structure_id INTEGER NOT NULL UNIQUE," +
				"max_solve_seconds INTEGER NOT NULL DEFAULT 30, unimproved_seconds INTEGER NOT NULL DEFAULT 0," +
				"minimum_rest_hours INTEGER NOT NULL DEFAULT 10, max_shifts_per_day INTEGER NOT NULL DEFAULT 1," +
				"desired_date_weight INTEGER NOT NULL DEFAULT 1, undesired_date_weight INTEGER NOT NULL DEFAULT 1," +
				"balance_weight INTEGER NOT NULL DEFAULT 1, optional_skill_weight INTEGER NOT NULL DEFAULT 1," +
				"balance_by_hours INTEGER NOT NULL DEFAULT 1, max_weekly_hours INTEGER NOT NULL DEFAULT 0," +
				"min_weekly_shifts INTEGER NOT NULL DEFAULT 0, max_weekly_shifts INTEGER NOT NULL DEFAULT 0," +
				"max_consecutive_days INTEGER NOT NULL DEFAULT 0, min_days_off_per_week INTEGER NOT NULL DEFAULT 0," +
				"allow_unassigned INTEGER NOT NULL DEFAULT 0, unassigned_weight INTEGER NOT NULL DEFAULT 10," +
				"same_location_weight INTEGER NOT NULL DEFAULT 0, night_balance_weight INTEGER NOT NULL DEFAULT 0," +
				"night_start_hour INTEGER NOT NULL DEFAULT 22, night_end_hour INTEGER NOT NULL DEFAULT 6," +
				"stop_when_feasible INTEGER NOT NULL DEFAULT 0," +
				"context_days INTEGER NOT NULL DEFAULT 1," +
				"diminished_window_seconds INTEGER NOT NULL DEFAULT 0," +
				"diminished_ratio_pct INTEGER NOT NULL DEFAULT 25," +
				"weekly_shift_weight INTEGER NOT NULL DEFAULT 1," +
				"days_off_weight INTEGER NOT NULL DEFAULT 1," +
				"consecutive_days_weight INTEGER NOT NULL DEFAULT 1," +
				"FOREIGN KEY (structure_id) REFERENCES structures(id) ON DELETE CASCADE);");
			String[] additions = {
				"balance_by_hours INTEGER NOT NULL DEFAULT 1", "max_weekly_hours INTEGER NOT NULL DEFAULT 0",
				"min_weekly_shifts INTEGER NOT NULL DEFAULT 0", "max_weekly_shifts INTEGER NOT NULL DEFAULT 0",
				"max_consecutive_days INTEGER NOT NULL DEFAULT 0", "min_days_off_per_week INTEGER NOT NULL DEFAULT 0",
				"allow_unassigned INTEGER NOT NULL DEFAULT 0", "unassigned_weight INTEGER NOT NULL DEFAULT 10",
				"same_location_weight INTEGER NOT NULL DEFAULT 0", "night_balance_weight INTEGER NOT NULL DEFAULT 0",
				"night_start_hour INTEGER NOT NULL DEFAULT 22", "night_end_hour INTEGER NOT NULL DEFAULT 6",
				"stop_when_feasible INTEGER NOT NULL DEFAULT 0",
				"context_days INTEGER NOT NULL DEFAULT 1",
				"diminished_window_seconds INTEGER NOT NULL DEFAULT 0",
				"diminished_ratio_pct INTEGER NOT NULL DEFAULT 25",
				"weekly_shift_weight INTEGER NOT NULL DEFAULT 1",
				"days_off_weight INTEGER NOT NULL DEFAULT 1",
				"consecutive_days_weight INTEGER NOT NULL DEFAULT 1"
			};
			for (String addition : additions) try { stmt.execute("ALTER TABLE solver_settings ADD COLUMN " + addition); } catch (SQLException ignored) {}
		} catch (Exception e) { logger.log(Level.SEVERE, "Error ensuring solver_settings table", e); }
	}

	private void ensureGeneralSettingsTable() {
		try (Connection conn = DatabaseConnection.connect(dbName); Statement stmt = conn.createStatement()) {
			stmt.execute("CREATE TABLE IF NOT EXISTS general_settings (" +
				"id INTEGER PRIMARY KEY AUTOINCREMENT, structure_id INTEGER NOT NULL UNIQUE," +
				"shift_window_mode TEXT NOT NULL DEFAULT 'month'," +
				"auto_populate_from_template INTEGER NOT NULL DEFAULT 0," +
				"FOREIGN KEY (structure_id) REFERENCES structures(id) ON DELETE CASCADE);");
		} catch (Exception e) { logger.log(Level.SEVERE, "Error ensuring general_settings table", e); }
	}

	private void ensureHomeUiSettingsTable() {
		try (Connection conn = DatabaseConnection.connect(dbName); Statement stmt = conn.createStatement()) {
			stmt.execute("CREATE TABLE IF NOT EXISTS home_ui_settings (" +
				"id INTEGER PRIMARY KEY AUTOINCREMENT," +
				"cover_key TEXT NOT NULL DEFAULT ''," +
				"cover_data_url TEXT NOT NULL DEFAULT ''," +
				"title_key TEXT NOT NULL DEFAULT 'home.title'," +
				"body_key TEXT NOT NULL DEFAULT 'home.body'," +
				"hint_key TEXT NOT NULL DEFAULT 'home.hint');");
			try { stmt.execute("ALTER TABLE home_ui_settings ADD COLUMN cover_data_url TEXT NOT NULL DEFAULT ''"); } catch (SQLException ignored) {}
			stmt.execute("INSERT OR IGNORE INTO home_ui_settings (id, cover_key, cover_data_url, title_key, body_key, hint_key) " +
				"VALUES (1, '', '', 'home.title', 'home.body', 'home.hint');");
		} catch (Exception e) { logger.log(Level.SEVERE, "Error ensuring home_ui_settings table", e); }
	}

	/** Updates the old generic "Dates" entry while preserving the same i18n key. */
	private void migrateNavDatesTranslations() {
		String[][] values = {
			{"en", "Operator Date Preferences"},
			{"fr", "Préférences de dates des opérateurs"},
			{"es", "Preferencias de fechas de operadores"},
			{"de", "Datumspräferenzen der Mitarbeiter"}
		};
		String sql = "UPDATE localizzazioni SET value = ? WHERE entity_type = 'labels' " +
			"AND field_name = 'value' AND entity_id = (SELECT id FROM labels WHERE key = 'nav.dates') " +
			"AND language_id = (SELECT id FROM languages WHERE code = ?);";
		try (Connection conn = DatabaseConnection.connect(dbName);
			 PreparedStatement stmt = conn.prepareStatement(sql)) {
			for (String[] value : values) {
				stmt.setString(1, value[1]);
				stmt.setString(2, value[0]);
				stmt.addBatch();
			}
			stmt.executeBatch();
			try (PreparedStatement label = conn.prepareStatement(
					"UPDATE labels SET description = ? WHERE key = 'nav.dates';")) {
				label.setString(1, "Voce menu Preferenze date Operatori");
				label.executeUpdate();
			}
		} catch (Exception e) {
			logger.log(Level.SEVERE, "Error migrating nav.dates translations", e);
		}
	}

	private void seedLabelTranslations() {
		// {labelKey, langCode, value}
		String[][] data = {
			// --- Buttons ---
			{"btn.save","en","Save"},{"btn.save","fr","Enregistrer"},{"btn.save","es","Guardar"},{"btn.save","de","Speichern"},
			{"btn.cancel","en","Cancel"},{"btn.cancel","fr","Annuler"},{"btn.cancel","es","Cancelar"},{"btn.cancel","de","Abbrechen"},
			{"btn.add","en","Add"},{"btn.add","fr","Ajouter"},{"btn.add","es","Añadir"},{"btn.add","de","Hinzufügen"},
			{"btn.delete","en","Delete"},{"btn.delete","fr","Supprimer"},{"btn.delete","es","Eliminar"},{"btn.delete","de","Löschen"},
			{"btn.edit","en","Edit"},{"btn.edit","fr","Modifier"},{"btn.edit","es","Editar"},{"btn.edit","de","Bearbeiten"},
			{"btn.close","en","Close"},{"btn.close","fr","Fermer"},{"btn.close","es","Cerrar"},{"btn.close","de","Schließen"},
			{"btn.solve","en","Solve"},{"btn.solve","fr","Résoudre"},{"btn.solve","es","Resolver"},{"btn.solve","de","Lösen"},
			{"btn.stopSolving","en","Stop solving"},{"btn.stopSolving","fr","Arrêter"},{"btn.stopSolving","es","Detener"},{"btn.stopSolving","de","Stoppen"},
			{"btn.copy","en","Copy"},{"btn.copy","fr","Copier"},{"btn.copy","es","Copiar"},{"btn.copy","de","Kopieren"},
			{"btn.preview","en","Preview"},{"btn.preview","fr","Aperçu"},{"btn.preview","es","Vista previa"},{"btn.preview","de","Vorschau"},
			{"btn.generatePdf","en","Generate PDF"},{"btn.generatePdf","fr","Générer PDF"},{"btn.generatePdf","es","Generar PDF"},{"btn.generatePdf","de","PDF generieren"},
			{"btn.addEmployee","en","Add Employee"},{"btn.addEmployee","fr","Ajouter Employé"},{"btn.addEmployee","es","Añadir Empleado"},{"btn.addEmployee","de","Mitarbeiter hinzufügen"},
			{"btn.addLocation","en","Add Location"},{"btn.addLocation","fr","Ajouter Lieu"},{"btn.addLocation","es","Añadir Ubicación"},{"btn.addLocation","de","Ort hinzufügen"},
			{"btn.addShift","en","Add Shift"},{"btn.addShift","fr","Ajouter Quart"},{"btn.addShift","es","Añadir Turno"},{"btn.addShift","de","Schicht hinzufügen"},
			{"btn.handleSkill","en","Handle Skill"},{"btn.handleSkill","fr","Gérer Compétences"},{"btn.handleSkill","es","Gestionar Habilidades"},{"btn.handleSkill","de","Kompetenzen verwalten"},
			{"btn.printShifts","en","Print Shifts"},{"btn.printShifts","fr","Imprimer Quarts"},{"btn.printShifts","es","Imprimir Turnos"},{"btn.printShifts","de","Schichten drucken"},
			{"btn.printCoverage","en","Print Coverage"},{"btn.printCoverage","fr","Imprimer Couverture"},{"btn.printCoverage","es","Imprimir Cobertura"},{"btn.printCoverage","de","Deckung drucken"},
			{"btn.saveAndLock","en","Save and Lock"},{"btn.saveAndLock","fr","Enregistrer et Verrouiller"},{"btn.saveAndLock","es","Guardar y Bloquear"},{"btn.saveAndLock","de","Speichern und Sperren"},
			{"btn.unlock","en","Unlock"},{"btn.unlock","fr","Déverrouiller"},{"btn.unlock","es","Desbloquear"},{"btn.unlock","de","Entsperren"},
			{"btn.analyze","en","Analyze"},{"btn.analyze","fr","Analyser"},{"btn.analyze","es","Analizar"},{"btn.analyze","de","Analysieren"},
			// --- Navigation ---
			{"nav.title","en","Shift Management"},{"nav.title","fr","Gestion des Quarts"},{"nav.title","es","Gestión de Turnos"},{"nav.title","de","Schichtverwaltung"},
			{"nav.languages","en","Languages"},{"nav.languages","fr","Langues"},{"nav.languages","es","Idiomas"},{"nav.languages","de","Sprachen"},
			{"nav.labels","en","Labels"},{"nav.labels","fr","Étiquettes"},{"nav.labels","es","Etiquetas"},{"nav.labels","de","Beschriftungen"},
			{"nav.structures","en","Structures"},{"nav.structures","fr","Structures"},{"nav.structures","es","Estructuras"},{"nav.structures","de","Strukturen"},
			{"nav.previousWeek","en","Previous week"},{"nav.previousWeek","fr","Semaine précédente"},{"nav.previousWeek","es","Semana anterior"},{"nav.previousWeek","de","Vorherige Woche"},
			{"nav.nextWeek","en","Next week"},{"nav.nextWeek","fr","Semaine suivante"},{"nav.nextWeek","es","Semana siguiente"},{"nav.nextWeek","de","Nächste Woche"},
			{"nav.previousYear","en","Previous year"},{"nav.previousYear","fr","Année précédente"},{"nav.previousYear","es","Año anterior"},{"nav.previousYear","de","Vorheriges Jahr"},
			{"nav.nextYear","en","Next year"},{"nav.nextYear","fr","Année suivante"},{"nav.nextYear","es","Año siguiente"},{"nav.nextYear","de","Nächstes Jahr"},
			{"badge.weekLocked","en","Week locked"},{"badge.weekLocked","fr","Semaine verrouillée"},{"badge.weekLocked","es","Semana bloqueada"},{"badge.weekLocked","de","Woche gesperrt"},
			// --- Tabs ---
			{"tab.byLocation","en","By location"},{"tab.byLocation","fr","Par lieu"},{"tab.byLocation","es","Por ubicación"},{"tab.byLocation","de","Nach Ort"},
			{"tab.byEmployee","en","By employee"},{"tab.byEmployee","fr","Par employé"},{"tab.byEmployee","es","Por empleado"},{"tab.byEmployee","de","Nach Mitarbeiter"},
			{"tab.all","en","All"},{"tab.all","fr","Tous"},{"tab.all","es","Todos"},{"tab.all","de","Alle"},
			{"tab.desired","en","Desired"},{"tab.desired","fr","Souhaités"},{"tab.desired","es","Deseados"},{"tab.desired","de","Bevorzugt"},
			{"tab.undesired","en","Undesired"},{"tab.undesired","fr","Non souhaités"},{"tab.undesired","es","No deseados"},{"tab.undesired","de","Nicht bevorzugt"},
			{"tab.unavailable","en","Unavailable"},{"tab.unavailable","fr","Non disponibles"},{"tab.unavailable","es","No disponibles"},{"tab.unavailable","de","Nicht verfügbar"},
			// --- Modal titles ---
			{"modal.scoreAnalysis","en","Score Analysis"},{"modal.scoreAnalysis","fr","Analyse du Score"},{"modal.scoreAnalysis","es","Análisis de Puntuación"},{"modal.scoreAnalysis","de","Score-Analyse"},
			{"modal.addEmployee","en","Add Employee"},{"modal.addEmployee","fr","Ajouter Employé"},{"modal.addEmployee","es","Añadir Empleado"},{"modal.addEmployee","de","Mitarbeiter hinzufügen"},
			{"modal.editEmployee","en","Edit Employee"},{"modal.editEmployee","fr","Modifier Employé"},{"modal.editEmployee","es","Editar Empleado"},{"modal.editEmployee","de","Mitarbeiter bearbeiten"},
			{"modal.deleteEmployee","en","Delete Employee"},{"modal.deleteEmployee","fr","Supprimer Employé"},{"modal.deleteEmployee","es","Eliminar Empleado"},{"modal.deleteEmployee","de","Mitarbeiter löschen"},
			{"modal.employeeDates","en","Employee Dates"},{"modal.employeeDates","fr","Dates Employé"},{"modal.employeeDates","es","Fechas Empleado"},{"modal.employeeDates","de","Mitarbeiterdaten"},
			{"modal.addLocation","en","Add Location"},{"modal.addLocation","fr","Ajouter Lieu"},{"modal.addLocation","es","Añadir Ubicación"},{"modal.addLocation","de","Ort hinzufügen"},
			{"modal.editLocation","en","Edit Location"},{"modal.editLocation","fr","Modifier Lieu"},{"modal.editLocation","es","Editar Ubicación"},{"modal.editLocation","de","Ort bearbeiten"},
			{"modal.deleteLocation","en","Delete Location"},{"modal.deleteLocation","fr","Supprimer Lieu"},{"modal.deleteLocation","es","Eliminar Ubicación"},{"modal.deleteLocation","de","Ort löschen"},
			{"modal.shiftsList","en","Shifts List"},{"modal.shiftsList","fr","Liste des Quarts"},{"modal.shiftsList","es","Lista de Turnos"},{"modal.shiftsList","de","Schichtliste"},
			{"modal.skillsForShift","en","Skills for Shift"},{"modal.skillsForShift","fr","Compétences du Quart"},{"modal.skillsForShift","es","Habilidades del Turno"},{"modal.skillsForShift","de","Schichtkompetenzen"},
			{"modal.skillsList","en","Skills List"},{"modal.skillsList","fr","Liste des Compétences"},{"modal.skillsList","es","Lista de Habilidades"},{"modal.skillsList","de","Kompetenzliste"},
			{"modal.handleShift","en","Handle Shift"},{"modal.handleShift","fr","Gérer Quart"},{"modal.handleShift","es","Gestionar Turno"},{"modal.handleShift","de","Schicht verwalten"},
			{"modal.shiftDetail","en","Shift Detail"},{"modal.shiftDetail","fr","Détail du Quart"},{"modal.shiftDetail","es","Detalle del Turno"},{"modal.shiftDetail","de","Schichtdetail"},
			{"modal.availabilityDetail","en","Availability Detail"},{"modal.availabilityDetail","fr","Détail Disponibilité"},{"modal.availabilityDetail","es","Detalle Disponibilidad"},{"modal.availabilityDetail","de","Verfügbarkeitsdetail"},
			{"modal.printWeeklyShifts","en","Print Weekly Shifts"},{"modal.printWeeklyShifts","fr","Imprimer Quarts Hebdomadaires"},{"modal.printWeeklyShifts","es","Imprimir Turnos Semanales"},{"modal.printWeeklyShifts","de","Wöchentliche Schichten drucken"},
			{"modal.printCoverageShifts","en","Print Coverage Shifts"},{"modal.printCoverageShifts","fr","Imprimer Couverture Quarts"},{"modal.printCoverageShifts","es","Imprimir Cobertura Turnos"},{"modal.printCoverageShifts","de","Schichtdeckung drucken"},
			{"modal.confirm","en","Confirm"},{"modal.confirm","fr","Confirmer"},{"modal.confirm","es","Confirmar"},{"modal.confirm","de","Bestätigen"},
			{"modal.manageLanguages","en","Language Management"},{"modal.manageLanguages","fr","Gestion des Langues"},{"modal.manageLanguages","es","Gestión de Idiomas"},{"modal.manageLanguages","de","Sprachverwaltung"},
			{"modal.manageLabels","en","Labels Management"},{"modal.manageLabels","fr","Gestion des Étiquettes"},{"modal.manageLabels","es","Gestión de Etiquetas"},{"modal.manageLabels","de","Beschriftungsverwaltung"},
			{"modal.translations","en","Translations"},{"modal.translations","fr","Traductions"},{"modal.translations","es","Traducciones"},{"modal.translations","de","Übersetzungen"},
			// --- Form labels ---
			{"label.firstName","en","First Name"},{"label.firstName","fr","Prénom"},{"label.firstName","es","Nombre"},{"label.firstName","de","Vorname"},
			{"label.lastName","en","Last Name"},{"label.lastName","fr","Nom"},{"label.lastName","es","Apellido"},{"label.lastName","de","Nachname"},
			{"label.code","en","Code"},{"label.code","fr","Code"},{"label.code","es","Código"},{"label.code","de","Code"},
			{"label.skills","en","Skills"},{"label.skills","fr","Compétences"},{"label.skills","es","Habilidades"},{"label.skills","de","Kompetenzen"},
			{"label.locationName","en","Location Name"},{"label.locationName","fr","Nom du Lieu"},{"label.locationName","es","Nombre Ubicación"},{"label.locationName","de","Ortsname"},
			{"label.locationCode","en","Location Code"},{"label.locationCode","fr","Code Lieu"},{"label.locationCode","es","Código Ubicación"},{"label.locationCode","de","Ortscode"},
			{"label.locationOrder","en","Location Order"},{"label.locationOrder","fr","Ordre Lieu"},{"label.locationOrder","es","Orden Ubicación"},{"label.locationOrder","de","Ortsreihenfolge"},
			{"label.requiredSkills","en","Required Skills"},{"label.requiredSkills","fr","Compétences Requises"},{"label.requiredSkills","es","Habilidades Requeridas"},{"label.requiredSkills","de","Erforderliche Kompetenzen"},
			{"label.optionalSkills","en","Optional Skills"},{"label.optionalSkills","fr","Compétences Optionnelles"},{"label.optionalSkills","es","Habilidades Opcionales"},{"label.optionalSkills","de","Optionale Kompetenzen"},
			{"label.location","en","Location"},{"label.location","fr","Lieu"},{"label.location","es","Ubicación"},{"label.location","de","Ort"},
			{"label.startTime","en","Start Time"},{"label.startTime","fr","Heure Début"},{"label.startTime","es","Hora Inicio"},{"label.startTime","de","Startzeit"},
			{"label.endTime","en","End Time"},{"label.endTime","fr","Heure Fin"},{"label.endTime","es","Hora Fin"},{"label.endTime","de","Endzeit"},
			{"label.nurse","en","Nurse"},{"label.nurse","fr","Infirmière"},{"label.nurse","es","Enfermera"},{"label.nurse","de","Krankenschwester"},
			{"label.month","en","Month"},{"label.month","fr","Mois"},{"label.month","es","Mes"},{"label.month","de","Monat"},
			{"label.facility","en","Facility"},{"label.facility","fr","Structure"},{"label.facility","es","Instalación"},{"label.facility","de","Einrichtung"},
			{"label.employee","en","Employee"},{"label.employee","fr","Employé"},{"label.employee","es","Empleado"},{"label.employee","de","Mitarbeiter"},
			{"label.type","en","Type"},{"label.type","fr","Type"},{"label.type","es","Tipo"},{"label.type","de","Typ"},
			// --- Column headers ---
			{"col.id","en","ID"},{"col.id","fr","ID"},{"col.id","es","ID"},{"col.id","de","ID"},
			{"col.name","en","Name"},{"col.name","fr","Nom"},{"col.name","es","Nombre"},{"col.name","de","Name"},
			{"col.code","en","Code"},{"col.code","fr","Code"},{"col.code","es","Código"},{"col.code","de","Code"},
			{"col.order","en","Order"},{"col.order","fr","Ordre"},{"col.order","es","Orden"},{"col.order","de","Reihenfolge"},
			{"col.actions","en","Actions"},{"col.actions","fr","Actions"},{"col.actions","es","Acciones"},{"col.actions","de","Aktionen"},
			{"col.type","en","Type"},{"col.type","fr","Type"},{"col.type","es","Tipo"},{"col.type","de","Typ"},
			{"col.dateStart","en","Start Date"},{"col.dateStart","fr","Date Début"},{"col.dateStart","es","Fecha Inicio"},{"col.dateStart","de","Startdatum"},
			{"col.dateEnd","en","End Date"},{"col.dateEnd","fr","Date Fin"},{"col.dateEnd","es","Fecha Fin"},{"col.dateEnd","de","Enddatum"},
			{"col.start","en","Start"},{"col.start","fr","Début"},{"col.start","es","Inicio"},{"col.start","de","Start"},
			{"col.end","en","End"},{"col.end","fr","Fin"},{"col.end","es","Fin"},{"col.end","de","Ende"},
			{"col.employee","en","Employee"},{"col.employee","fr","Employé"},{"col.employee","es","Empleado"},{"col.employee","de","Mitarbeiter"},
			{"col.location","en","Location"},{"col.location","fr","Lieu"},{"col.location","es","Ubicación"},{"col.location","de","Ort"},
			{"col.day","en","Day"},{"col.day","fr","Jour"},{"col.day","es","Día"},{"col.day","de","Tag"},
			{"col.requiredSkills","en","R.Skills"},{"col.requiredSkills","fr","Comp.Req."},{"col.requiredSkills","es","Hab.Req."},{"col.requiredSkills","de","Erf.Komp."},
			{"col.optionalSkills","en","O.Skills"},{"col.optionalSkills","fr","Comp.Opt."},{"col.optionalSkills","es","Hab.Opc."},{"col.optionalSkills","de","Opt.Komp."},
			{"col.active","en","Active"},{"col.active","fr","Active"},{"col.active","es","Activa"},{"col.active","de","Aktiv"},
			{"col.description","en","Description"},{"col.description","fr","Description"},{"col.description","es","Descripción"},{"col.description","de","Beschreibung"},
			{"col.key","en","Key"},{"col.key","fr","Clé"},{"col.key","es","Clave"},{"col.key","de","Schlüssel"},
			// --- Placeholders ---
			{"placeholder.locationName","en","Enter location name"},{"placeholder.locationName","fr","Entrez le nom du lieu"},{"placeholder.locationName","es","Ingrese nombre ubicación"},{"placeholder.locationName","de","Ortsname eingeben"},
			{"placeholder.locationCode","en","Enter location code"},{"placeholder.locationCode","fr","Entrez le code du lieu"},{"placeholder.locationCode","es","Ingrese código ubicación"},{"placeholder.locationCode","de","Ortscode eingeben"},
			{"placeholder.selectLocation","en","Select Location"},{"placeholder.selectLocation","fr","Sélectionner Lieu"},{"placeholder.selectLocation","es","Seleccionar Ubicación"},{"placeholder.selectLocation","de","Ort auswählen"},
			{"placeholder.selectNurse","en","Select nurse..."},{"placeholder.selectNurse","fr","Sélectionner infirmière..."},{"placeholder.selectNurse","es","Seleccionar enfermera..."},{"placeholder.selectNurse","de","Krankenschwester auswählen..."},
			{"placeholder.selectFacility","en","Select facility..."},{"placeholder.selectFacility","fr","Sélectionner structure..."},{"placeholder.selectFacility","es","Seleccionar instalación..."},{"placeholder.selectFacility","de","Einrichtung auswählen..."},
			{"placeholder.skillName","en","Skill Name"},{"placeholder.skillName","fr","Nom Compétence"},{"placeholder.skillName","es","Nombre Habilidad"},{"placeholder.skillName","de","Kompetenzname"},
			// --- Options ---
			{"option.desired","en","Desired"},{"option.desired","fr","Souhaité"},{"option.desired","es","Deseado"},{"option.desired","de","Bevorzugt"},
			{"option.undesired","en","Undesired"},{"option.undesired","fr","Non Souhaité"},{"option.undesired","es","No Deseado"},{"option.undesired","de","Nicht bevorzugt"},
			{"option.unavailable","en","Unavailable"},{"option.unavailable","fr","Non Disponible"},{"option.unavailable","es","No Disponible"},{"option.unavailable","de","Nicht verfügbar"},
			// --- Toast titles ---
			{"toast.success","en","Success"},{"toast.success","fr","Succès"},{"toast.success","es","Éxito"},{"toast.success","de","Erfolg"},
			{"toast.error","en","Error"},{"toast.error","fr","Erreur"},{"toast.error","es","Error"},{"toast.error","de","Fehler"},
			{"toast.warning","en","Warning"},{"toast.warning","fr","Attention"},{"toast.warning","es","Atención"},{"toast.warning","de","Warnung"},
			{"toast.info","en","Information"},{"toast.info","fr","Information"},{"toast.info","es","Información"},{"toast.info","de","Information"},
			// --- Messages: success ---
			{"msg.success.saved","en","Saved!"},{"msg.success.saved","fr","Enregistré!"},{"msg.success.saved","es","Guardado!"},{"msg.success.saved","de","Gespeichert!"},
			{"msg.success.scheduleComplete","en","Schedule complete!"},{"msg.success.scheduleComplete","fr","Planning terminé!"},{"msg.success.scheduleComplete","es","Programación completa!"},{"msg.success.scheduleComplete","de","Zeitplan abgeschlossen!"},
			{"msg.success.weekSaved","en","Week saved and locked."},{"msg.success.weekSaved","fr","Semaine sauvegardée et verrouillée."},{"msg.success.weekSaved","es","Semana guardada y bloqueada."},{"msg.success.weekSaved","de","Woche gespeichert und gesperrt."},
			{"msg.success.weekUnlocked","en","Week unlocked."},{"msg.success.weekUnlocked","fr","Semaine déverrouillée."},{"msg.success.weekUnlocked","es","Semana desbloqueada."},{"msg.success.weekUnlocked","de","Woche entsperrt."},
			{"msg.success.deleteEmployee","en","Employee deleted successfully!"},{"msg.success.deleteEmployee","fr","Employé supprimé avec succès!"},{"msg.success.deleteEmployee","es","Empleado eliminado con éxito!"},{"msg.success.deleteEmployee","de","Mitarbeiter erfolgreich gelöscht!"},
			{"msg.success.deleteLocation","en","Location deleted successfully!"},{"msg.success.deleteLocation","fr","Lieu supprimé avec succès!"},{"msg.success.deleteLocation","es","Ubicación eliminada con éxito!"},{"msg.success.deleteLocation","de","Ort erfolgreich gelöscht!"},
			{"msg.success.deleteDate","en","Date deleted successfully."},{"msg.success.deleteDate","fr","Date supprimée avec succès."},{"msg.success.deleteDate","es","Fecha eliminada con éxito."},{"msg.success.deleteDate","de","Datum erfolgreich gelöscht."},
			// --- Messages: errors ---
			{"msg.error.loadData","en","Error loading data."},{"msg.error.loadData","fr","Erreur lors du chargement des données."},{"msg.error.loadData","es","Error al cargar los datos."},{"msg.error.loadData","de","Fehler beim Laden der Daten."},
			{"msg.error.loadSchedule","en","Error loading schedule."},{"msg.error.loadSchedule","fr","Erreur lors du chargement du planning."},{"msg.error.loadSchedule","es","Error al cargar el horario."},{"msg.error.loadSchedule","de","Fehler beim Laden des Zeitplans."},
			{"msg.error.saveWeek","en","Error saving week."},{"msg.error.saveWeek","fr","Erreur lors de l'enregistrement de la semaine."},{"msg.error.saveWeek","es","Error al guardar la semana."},{"msg.error.saveWeek","de","Fehler beim Speichern der Woche."},
			{"msg.error.loadEmployee","en","Error loading employee data."},{"msg.error.loadEmployee","fr","Erreur lors du chargement des données employé."},{"msg.error.loadEmployee","es","Error al cargar datos del empleado."},{"msg.error.loadEmployee","de","Fehler beim Laden der Mitarbeiterdaten."},
			{"msg.error.duplicateEmployeeCode","en","Employee code already in use. Choose a different code."},{"msg.error.duplicateEmployeeCode","fr","Code employé déjà utilisé. Choisissez un autre code."},{"msg.error.duplicateEmployeeCode","es","Código de empleado ya en uso. Elija otro código."},{"msg.error.duplicateEmployeeCode","de","Mitarbeitercode bereits vergeben. Wählen Sie einen anderen Code."},
			{"msg.error.editEmployee","en","Error editing employee."},{"msg.error.editEmployee","fr","Erreur lors de la modification de l'employé."},{"msg.error.editEmployee","es","Error al editar empleado."},{"msg.error.editEmployee","de","Fehler beim Bearbeiten des Mitarbeiters."},
			{"msg.error.addEmployee","en","Error adding employee."},{"msg.error.addEmployee","fr","Erreur lors de l'ajout de l'employé."},{"msg.error.addEmployee","es","Error al añadir empleado."},{"msg.error.addEmployee","de","Fehler beim Hinzufügen des Mitarbeiters."},
			{"msg.error.deleteEmployee","en","Error deleting employee."},{"msg.error.deleteEmployee","fr","Erreur lors de la suppression de l'employé."},{"msg.error.deleteEmployee","es","Error al eliminar empleado."},{"msg.error.deleteEmployee","de","Fehler beim Löschen des Mitarbeiters."},
			{"msg.error.loadSkills","en","Error loading skills."},{"msg.error.loadSkills","fr","Erreur lors du chargement des compétences."},{"msg.error.loadSkills","es","Error al cargar habilidades."},{"msg.error.loadSkills","de","Fehler beim Laden der Kompetenzen."},
			{"msg.error.loadLocations","en","Error loading locations."},{"msg.error.loadLocations","fr","Erreur lors du chargement des lieux."},{"msg.error.loadLocations","es","Error al cargar ubicaciones."},{"msg.error.loadLocations","de","Fehler beim Laden der Orte."},
			{"msg.error.duplicateLocationCode","en","Location code already in use."},{"msg.error.duplicateLocationCode","fr","Code lieu déjà utilisé."},{"msg.error.duplicateLocationCode","es","Código de ubicación ya en uso."},{"msg.error.duplicateLocationCode","de","Ortscode bereits vergeben."},
			{"msg.error.updateLocation","en","Error updating location."},{"msg.error.updateLocation","fr","Erreur lors de la mise à jour du lieu."},{"msg.error.updateLocation","es","Error al actualizar ubicación."},{"msg.error.updateLocation","de","Fehler beim Aktualisieren des Orts."},
			{"msg.error.addLocation","en","Error adding location."},{"msg.error.addLocation","fr","Erreur lors de l'ajout du lieu."},{"msg.error.addLocation","es","Error al añadir ubicación."},{"msg.error.addLocation","de","Fehler beim Hinzufügen des Orts."},
			{"msg.error.deleteLocation","en","Error deleting location."},{"msg.error.deleteLocation","fr","Erreur lors de la suppression du lieu."},{"msg.error.deleteLocation","es","Error al eliminar ubicación."},{"msg.error.deleteLocation","de","Fehler beim Löschen des Orts."},
			{"msg.error.deleteShift","en","Error deleting shift."},{"msg.error.deleteShift","fr","Erreur lors de la suppression du quart."},{"msg.error.deleteShift","es","Error al eliminar turno."},{"msg.error.deleteShift","de","Fehler beim Löschen der Schicht."},
			{"msg.error.addShift","en","Error adding shift."},{"msg.error.addShift","fr","Erreur lors de l'ajout du quart."},{"msg.error.addShift","es","Error al añadir turno."},{"msg.error.addShift","de","Fehler beim Hinzufügen der Schicht."},
			{"msg.error.saveSkills","en","Error saving skills."},{"msg.error.saveSkills","fr","Erreur lors de l'enregistrement des compétences."},{"msg.error.saveSkills","es","Error al guardar habilidades."},{"msg.error.saveSkills","de","Fehler beim Speichern der Kompetenzen."},
			{"msg.error.deleteSkill","en","Error deleting skill."},{"msg.error.deleteSkill","fr","Erreur lors de la suppression de la compétence."},{"msg.error.deleteSkill","es","Error al eliminar habilidad."},{"msg.error.deleteSkill","de","Fehler beim Löschen der Kompetenz."},
			{"msg.error.deleteDate","en","Error deleting date."},{"msg.error.deleteDate","fr","Erreur lors de la suppression de la date."},{"msg.error.deleteDate","es","Error al eliminar fecha."},{"msg.error.deleteDate","de","Fehler beim Löschen des Datums."},
			{"msg.error.save","en","Error saving. Please try again."},{"msg.error.save","fr","Erreur lors de l'enregistrement. Veuillez réessayer."},{"msg.error.save","es","Error al guardar. Inténtelo de nuevo."},{"msg.error.save","de","Fehler beim Speichern. Bitte erneut versuchen."},
			{"msg.error.retrieveData","en","Error retrieving data. Please try again."},{"msg.error.retrieveData","fr","Erreur lors de la récupération des données."},{"msg.error.retrieveData","es","Error al recuperar datos. Inténtelo de nuevo."},{"msg.error.retrieveData","de","Fehler beim Abrufen der Daten."},
			// --- Messages: warnings / validation ---
			{"msg.warning.requiredFields","en","All fields are required!"},{"msg.warning.requiredFields","fr","Tous les champs sont obligatoires!"},{"msg.warning.requiredFields","es","Todos los campos son obligatorios!"},{"msg.warning.requiredFields","de","Alle Felder sind erforderlich!"},
			{"msg.warning.invalidDatetime","en","Invalid date/time format!"},{"msg.warning.invalidDatetime","fr","Format date/heure invalide!"},{"msg.warning.invalidDatetime","es","Formato fecha/hora inválido!"},{"msg.warning.invalidDatetime","de","Ungültiges Datum/Uhrzeit-Format!"},
			{"msg.warning.endBeforeStart","en","End time must be after start time!"},{"msg.warning.endBeforeStart","fr","L'heure de fin doit être après l'heure de début!"},{"msg.warning.endBeforeStart","es","La hora de fin debe ser posterior al inicio!"},{"msg.warning.endBeforeStart","de","Endzeit muss nach Startzeit liegen!"},
			{"msg.warning.noValidSkills","en","No valid skills to save."},{"msg.warning.noValidSkills","fr","Aucune compétence valide à enregistrer."},{"msg.warning.noValidSkills","es","Sin habilidades válidas para guardar."},{"msg.warning.noValidSkills","de","Keine gültigen Kompetenzen zum Speichern."},
			{"msg.warning.shiftNotFound","en","Shift not found."},{"msg.warning.shiftNotFound","fr","Quart non trouvé."},{"msg.warning.shiftNotFound","es","Turno no encontrado."},{"msg.warning.shiftNotFound","de","Schicht nicht gefunden."},
			{"msg.warning.noShifts","en","No shifts available."},{"msg.warning.noShifts","fr","Aucun quart disponible."},{"msg.warning.noShifts","es","Sin turnos disponibles."},{"msg.warning.noShifts","de","Keine Schichten verfügbar."},
			{"msg.warning.insufficientResources","en","Insufficient resources to complete the schedule."},{"msg.warning.insufficientResources","fr","Ressources insuffisantes pour compléter le planning."},{"msg.warning.insufficientResources","es","Recursos insuficientes para completar el horario."},{"msg.warning.insufficientResources","de","Nicht genügend Ressourcen für den Zeitplan."},
			{"msg.noUnassignedShifts","en","No unassigned shifts."},{"msg.noUnassignedShifts","fr","Aucun quart non assigné."},{"msg.noUnassignedShifts","es","Sin turnos sin asignar."},{"msg.noUnassignedShifts","de","Keine nicht zugewiesenen Schichten."},
			{"msg.solving","en","Solving..."},{"msg.solving","fr","Résolution en cours..."},{"msg.solving","es","Resolviendo..."},{"msg.solving","de","Wird gelöst..."},
			{"msg.solved","en","Solving complete."},{"msg.solved","fr","Résolution terminée."},{"msg.solved","es","Resolución completada."},{"msg.solved","de","Lösung abgeschlossen."},
			// --- Confirm dialogs ---
			{"confirm.deleteEmployee","en","Are you sure you want to delete this employee?"},{"confirm.deleteEmployee","fr","Êtes-vous sûr de vouloir supprimer cet employé?"},{"confirm.deleteEmployee","es","¿Está seguro de eliminar este empleado?"},{"confirm.deleteEmployee","de","Möchten Sie diesen Mitarbeiter wirklich löschen?"},
			{"confirm.deleteLocation","en","Are you sure you want to delete this location?"},{"confirm.deleteLocation","fr","Êtes-vous sûr de vouloir supprimer ce lieu?"},{"confirm.deleteLocation","es","¿Está seguro de eliminar esta ubicación?"},{"confirm.deleteLocation","de","Möchten Sie diesen Ort wirklich löschen?"},
			{"confirm.deleteSkill","en","Are you sure you want to delete this skill?"},{"confirm.deleteSkill","fr","Êtes-vous sûr de vouloir supprimer cette compétence?"},{"confirm.deleteSkill","es","¿Está seguro de eliminar esta habilidad?"},{"confirm.deleteSkill","de","Möchten Sie diese Kompetenz wirklich löschen?"},
			{"confirm.deleteLabel","en","Delete this label and all its translations?"},{"confirm.deleteLabel","fr","Supprimer cette étiquette et toutes ses traductions?"},{"confirm.deleteLabel","es","¿Eliminar esta etiqueta y todas sus traducciones?"},{"confirm.deleteLabel","de","Diese Beschriftung und alle Übersetzungen löschen?"},
			// --- Validation (report) ---
			{"msg.validation.selectNurseMonth","en","Please select both nurse and month."},{"msg.validation.selectNurseMonth","fr","Veuillez sélectionner l'infirmière et le mois."},{"msg.validation.selectNurseMonth","es","Seleccione la enfermera y el mes."},{"msg.validation.selectNurseMonth","de","Bitte Krankenschwester und Monat auswählen."},
			{"msg.validation.selectFacilityMonth","en","Please select both facility and month."},{"msg.validation.selectFacilityMonth","fr","Veuillez sélectionner la structure et le mois."},{"msg.validation.selectFacilityMonth","es","Seleccione la instalación y el mes."},{"msg.validation.selectFacilityMonth","de","Bitte Einrichtung und Monat auswählen."},
			// --- PDF ---
			{"pdf.titleMonthlyShifts","en","Monthly Shifts"},{"pdf.titleMonthlyShifts","fr","Quarts Mensuels"},{"pdf.titleMonthlyShifts","es","Turnos Mensuales"},{"pdf.titleMonthlyShifts","de","Monatliche Schichten"},
			{"pdf.titleCoverage","en","Coverage Shifts"},{"pdf.titleCoverage","fr","Couverture des Quarts"},{"pdf.titleCoverage","es","Cobertura de Turnos"},{"pdf.titleCoverage","de","Schichtdeckung"},
			{"pdf.filenameShifts","en","Shifts"},{"pdf.filenameShifts","fr","Quarts"},{"pdf.filenameShifts","es","Turnos"},{"pdf.filenameShifts","de","Schichten"},
			{"pdf.filenameCoverage","en","Coverage"},{"pdf.filenameCoverage","fr","Couverture"},{"pdf.filenameCoverage","es","Cobertura"},{"pdf.filenameCoverage","de","Abdeckung"},
			{"pdf.totalHours","en","Total hours"},{"pdf.totalHours","fr","Heures totales"},{"pdf.totalHours","es","Horas totales"},{"pdf.totalHours","de","Gesamtstunden"},
			// --- Confirm dialog ---
			{"confirm.title","en","Confirm"},{"confirm.title","fr","Confirmer"},{"confirm.title","es","Confirmar"},{"confirm.title","de","Bestätigen"},
			{"btn.confirm","en","Confirm"},{"btn.confirm","fr","Confirmer"},{"btn.confirm","es","Confirmar"},{"btn.confirm","de","Bestätigen"},
			// --- Days (full name) ---
			{"day.sun","en","Sunday"},{"day.sun","fr","Dimanche"},{"day.sun","es","Domingo"},{"day.sun","de","Sonntag"},
			{"day.mon","en","Monday"},{"day.mon","fr","Lundi"},{"day.mon","es","Lunes"},{"day.mon","de","Montag"},
			{"day.tue","en","Tuesday"},{"day.tue","fr","Mardi"},{"day.tue","es","Martes"},{"day.tue","de","Dienstag"},
			{"day.wed","en","Wednesday"},{"day.wed","fr","Mercredi"},{"day.wed","es","Miércoles"},{"day.wed","de","Mittwoch"},
			{"day.thu","en","Thursday"},{"day.thu","fr","Jeudi"},{"day.thu","es","Jueves"},{"day.thu","de","Donnerstag"},
			{"day.fri","en","Friday"},{"day.fri","fr","Vendredi"},{"day.fri","es","Viernes"},{"day.fri","de","Freitag"},
			{"day.sat","en","Saturday"},{"day.sat","fr","Samedi"},{"day.sat","es","Sábado"},{"day.sat","de","Samstag"},
			// --- Days (short) ---
			{"day.sun.s","en","Sun"},{"day.sun.s","fr","Dim"},{"day.sun.s","es","Dom"},{"day.sun.s","de","Son"},
			{"day.mon.s","en","Mon"},{"day.mon.s","fr","Lun"},{"day.mon.s","es","Lun"},{"day.mon.s","de","Mon"},
			{"day.tue.s","en","Tue"},{"day.tue.s","fr","Mar"},{"day.tue.s","es","Mar"},{"day.tue.s","de","Die"},
			{"day.wed.s","en","Wed"},{"day.wed.s","fr","Mer"},{"day.wed.s","es","Mié"},{"day.wed.s","de","Mit"},
			{"day.thu.s","en","Thu"},{"day.thu.s","fr","Jeu"},{"day.thu.s","es","Jue"},{"day.thu.s","de","Don"},
			{"day.fri.s","en","Fri"},{"day.fri.s","fr","Ven"},{"day.fri.s","es","Vie"},{"day.fri.s","de","Fre"},
			{"day.sat.s","en","Sat"},{"day.sat.s","fr","Sam"},{"day.sat.s","es","Sáb"},{"day.sat.s","de","Sam"},
			// --- Months (full name) ---
			{"month.jan","en","January"},{"month.jan","fr","Janvier"},{"month.jan","es","Enero"},{"month.jan","de","Januar"},
			{"month.feb","en","February"},{"month.feb","fr","Février"},{"month.feb","es","Febrero"},{"month.feb","de","Februar"},
			{"month.mar","en","March"},{"month.mar","fr","Mars"},{"month.mar","es","Marzo"},{"month.mar","de","März"},
			{"month.apr","en","April"},{"month.apr","fr","Avril"},{"month.apr","es","Abril"},{"month.apr","de","April"},
			{"month.may","en","May"},{"month.may","fr","Mai"},{"month.may","es","Mayo"},{"month.may","de","Mai"},
			{"month.jun","en","June"},{"month.jun","fr","Juin"},{"month.jun","es","Junio"},{"month.jun","de","Juni"},
			{"month.jul","en","July"},{"month.jul","fr","Juillet"},{"month.jul","es","Julio"},{"month.jul","de","Juli"},
			{"month.aug","en","August"},{"month.aug","fr","Août"},{"month.aug","es","Agosto"},{"month.aug","de","August"},
			{"month.sep","en","September"},{"month.sep","fr","Septembre"},{"month.sep","es","Septiembre"},{"month.sep","de","September"},
			{"month.oct","en","October"},{"month.oct","fr","Octobre"},{"month.oct","es","Octubre"},{"month.oct","de","Oktober"},
			{"month.nov","en","November"},{"month.nov","fr","Novembre"},{"month.nov","es","Noviembre"},{"month.nov","de","November"},
			{"month.dec","en","December"},{"month.dec","fr","Décembre"},{"month.dec","es","Diciembre"},{"month.dec","de","Dezember"},
			// --- Months (short) ---
			{"month.jan.s","en","Jan"},{"month.jan.s","fr","Jan"},{"month.jan.s","es","Ene"},{"month.jan.s","de","Jan"},
			{"month.feb.s","en","Feb"},{"month.feb.s","fr","Fév"},{"month.feb.s","es","Feb"},{"month.feb.s","de","Feb"},
			{"month.mar.s","en","Mar"},{"month.mar.s","fr","Mar"},{"month.mar.s","es","Mar"},{"month.mar.s","de","Mär"},
			{"month.apr.s","en","Apr"},{"month.apr.s","fr","Avr"},{"month.apr.s","es","Abr"},{"month.apr.s","de","Apr"},
			{"month.may.s","en","May"},{"month.may.s","fr","Mai"},{"month.may.s","es","May"},{"month.may.s","de","Mai"},
			{"month.jun.s","en","Jun"},{"month.jun.s","fr","Jui"},{"month.jun.s","es","Jun"},{"month.jun.s","de","Jun"},
			{"month.jul.s","en","Jul"},{"month.jul.s","fr","Jul"},{"month.jul.s","es","Jul"},{"month.jul.s","de","Jul"},
			{"month.aug.s","en","Aug"},{"month.aug.s","fr","Aoû"},{"month.aug.s","es","Ago"},{"month.aug.s","de","Aug"},
			{"month.sep.s","en","Sep"},{"month.sep.s","fr","Sep"},{"month.sep.s","es","Sep"},{"month.sep.s","de","Sep"},
			{"month.oct.s","en","Oct"},{"month.oct.s","fr","Oct"},{"month.oct.s","es","Oct"},{"month.oct.s","de","Okt"},
			{"month.nov.s","en","Nov"},{"month.nov.s","fr","Nov"},{"month.nov.s","es","Nov"},{"month.nov.s","de","Nov"},
			{"month.dec.s","en","Dec"},{"month.dec.s","fr","Déc"},{"month.dec.s","es","Dic"},{"month.dec.s","de","Dez"},
			// --- Context menu ---
			{"ctx.edit","en","Edit"},{"ctx.edit","fr","Modifier"},{"ctx.edit","es","Editar"},{"ctx.edit","de","Bearbeiten"},
			{"ctx.delete","en","Delete"},{"ctx.delete","fr","Supprimer"},{"ctx.delete","es","Eliminar"},{"ctx.delete","de","Löschen"},
			// --- Date type tabs ---
			{"dateType.desired","en","Desired"},{"dateType.desired","fr","Souhaité"},{"dateType.desired","es","Deseado"},{"dateType.desired","de","Bevorzugt"},
			{"dateType.undesired","en","Undesired"},{"dateType.undesired","fr","Non Souhaité"},{"dateType.undesired","es","No Deseado"},{"dateType.undesired","de","Nicht bevorzugt"},
			{"dateType.unavailable","en","Unavailable"},{"dateType.unavailable","fr","Non Disponible"},{"dateType.unavailable","es","No Disponible"},{"dateType.unavailable","de","Nicht verfügbar"},
			// --- Table column headers ---
			{"table.id","en","ID"},{"table.id","fr","ID"},{"table.id","es","ID"},{"table.id","de","ID"},
			{"table.dateStart","en","Start Date"},{"table.dateStart","fr","Date Début"},{"table.dateStart","es","Fecha Inicio"},{"table.dateStart","de","Startdatum"},
			{"table.dateEnd","en","End Date"},{"table.dateEnd","fr","Date Fin"},{"table.dateEnd","es","Fecha Fin"},{"table.dateEnd","de","Enddatum"},
			{"table.type","en","Type"},{"table.type","fr","Type"},{"table.type","es","Tipo"},{"table.type","de","Typ"},
			{"table.actions","en","Actions"},{"table.actions","fr","Actions"},{"table.actions","es","Acciones"},{"table.actions","de","Aktionen"},
			{"table.startDate","en","Start Date"},{"table.startDate","fr","Date Début"},{"table.startDate","es","Fecha Inicio"},{"table.startDate","de","Startdatum"},
			{"table.endDate","en","End Date"},{"table.endDate","fr","Date Fin"},{"table.endDate","es","Fecha Fin"},{"table.endDate","de","Enddatum"},
			{"table.requiredSkills","en","R.Skills"},{"table.requiredSkills","fr","Comp.Req."},{"table.requiredSkills","es","Hab.Req."},{"table.requiredSkills","de","Erf.Komp."},
			{"table.optionalSkills","en","O.Skills"},{"table.optionalSkills","fr","Comp.Opt."},{"table.optionalSkills","es","Hab.Opc."},{"table.optionalSkills","de","Opt.Komp."},
			{"table.name","en","Name"},{"table.name","fr","Nom"},{"table.name","es","Nombre"},{"table.name","de","Name"},
			{"table.order","en","Order"},{"table.order","fr","Ordre"},{"table.order","es","Orden"},{"table.order","de","Reihenfolge"},
			{"table.code","en","Code"},{"table.code","fr","Code"},{"table.code","es","Código"},{"table.code","de","Code"},
			{"table.description","en","Description"},{"table.description","fr","Description"},{"table.description","es","Descripción"},{"table.description","de","Beschreibung"},
			{"table.active","en","Active"},{"table.active","fr","Active"},{"table.active","es","Activa"},{"table.active","de","Aktiv"},
			{"table.key","en","Key"},{"table.key","fr","Clé"},{"table.key","es","Clave"},{"table.key","de","Schlüssel"},
			{"table.translation","en","Translation"},{"table.translation","fr","Traduction"},{"table.translation","es","Traducción"},{"table.translation","de","Übersetzung"},
			{"table.constraint","en","Constraint"},{"table.constraint","fr","Contrainte"},{"table.constraint","es","Restricción"},{"table.constraint","de","Einschränkung"},
			{"table.matches","en","# Matches"},{"table.matches","fr","# Occurrences"},{"table.matches","es","# Coincidencias"},{"table.matches","de","# Treffer"},
			{"table.weight","en","Weight"},{"table.weight","fr","Poids"},{"table.weight","es","Peso"},{"table.weight","de","Gewicht"},
			{"table.score","en","Score"},{"table.score","fr","Score"},{"table.score","es","Score"},{"table.score","de","Score"},
			// --- Modal aliases ---
			{"modal.employeeDatesList","en","Employee Dates"},{"modal.employeeDatesList","fr","Dates Employé"},{"modal.employeeDatesList","es","Fechas Empleado"},{"modal.employeeDatesList","de","Mitarbeiterdaten"},
			{"modal.shiftDetails","en","Shift Detail"},{"modal.shiftDetails","fr","Détail du Quart"},{"modal.shiftDetails","es","Detalle del Turno"},{"modal.shiftDetails","de","Schichtdetail"},
			{"modal.dateDetails","en","Availability Detail"},{"modal.dateDetails","fr","Détail Disponibilité"},{"modal.dateDetails","es","Detalle Disponibilidad"},{"modal.dateDetails","de","Verfügbarkeitsdetail"},
			{"modal.printShifts","en","Print Weekly Shifts"},{"modal.printShifts","fr","Imprimer Quarts Hebdomadaires"},{"modal.printShifts","es","Imprimir Turnos Semanales"},{"modal.printShifts","de","Wöchentliche Schichten drucken"},
			{"modal.printCoverage","en","Print Coverage Shifts"},{"modal.printCoverage","fr","Imprimer Couverture Quarts"},{"modal.printCoverage","es","Imprimir Cobertura Turnos"},{"modal.printCoverage","de","Schichtdeckung drucken"},
			{"modal.languageManagement","en","Language Management"},{"modal.languageManagement","fr","Gestion des Langues"},{"modal.languageManagement","es","Gestión de Idiomas"},{"modal.languageManagement","de","Sprachverwaltung"},
			{"modal.labelManagement","en","Labels Management"},{"modal.labelManagement","fr","Gestion des Étiquettes"},{"modal.labelManagement","es","Gestión de Etiquetas"},{"modal.labelManagement","de","Beschriftungsverwaltung"},
			{"modal.deleteLabel","en","Delete Label"},{"modal.deleteLabel","fr","Supprimer Étiquette"},{"modal.deleteLabel","es","Eliminar Etiqueta"},{"modal.deleteLabel","de","Beschriftung löschen"},
			{"modal.deleteLanguage","en","Delete Language"},{"modal.deleteLanguage","fr","Supprimer Langue"},{"modal.deleteLanguage","es","Eliminar Idioma"},{"modal.deleteLanguage","de","Sprache löschen"},
			// --- Additional buttons ---
			{"btn.exportJson","en","Export JSON"},{"btn.exportJson","fr","Exporter JSON"},{"btn.exportJson","es","Exportar JSON"},{"btn.exportJson","de","JSON exportieren"},
			{"btn.saveAll","en","Save All"},{"btn.saveAll","fr","Tout enregistrer"},{"btn.saveAll","es","Guardar todo"},{"btn.saveAll","de","Alles speichern"},
			{"btn.selectSkills","en","Select Skills"},{"btn.selectSkills","fr","Sélectionner Compétences"},{"btn.selectSkills","es","Seleccionar Habilidades"},{"btn.selectSkills","de","Kompetenzen auswählen"},
			// --- Additional form labels ---
			{"label.start","en","Start"},{"label.start","fr","Début"},{"label.start","es","Inicio"},{"label.start","de","Start"},
			{"label.end","en","End"},{"label.end","fr","Fin"},{"label.end","es","Fin"},{"label.end","de","Ende"},
			{"label.score","en","Score"},{"label.score","fr","Score"},{"label.score","es","Puntuación"},{"label.score","de","Punktzahl"},
			{"label.selectMonthForReport","en","Select the month for generating the report."},{"label.selectMonthForReport","fr","Sélectionnez le mois pour générer le rapport."},{"label.selectMonthForReport","es","Seleccione el mes para generar el reporte."},{"label.selectMonthForReport","de","Wählen Sie den Monat für den Bericht."},
			{"label.reportPreview","en","Preview shifts found:"},{"label.reportPreview","fr","Aperçu des quarts trouvés:"},{"label.reportPreview","es","Vista previa de turnos encontrados:"},{"label.reportPreview","de","Vorschau gefundener Schichten:"},
			// --- Additional placeholders ---
			{"placeholder.locationOrder","en","Enter location order"},{"placeholder.locationOrder","fr","Entrez l'ordre du lieu"},{"placeholder.locationOrder","es","Ingrese orden ubicación"},{"placeholder.locationOrder","de","Ortsreihenfolge eingeben"},
			{"placeholder.searchKeyOrDescription","en","Search key or description..."},{"placeholder.searchKeyOrDescription","fr","Rechercher clé ou description..."},{"placeholder.searchKeyOrDescription","es","Buscar clave o descripción..."},{"placeholder.searchKeyOrDescription","de","Schlüssel oder Beschreibung suchen..."},
			{"placeholder.searchLabels","en","Search key, description or translation..."},{"placeholder.searchLabels","fr","Rechercher clé, description ou traduction..."},{"placeholder.searchLabels","es","Buscar clave, descripción o traducción..."},{"placeholder.searchLabels","de","Schlüssel, Beschreibung oder Übersetzung suchen..."},
			// --- Tooltips ---
			{"tooltip.previousYear","en","Previous year"},{"tooltip.previousYear","fr","Année précédente"},{"tooltip.previousYear","es","Año anterior"},{"tooltip.previousYear","de","Vorjahr"},
			{"tooltip.nextYear","en","Next year"},{"tooltip.nextYear","fr","Année suivante"},{"tooltip.nextYear","es","Año siguiente"},{"tooltip.nextYear","de","Nächstes Jahr"},
			{"tooltip.previousWeek","en","Previous week"},{"tooltip.previousWeek","fr","Semaine précédente"},{"tooltip.previousWeek","es","Semana anterior"},{"tooltip.previousWeek","de","Vorherige Woche"},
			{"tooltip.nextWeek","en","Next week"},{"tooltip.nextWeek","fr","Semaine suivante"},{"tooltip.nextWeek","es","Semana siguiente"},{"tooltip.nextWeek","de","Nächste Woche"},
			{"tooltip.weekLocked","en","Week locked"},{"tooltip.weekLocked","fr","Semaine verrouillée"},{"tooltip.weekLocked","es","Semana bloqueada"},{"tooltip.weekLocked","de","Woche gesperrt"},
			{"tooltip.saveAndLockWeek","en","Save and lock current week"},{"tooltip.saveAndLockWeek","fr","Enregistrer et verrouiller la semaine"},{"tooltip.saveAndLockWeek","es","Guardar y bloquear semana actual"},{"tooltip.saveAndLockWeek","de","Aktuelle Woche speichern und sperren"},
			{"tooltip.unlockWeek","en","Unlock week to edit it"},{"tooltip.unlockWeek","fr","Déverrouiller la semaine pour la modifier"},{"tooltip.unlockWeek","es","Desbloquear la semana para editarla"},{"tooltip.unlockWeek","de","Woche zum Bearbeiten entsperren"},
			{"tooltip.editEmployee","en","Edit employee"},{"tooltip.editEmployee","fr","Modifier l'employé"},{"tooltip.editEmployee","es","Editar empleado"},{"tooltip.editEmployee","de","Mitarbeiter bearbeiten"},
			{"tooltip.deleteEmployee","en","Delete employee"},{"tooltip.deleteEmployee","fr","Supprimer l'employé"},{"tooltip.deleteEmployee","es","Eliminar empleado"},{"tooltip.deleteEmployee","de","Mitarbeiter löschen"},
			{"tooltip.editLocation","en","Edit location"},{"tooltip.editLocation","fr","Modifier le lieu"},{"tooltip.editLocation","es","Editar ubicación"},{"tooltip.editLocation","de","Ort bearbeiten"},
			{"tooltip.deleteLocation","en","Delete location"},{"tooltip.deleteLocation","fr","Supprimer le lieu"},{"tooltip.deleteLocation","es","Eliminar ubicación"},{"tooltip.deleteLocation","de","Ort löschen"},
			{"tooltip.employeeDatesWithData","en","Manage dates (with dates assigned)"},{"tooltip.employeeDatesWithData","fr","Gérer les dates (avec dates assignées)"},{"tooltip.employeeDatesWithData","es","Gestionar fechas (con fechas asignadas)"},{"tooltip.employeeDatesWithData","de","Daten verwalten (mit zugewiesenen Daten)"},
			{"tooltip.employeeDatesEmpty","en","Manage dates (no dates)"},{"tooltip.employeeDatesEmpty","fr","Gérer les dates (aucune date)"},{"tooltip.employeeDatesEmpty","es","Gestionar fechas (sin fechas)"},{"tooltip.employeeDatesEmpty","de","Daten verwalten (keine Daten)"},
			{"tooltip.locationShiftsWithData","en","Manage shift dates (with shifts)"},{"tooltip.locationShiftsWithData","fr","Gérer dates de quart (avec quarts)"},{"tooltip.locationShiftsWithData","es","Gestionar fechas de turno (con turnos)"},{"tooltip.locationShiftsWithData","de","Schichtdaten verwalten (mit Schichten)"},
			{"tooltip.locationShiftsEmpty","en","Manage shift dates (no shifts)"},{"tooltip.locationShiftsEmpty","fr","Gérer dates de quart (aucun quart)"},{"tooltip.locationShiftsEmpty","es","Gestionar fechas de turno (sin turnos)"},{"tooltip.locationShiftsEmpty","de","Schichtdaten verwalten (keine Schichten)"},
			{"tooltip.delete","en","Delete"},{"tooltip.delete","fr","Supprimer"},{"tooltip.delete","es","Eliminar"},{"tooltip.delete","de","Löschen"},
			{"tooltip.clearSearch","en","Clear search"},{"tooltip.clearSearch","fr","Effacer la recherche"},{"tooltip.clearSearch","es","Limpiar búsqueda"},{"tooltip.clearSearch","de","Suche löschen"},
			// --- i18n sweep: missing frontend keys (it,en,fr,es,de) ---
			{"btn.addRange","en","Add range"},{"btn.addRange","fr","Ajouter une plage"},{"btn.addRange","es","Añadir franja"},{"btn.addRange","de","Bereich hinzufügen"},
			{"btn.applyTemplate","en","Fill from template"},{"btn.applyTemplate","fr","Remplir depuis le modèle"},{"btn.applyTemplate","es","Rellenar desde plantilla"},{"btn.applyTemplate","de","Aus Vorlage füllen"},
			{"btn.interrupt","en","Stop"},{"btn.interrupt","fr","Interrompre"},{"btn.interrupt","es","Interrumpir"},{"btn.interrupt","de","Abbrechen"},
			{"btn.today","en","Today"},{"btn.today","fr","Aujourd'hui"},{"btn.today","es","Hoy"},{"btn.today","de","Heute"},
			{"config.title","en","Configuration"},{"config.title","fr","Configuration"},{"config.title","es","Configuración"},{"config.title","de","Konfiguration"},
			{"config.section.shiftView","en","Shift view"},{"config.section.shiftView","fr","Affichage des quarts"},{"config.section.shiftView","es","Visualización de turnos"},{"config.section.shiftView","de","Schichtansicht"},
			{"config.section.template","en","Weekly shift template"},{"config.section.template","fr","Modèle de quarts hebdomadaire"},{"config.section.template","es","Plantilla de turnos semanal"},{"config.section.template","de","Wöchentliche Schichtvorlage"},
			{"config.shiftWindow","en","Shift window granularity"},{"config.shiftWindow","fr","Granularité de la fenêtre de quarts"},{"config.shiftWindow","es","Granularidad de la ventana de turnos"},{"config.shiftWindow","de","Granularität des Schichtfensters"},
			{"config.shiftWindow.hint","en","Determines the window width and the arrow step in Shift Management."},{"config.shiftWindow.hint","fr","Détermine la largeur de la fenêtre et le pas des flèches dans la gestion des quarts."},{"config.shiftWindow.hint","es","Determina el ancho de la ventana y el paso de las flechas en la gestión de turnos."},{"config.shiftWindow.hint","de","Bestimmt die Fensterbreite und die Schrittweite der Pfeile in der Schichtverwaltung."},
			{"config.window.week","en","Week"},{"config.window.week","fr","Semaine"},{"config.window.week","es","Semana"},{"config.window.week","de","Woche"},
			{"config.window.month","en","Month"},{"config.window.month","fr","Mois"},{"config.window.month","es","Mes"},{"config.window.month","de","Monat"},
		};
		insertLabelTranslations(data);
	}

	/** General + Solver Settings translations, extracted to stay below the JVM's 64 KB/method limit. */
	private void seedLabelTranslations2() {
		String[][] data = {
			{"config.general.listHint","en","Settings are per structure. Use the pencil to edit them."},{"config.general.listHint","fr","Les paramètres sont propres à chaque structure. Utilisez le crayon pour les modifier."},{"config.general.listHint","es","Los parámetros son propios de cada estructura. Usa el lápiz para editarlos."},{"config.general.listHint","de","Die Parameter sind strukturspezifisch. Zum Bearbeiten das Stift-Symbol verwenden."},
			{"config.general.col.window","en","Granularity"},{"config.general.col.window","fr","Granularité"},{"config.general.col.window","es","Granularidad"},{"config.general.col.window","de","Granularität"},
			{"config.general.col.autoPopulate","en","Auto-populate"},{"config.general.col.autoPopulate","fr","Remplissage auto"},{"config.general.col.autoPopulate","es","Autocompletado"},{"config.general.col.autoPopulate","de","Auto-Befüllung"},
			{"toast.generalSettingsSaved","en","General settings saved."},{"toast.generalSettingsSaved","fr","Paramètres généraux enregistrés."},{"toast.generalSettingsSaved","es","Parámetros generales guardados."},{"toast.generalSettingsSaved","de","Allgemeine Einstellungen gespeichert."},
			{"solver.help.max_solve_seconds","en","Maximum solving time (5–600 s): after the limit the best solution found is returned."},{"solver.help.max_solve_seconds","fr","Temps de calcul maximal (5–600 s) : au-delà, la meilleure solution trouvée est renvoyée."},{"solver.help.max_solve_seconds","es","Tiempo máximo de cálculo (5–600 s): superado el límite se devuelve la mejor solución encontrada."},{"solver.help.max_solve_seconds","de","Maximale Rechenzeit (5–600 s): Nach dem Limit wird die beste gefundene Lösung zurückgegeben."},
			{"solver.help.unimproved_seconds","en","Stops solving if there is no improvement for this many seconds; 0 disables early stopping."},{"solver.help.unimproved_seconds","fr","Arrête le calcul en l'absence d'amélioration pendant ce nombre de secondes ; 0 désactive l'arrêt anticipé."},{"solver.help.unimproved_seconds","es","Detiene el cálculo si no hay mejoras durante estos segundos; 0 desactiva la parada anticipada."},{"solver.help.unimproved_seconds","de","Stoppt die Berechnung, wenn für so viele Sekunden keine Verbesserung erfolgt; 0 deaktiviert den vorzeitigen Stopp."},
			{"solver.help.diminished_window_seconds","en","Observation window (seconds) for diminishing-returns stop: compares the current improvement rate with that of N seconds earlier; 0 disables it."},{"solver.help.diminished_window_seconds","fr","Fenêtre d'observation (secondes) pour l'arrêt à rendement décroissant : compare le rythme d'amélioration actuel à celui d'il y a N secondes ; 0 désactive."},{"solver.help.diminished_window_seconds","es","Ventana de observación (segundos) para la parada por rendimiento decreciente: compara el ritmo de mejora actual con el de N segundos antes; 0 la desactiva."},{"solver.help.diminished_window_seconds","de","Beobachtungsfenster (Sekunden) für den Stopp bei abnehmendem Ertrag: vergleicht die aktuelle Verbesserungsrate mit der von vor N Sekunden; 0 deaktiviert."},
			{"solver.help.diminished_ratio_pct","en","The solver stops when the improvement rate falls below this percentage of the initial rate (1–100); active only when the window is > 0."},{"solver.help.diminished_ratio_pct","fr","Le solveur s'arrête quand le rythme d'amélioration descend sous ce pourcentage du rythme initial (1–100) ; actif seulement si la fenêtre est > 0."},{"solver.help.diminished_ratio_pct","es","El solver se detiene cuando el ritmo de mejora cae por debajo de este porcentaje del ritmo inicial (1–100); solo activo con ventana > 0."},{"solver.help.diminished_ratio_pct","de","Der Solver stoppt, wenn die Verbesserungsrate unter diesen Prozentsatz der Anfangsrate fällt (1–100); nur aktiv, wenn das Fenster > 0 ist."},
			{"solver.help.context_days","en","Days adjacent to the window whose already-assigned shifts are loaded as locked context (overlap, rest, weekly hours and consecutive-day constraints see them but do not change them); 0 = window only."},{"solver.help.context_days","fr","Jours adjacents à la fenêtre dont les quarts déjà affectés sont chargés comme contexte verrouillé (les contraintes de chevauchement, repos, heures hebdomadaires et jours consécutifs les voient sans les modifier) ; 0 = fenêtre seule."},{"solver.help.context_days","es","Días adyacentes a la ventana cuyos turnos ya asignados se cargan como contexto bloqueado (las restricciones de solapamiento, descanso, horas semanales y días consecutivos los ven pero no los cambian); 0 = solo la ventana."},{"solver.help.context_days","de","An das Fenster angrenzende Tage, deren bereits zugewiesene Schichten als gesperrter Kontext geladen werden (Überschneidungs-, Ruhe-, Wochenstunden- und Folgetage-Regeln sehen sie, ändern sie aber nicht); 0 = nur Fenster."},
			{"solver.help.minimum_rest_hours","en","Minimum rest hours between the end of one shift and the start of the next for the same operator (hard constraint)."},{"solver.help.minimum_rest_hours","fr","Heures de repos minimales entre la fin d'un quart et le début du suivant pour le même opérateur (contrainte stricte)."},{"solver.help.minimum_rest_hours","es","Horas mínimas de descanso entre el fin de un turno y el inicio del siguiente del mismo operador (restricción rígida)."},{"solver.help.minimum_rest_hours","de","Mindestruhezeit zwischen dem Ende einer Schicht und dem Beginn der nächsten desselben Mitarbeiters (harte Regel)."},
			{"solver.help.max_shifts_per_day","en","Maximum number of shifts for the same operator on the same day (1–5)."},{"solver.help.max_shifts_per_day","fr","Nombre maximal de quarts pour le même opérateur le même jour (1–5)."},{"solver.help.max_shifts_per_day","es","Número máximo de turnos para el mismo operador en el mismo día (1–5)."},{"solver.help.max_shifts_per_day","de","Maximale Anzahl an Schichten für denselben Mitarbeiter am selben Tag (1–5)."},
			{"solver.help.max_weekly_hours","en","Weekly hours cap per operator; 0 disables the limit."},{"solver.help.max_weekly_hours","fr","Plafond d'heures hebdomadaires par opérateur ; 0 désactive la limite."},{"solver.help.max_weekly_hours","es","Tope de horas semanales por operador; 0 desactiva el límite."},{"solver.help.max_weekly_hours","de","Wöchentliche Stundenobergrenze pro Mitarbeiter; 0 deaktiviert das Limit."},
			{"solver.help.min_weekly_shifts","en","Minimum weekly shifts per operator; 0 disables the minimum."},{"solver.help.min_weekly_shifts","fr","Quarts hebdomadaires minimaux par opérateur ; 0 désactive le minimum."},{"solver.help.min_weekly_shifts","es","Turnos semanales mínimos por operador; 0 desactiva el mínimo."},{"solver.help.min_weekly_shifts","de","Mindestanzahl wöchentlicher Schichten pro Mitarbeiter; 0 deaktiviert das Minimum."},
			{"solver.help.max_weekly_shifts","en","Maximum weekly shifts per operator; 0 disables the maximum."},{"solver.help.max_weekly_shifts","fr","Quarts hebdomadaires maximaux par opérateur ; 0 désactive le maximum."},{"solver.help.max_weekly_shifts","es","Turnos semanales máximos por operador; 0 desactiva el máximo."},{"solver.help.max_weekly_shifts","de","Maximale Anzahl wöchentlicher Schichten pro Mitarbeiter; 0 deaktiviert das Maximum."},
			{"solver.help.max_consecutive_days","en","Maximum consecutive working days per operator; 0 disables the limit."},{"solver.help.max_consecutive_days","fr","Jours de travail consécutifs maximaux par opérateur ; 0 désactive la limite."},{"solver.help.max_consecutive_days","es","Días laborables consecutivos máximos por operador; 0 desactiva el límite."},{"solver.help.max_consecutive_days","de","Maximale aufeinanderfolgende Arbeitstage pro Mitarbeiter; 0 deaktiviert das Limit."},
			{"solver.help.min_days_off_per_week","en","Minimum days off per operator in a week (0–7)."},{"solver.help.min_days_off_per_week","fr","Jours de repos minimaux par opérateur sur une semaine (0–7)."},{"solver.help.min_days_off_per_week","es","Días de descanso mínimos por operador en una semana (0–7)."},{"solver.help.min_days_off_per_week","de","Mindestanzahl freier Tage pro Mitarbeiter in einer Woche (0–7)."},
			{"solver.help.desired_date_weight","en","How much to reward shifts on the operator's preferred dates; higher = the solver satisfies them more (0–10)."},{"solver.help.desired_date_weight","fr","Dans quelle mesure récompenser les quarts aux dates préférées de l'opérateur ; plus élevé = le solveur les satisfait davantage (0–10)."},{"solver.help.desired_date_weight","es","Cuánto premiar los turnos en las fechas preferidas del operador; mayor = el solver las satisface más (0–10)."},{"solver.help.desired_date_weight","de","Wie stark Schichten an den Wunschterminen des Mitarbeiters belohnt werden; höher = der Solver erfüllt sie stärker (0–10)."},
			{"solver.help.undesired_date_weight","en","How much to penalize shifts on the operator's unwanted dates; higher = the solver avoids them more (0–10)."},{"solver.help.undesired_date_weight","fr","Dans quelle mesure pénaliser les quarts aux dates non souhaitées de l'opérateur ; plus élevé = le solveur les évite davantage (0–10)."},{"solver.help.undesired_date_weight","es","Cuánto penalizar los turnos en las fechas no deseadas del operador; mayor = el solver las evita más (0–10)."},{"solver.help.undesired_date_weight","de","Wie stark Schichten an unerwünschten Terminen des Mitarbeiters bestraft werden; höher = der Solver vermeidet sie stärker (0–10)."},
			{"solver.help.balance_weight","en","How much fair workload distribution among operators matters (by hours or by shift count, see the dedicated option); higher = more even workloads (0–10)."},{"solver.help.balance_weight","fr","Importance d'une répartition équitable de la charge entre opérateurs (par heures ou par nombre de quarts, voir l'option dédiée) ; plus élevé = charges plus homogènes (0–10)."},{"solver.help.balance_weight","es","Cuánto importa una distribución equitativa de la carga entre operadores (por horas o por número de turnos, ver la opción dedicada); mayor = cargas más uniformes (0–10)."},{"solver.help.balance_weight","de","Wie wichtig eine faire Lastverteilung unter den Mitarbeitern ist (nach Stunden oder Schichtanzahl, siehe eigene Option); höher = gleichmäßigere Auslastung (0–10)."},
			{"solver.help.optional_skill_weight","en","How much to reward operators who also have the shift's optional skills, beyond the mandatory ones (0–10)."},{"solver.help.optional_skill_weight","fr","Dans quelle mesure récompenser les opérateurs possédant aussi les compétences facultatives du quart, en plus des obligatoires (0–10)."},{"solver.help.optional_skill_weight","es","Cuánto premiar a los operadores que también tienen las competencias opcionales del turno, además de las obligatorias (0–10)."},{"solver.help.optional_skill_weight","de","Wie stark Mitarbeiter belohnt werden, die zusätzlich zu den Pflichtkompetenzen auch die optionalen Kompetenzen der Schicht besitzen (0–10)."},
			{"solver.help.same_location_weight","en","How much to reward location continuity: the same operators at the same location on nearby days (0–10)."},{"solver.help.same_location_weight","fr","Dans quelle mesure récompenser la continuité de site : les mêmes opérateurs sur le même site les jours proches (0–10)."},{"solver.help.same_location_weight","es","Cuánto premiar la continuidad de sede: los mismos operadores en la misma sede en días cercanos (0–10)."},{"solver.help.same_location_weight","de","Wie stark Standortkontinuität belohnt wird: dieselben Mitarbeiter am selben Standort an nahen Tagen (0–10)."},
			{"solver.help.night_balance_weight","en","How much fair distribution of night shifts among operators matters (night range set by the night start/end hours) (0–10)."},{"solver.help.night_balance_weight","fr","Importance d'une répartition équitable des quarts de nuit entre opérateurs (plage de nuit définie par les heures de début/fin de nuit) (0–10)."},{"solver.help.night_balance_weight","es","Cuánto importa una distribución equitativa de los turnos nocturnos entre operadores (franja nocturna definida por las horas de inicio/fin de noche) (0–10)."},{"solver.help.night_balance_weight","de","Wie wichtig eine faire Verteilung der Nachtschichten unter den Mitarbeitern ist (Nachtzeitraum durch die Nachtbeginn-/-endstunden festgelegt) (0–10)."},
			{"solver.help.unassigned_weight","en","Penalty for each shift left uncovered; higher = the solver covers more shifts even at the expense of soft weights (1–100)."},{"solver.help.unassigned_weight","fr","Pénalité pour chaque quart laissé non couvert ; plus élevé = le solveur couvre davantage de quarts, au détriment des poids souples (1–100)."},{"solver.help.unassigned_weight","es","Penalización por cada turno sin cubrir; mayor = el solver cubre más turnos incluso a costa de los pesos blandos (1–100)."},{"solver.help.unassigned_weight","de","Strafe für jede unbesetzte Schicht; höher = der Solver besetzt mehr Schichten, auch zulasten der weichen Gewichte (1–100)."},
			{"solver.help.night_start_hour","en","Start hour of the night range (0–23), used to identify and balance night shifts."},{"solver.help.night_start_hour","fr","Heure de début de la plage de nuit (0–23), utilisée pour identifier et équilibrer les quarts de nuit."},{"solver.help.night_start_hour","es","Hora de inicio de la franja nocturna (0–23), usada para identificar y equilibrar los turnos nocturnos."},{"solver.help.night_start_hour","de","Startstunde des Nachtzeitraums (0–23), zur Identifizierung und zum Ausgleich von Nachtschichten."},
			{"solver.help.night_end_hour","en","End hour of the night range (0–23), used to identify and balance night shifts."},{"solver.help.night_end_hour","fr","Heure de fin de la plage de nuit (0–23), utilisée pour identifier et équilibrer les quarts de nuit."},{"solver.help.night_end_hour","es","Hora de fin de la franja nocturna (0–23), usada para identificar y equilibrar los turnos nocturnos."},{"solver.help.night_end_hour","de","Endstunde des Nachtzeitraums (0–23), zur Identifizierung und zum Ausgleich von Nachtschichten."},
			{"solver.help.balance_by_hours","en","When on, workload balancing considers total hours worked; otherwise it considers the number of shifts."},{"solver.help.balance_by_hours","fr","Activé, l'équilibrage de la charge prend en compte le total d'heures travaillées ; sinon le nombre de quarts."},{"solver.help.balance_by_hours","es","Si está activo, el equilibrado de carga considera el total de horas trabajadas; si no, el número de turnos."},{"solver.help.balance_by_hours","de","Wenn aktiv, berücksichtigt der Lastausgleich die gesamten Arbeitsstunden; andernfalls die Anzahl der Schichten."},
			{"solver.help.allow_unassigned","en","When on, the solver may leave shifts uncovered (with a penalty) instead of always forcing coverage."},{"solver.help.allow_unassigned","fr","Activé, le solveur peut laisser des quarts non couverts (avec pénalité) au lieu de toujours forcer la couverture."},{"solver.help.allow_unassigned","es","Si está activo, el solver puede dejar turnos sin cubrir (con penalización) en lugar de forzar siempre la cobertura."},{"solver.help.allow_unassigned","de","Wenn aktiv, darf der Solver Schichten unbesetzt lassen (mit Strafe), statt immer eine Besetzung zu erzwingen."},
			{"solver.help.stop_when_feasible","en","When on, the solver stops as soon as all hard constraints are satisfied, without further optimizing the soft weights (faster)."},{"solver.help.stop_when_feasible","fr","Activé, le solveur s'arrête dès que toutes les contraintes strictes sont respectées, sans optimiser davantage les poids souples (plus rapide)."},{"solver.help.stop_when_feasible","es","Si está activo, el solver se detiene en cuanto se cumplen todas las restricciones rígidas, sin optimizar más los pesos blandos (más rápido)."},{"solver.help.stop_when_feasible","de","Wenn aktiv, stoppt der Solver, sobald alle harten Regeln erfüllt sind, ohne die weichen Gewichte weiter zu optimieren (schneller)."},
			{"solver.group.processing","en","Processing"},{"solver.group.processing","fr","Traitement"},{"solver.group.processing","es","Procesamiento"},{"solver.group.processing","de","Verarbeitung"},
			{"solver.group.dailyWeekly","en","Daily and weekly rules"},{"solver.group.dailyWeekly","fr","Règles quotidiennes et hebdomadaires"},{"solver.group.dailyWeekly","es","Reglas diarias y semanales"},{"solver.group.dailyWeekly","de","Tägliche und wöchentliche Regeln"},
			{"solver.group.weights","en","Optimization weights"},{"solver.group.weights","fr","Poids d'optimisation"},{"solver.group.weights","es","Pesos de optimización"},{"solver.group.weights","de","Optimierungsgewichte"},
			{"solver.group.night","en","Night range"},{"solver.group.night","fr","Plage de nuit"},{"solver.group.night","es","Franja nocturna"},{"solver.group.night","de","Nachtzeitraum"},
			{"solver.group.options","en","Options"},{"solver.group.options","fr","Options"},{"solver.group.options","es","Opciones"},{"solver.group.options","de","Optionen"},
			{"solver.label.max_solve_seconds","en","Maximum duration (seconds)"},{"solver.label.max_solve_seconds","fr","Durée maximale (secondes)"},{"solver.label.max_solve_seconds","es","Duración máxima (segundos)"},{"solver.label.max_solve_seconds","de","Maximale Dauer (Sekunden)"},
			{"solver.label.unimproved_seconds","en","Stop without improvement"},{"solver.label.unimproved_seconds","fr","Arrêt sans amélioration"},{"solver.label.unimproved_seconds","es","Parada sin mejoras"},{"solver.label.unimproved_seconds","de","Stopp ohne Verbesserung"},
			{"solver.label.diminished_window_seconds","en","Diminishing-returns stop: window (s)"},{"solver.label.diminished_window_seconds","fr","Arrêt à rendement décroissant : fenêtre (s)"},{"solver.label.diminished_window_seconds","es","Parada por rendimiento decreciente: ventana (s)"},{"solver.label.diminished_window_seconds","de","Stopp bei abnehmendem Ertrag: Fenster (s)"},
			{"solver.label.diminished_ratio_pct","en","Diminishing-returns stop: threshold (%)"},{"solver.label.diminished_ratio_pct","fr","Arrêt à rendement décroissant : seuil (%)"},{"solver.label.diminished_ratio_pct","es","Parada por rendimiento decreciente: umbral (%)"},{"solver.label.diminished_ratio_pct","de","Stopp bei abnehmendem Ertrag: Schwelle (%)"},
			{"solver.label.context_days","en","Context days (window edges)"},{"solver.label.context_days","fr","Jours de contexte (bords de fenêtre)"},{"solver.label.context_days","es","Días de contexto (bordes de ventana)"},{"solver.label.context_days","de","Kontexttage (Fensterränder)"},
			{"solver.label.minimum_rest_hours","en","Minimum rest (hours)"},{"solver.label.minimum_rest_hours","fr","Repos minimal (heures)"},{"solver.label.minimum_rest_hours","es","Descanso mínimo (horas)"},{"solver.label.minimum_rest_hours","de","Mindestruhezeit (Stunden)"},
			{"solver.label.max_shifts_per_day","en","Maximum daily shifts"},{"solver.label.max_shifts_per_day","fr","Quarts quotidiens maximaux"},{"solver.label.max_shifts_per_day","es","Turnos diarios máximos"},{"solver.label.max_shifts_per_day","de","Maximale Schichten pro Tag"},
			{"solver.label.max_weekly_hours","en","Maximum weekly hours"},{"solver.label.max_weekly_hours","fr","Heures hebdomadaires maximales"},{"solver.label.max_weekly_hours","es","Horas semanales máximas"},{"solver.label.max_weekly_hours","de","Maximale Wochenstunden"},
			{"solver.label.min_weekly_shifts","en","Minimum weekly shifts"},{"solver.label.min_weekly_shifts","fr","Quarts hebdomadaires minimaux"},{"solver.label.min_weekly_shifts","es","Turnos semanales mínimos"},{"solver.label.min_weekly_shifts","de","Mindestschichten pro Woche"},
			{"solver.label.max_weekly_shifts","en","Maximum weekly shifts"},{"solver.label.max_weekly_shifts","fr","Quarts hebdomadaires maximaux"},{"solver.label.max_weekly_shifts","es","Turnos semanales máximos"},{"solver.label.max_weekly_shifts","de","Maximale Schichten pro Woche"},
			{"solver.label.max_consecutive_days","en","Maximum consecutive days"},{"solver.label.max_consecutive_days","fr","Jours consécutifs maximaux"},{"solver.label.max_consecutive_days","es","Días consecutivos máximos"},{"solver.label.max_consecutive_days","de","Maximale aufeinanderfolgende Tage"},
			{"solver.label.min_days_off_per_week","en","Minimum days off per week"},{"solver.label.min_days_off_per_week","fr","Jours de repos minimaux par semaine"},{"solver.label.min_days_off_per_week","es","Días de descanso mínimos por semana"},{"solver.label.min_days_off_per_week","de","Mindestruhetage pro Woche"},
			{"solver.label.desired_date_weight","en","Desired dates"},{"solver.label.desired_date_weight","fr","Dates souhaitées"},{"solver.label.desired_date_weight","es","Fechas deseadas"},{"solver.label.desired_date_weight","de","Wunschtermine"},
			{"solver.label.undesired_date_weight","en","Undesired dates"},{"solver.label.undesired_date_weight","fr","Dates non souhaitées"},{"solver.label.undesired_date_weight","es","Fechas no deseadas"},{"solver.label.undesired_date_weight","de","Unerwünschte Termine"},
			{"solver.label.balance_weight","en","Workload balance"},{"solver.label.balance_weight","fr","Équilibrage de la charge"},{"solver.label.balance_weight","es","Equilibrio de carga"},{"solver.label.balance_weight","de","Lastausgleich"},
			{"solver.label.optional_skill_weight","en","Optional skills"},{"solver.label.optional_skill_weight","fr","Compétences facultatives"},{"solver.label.optional_skill_weight","es","Competencias opcionales"},{"solver.label.optional_skill_weight","de","Optionale Kompetenzen"},
			{"solver.label.same_location_weight","en","Location continuity"},{"solver.label.same_location_weight","fr","Continuité de site"},{"solver.label.same_location_weight","es","Continuidad de sede"},{"solver.label.same_location_weight","de","Standortkontinuität"},
			{"solver.label.night_balance_weight","en","Night shift balance"},{"solver.label.night_balance_weight","fr","Équilibrage des nuits"},{"solver.label.night_balance_weight","es","Equilibrio de nocturnos"},{"solver.label.night_balance_weight","de","Nachtschichtausgleich"},
			{"solver.label.unassigned_weight","en","Unassigned shift penalty"},{"solver.label.unassigned_weight","fr","Pénalité de quart non affecté"},{"solver.label.unassigned_weight","es","Penalización de turno sin asignar"},{"solver.label.unassigned_weight","de","Strafe für unbesetzte Schicht"},
			{"solver.label.night_start_hour","en","Night start hour"},{"solver.label.night_start_hour","fr","Heure de début de nuit"},{"solver.label.night_start_hour","es","Hora de inicio de noche"},{"solver.label.night_start_hour","de","Nachtbeginn (Stunde)"},
			{"solver.label.night_end_hour","en","Night end hour"},{"solver.label.night_end_hour","fr","Heure de fin de nuit"},{"solver.label.night_end_hour","es","Hora de fin de noche"},{"solver.label.night_end_hour","de","Nachtende (Stunde)"},
			{"solver.hint.max_solve_seconds","en","5–600 seconds."},{"solver.hint.max_solve_seconds","fr","5–600 secondes."},{"solver.hint.max_solve_seconds","es","5–600 segundos."},{"solver.hint.max_solve_seconds","de","5–600 Sekunden."},
			{"solver.hint.unimproved_seconds","en","0 disables early stopping."},{"solver.hint.unimproved_seconds","fr","0 désactive l'arrêt anticipé."},{"solver.hint.unimproved_seconds","es","0 desactiva la parada anticipada."},{"solver.hint.unimproved_seconds","de","0 deaktiviert den vorzeitigen Stopp."},
			{"solver.hint.diminished_window_seconds","en","0 disables it. Compares the current improvement with that of N seconds earlier and stops when the rate falls below the threshold."},{"solver.hint.diminished_window_seconds","fr","0 désactive. Compare l'amélioration actuelle à celle d'il y a N secondes et s'arrête quand le rythme passe sous le seuil."},{"solver.hint.diminished_window_seconds","es","0 la desactiva. Compara la mejora actual con la de N segundos antes y se detiene cuando el ritmo cae por debajo del umbral."},{"solver.hint.diminished_window_seconds","de","0 deaktiviert. Vergleicht die aktuelle Verbesserung mit der von vor N Sekunden und stoppt, wenn die Rate unter die Schwelle fällt."},
			{"solver.hint.diminished_ratio_pct","en","1–100: percentage of the initial improvement rate below which to stop. Used only with window > 0."},{"solver.hint.diminished_ratio_pct","fr","1–100 : pourcentage du rythme d'amélioration initial sous lequel s'arrêter. Utilisé uniquement avec fenêtre > 0."},{"solver.hint.diminished_ratio_pct","es","1–100: porcentaje del ritmo de mejora inicial por debajo del cual detenerse. Solo con ventana > 0."},{"solver.hint.diminished_ratio_pct","de","1–100: Prozentsatz der anfänglichen Verbesserungsrate, unter dem gestoppt wird. Nur bei Fenster > 0."},
			{"solver.hint.context_days","en","0–7: shifts already assigned on days adjacent to the window, seen (locked) by the overlap, rest, weekly-hours and consecutive-day constraints. 0 = window only."},{"solver.hint.context_days","fr","0–7 : quarts déjà affectés les jours adjacents à la fenêtre, vus (verrouillés) par les contraintes de chevauchement, repos, heures hebdomadaires et jours consécutifs. 0 = fenêtre seule."},{"solver.hint.context_days","es","0–7: turnos ya asignados en los días adyacentes a la ventana, vistos (bloqueados) por las restricciones de solapamiento, descanso, horas semanales y días consecutivos. 0 = solo la ventana."},{"solver.hint.context_days","de","0–7: bereits zugewiesene Schichten an den an das Fenster angrenzenden Tagen, von den Überschneidungs-, Ruhe-, Wochenstunden- und Folgetage-Regeln gesehen (gesperrt). 0 = nur Fenster."},
			{"solver.hint.minimum_rest_hours","en","Minimum hours between two shifts."},{"solver.hint.minimum_rest_hours","fr","Heures minimales entre deux quarts."},{"solver.hint.minimum_rest_hours","es","Horas mínimas entre dos turnos."},{"solver.hint.minimum_rest_hours","de","Mindeststunden zwischen zwei Schichten."},
			{"solver.hint.max_shifts_per_day","en","From 1 to 5."},{"solver.hint.max_shifts_per_day","fr","De 1 à 5."},{"solver.hint.max_shifts_per_day","es","De 1 a 5."},{"solver.hint.max_shifts_per_day","de","Von 1 bis 5."},
			{"solver.hint.max_weekly_hours","en","0 disables the limit."},{"solver.hint.max_weekly_hours","fr","0 désactive la limite."},{"solver.hint.max_weekly_hours","es","0 desactiva el límite."},{"solver.hint.max_weekly_hours","de","0 deaktiviert das Limit."},
			{"solver.hint.min_weekly_shifts","en","0 disables the minimum."},{"solver.hint.min_weekly_shifts","fr","0 désactive le minimum."},{"solver.hint.min_weekly_shifts","es","0 desactiva el mínimo."},{"solver.hint.min_weekly_shifts","de","0 deaktiviert das Minimum."},
			{"solver.hint.max_weekly_shifts","en","0 disables the maximum."},{"solver.hint.max_weekly_shifts","fr","0 désactive le maximum."},{"solver.hint.max_weekly_shifts","es","0 desactiva el máximo."},{"solver.hint.max_weekly_shifts","de","0 deaktiviert das Maximum."},
			{"solver.hint.max_consecutive_days","en","0 disables the limit."},{"solver.hint.max_consecutive_days","fr","0 désactive la limite."},{"solver.hint.max_consecutive_days","es","0 desactiva el límite."},{"solver.hint.max_consecutive_days","de","0 deaktiviert das Limit."},
			{"solver.hint.min_days_off_per_week","en","From 0 to 7."},{"solver.hint.min_days_off_per_week","fr","De 0 à 7."},{"solver.hint.min_days_off_per_week","es","De 0 a 7."},{"solver.hint.min_days_off_per_week","de","Von 0 bis 7."},
			{"solver.hint.desired_date_weight","en","0–10."},{"solver.hint.desired_date_weight","fr","0–10."},{"solver.hint.desired_date_weight","es","0–10."},{"solver.hint.desired_date_weight","de","0–10."},
			{"solver.hint.undesired_date_weight","en","0–10."},{"solver.hint.undesired_date_weight","fr","0–10."},{"solver.hint.undesired_date_weight","es","0–10."},{"solver.hint.undesired_date_weight","de","0–10."},
			{"solver.hint.balance_weight","en","0–10."},{"solver.hint.balance_weight","fr","0–10."},{"solver.hint.balance_weight","es","0–10."},{"solver.hint.balance_weight","de","0–10."},
			{"solver.hint.optional_skill_weight","en","0–10."},{"solver.hint.optional_skill_weight","fr","0–10."},{"solver.hint.optional_skill_weight","es","0–10."},{"solver.hint.optional_skill_weight","de","0–10."},
			{"solver.hint.same_location_weight","en","0–10."},{"solver.hint.same_location_weight","fr","0–10."},{"solver.hint.same_location_weight","es","0–10."},{"solver.hint.same_location_weight","de","0–10."},
			{"solver.hint.night_balance_weight","en","0–10."},{"solver.hint.night_balance_weight","fr","0–10."},{"solver.hint.night_balance_weight","es","0–10."},{"solver.hint.night_balance_weight","de","0–10."},
			{"solver.hint.unassigned_weight","en","1–100."},{"solver.hint.unassigned_weight","fr","1–100."},{"solver.hint.unassigned_weight","es","1–100."},{"solver.hint.unassigned_weight","de","1–100."},
			{"solver.hint.night_start_hour","en","0–23."},{"solver.hint.night_start_hour","fr","0–23."},{"solver.hint.night_start_hour","es","0–23."},{"solver.hint.night_start_hour","de","0–23."},
			{"solver.hint.night_end_hour","en","0–23."},{"solver.hint.night_end_hour","fr","0–23."},{"solver.hint.night_end_hour","es","0–23."},{"solver.hint.night_end_hour","de","0–23."},
			{"solver.opt.balance_by_hours","en","Balance workload by hours (instead of by number of shifts)"},{"solver.opt.balance_by_hours","fr","Équilibrer la charge par heures (au lieu du nombre de quarts)"},{"solver.opt.balance_by_hours","es","Equilibrar la carga por horas (en lugar de por número de turnos)"},{"solver.opt.balance_by_hours","de","Last nach Stunden ausgleichen (statt nach Schichtanzahl)"},
			{"solver.opt.allow_unassigned","en","Allow unassigned shifts"},{"solver.opt.allow_unassigned","fr","Autoriser les quarts non affectés"},{"solver.opt.allow_unassigned","es","Permitir turnos sin asignar"},{"solver.opt.allow_unassigned","de","Unbesetzte Schichten zulassen"},
			{"solver.opt.stop_when_feasible","en","Stop at the first feasible solution"},{"solver.opt.stop_when_feasible","fr","Arrêter à la première solution réalisable"},{"solver.opt.stop_when_feasible","es","Detener en la primera solución factible"},{"solver.opt.stop_when_feasible","de","Bei der ersten machbaren Lösung stoppen"},
			{"solver.col.duration","en","Duration"},{"solver.col.duration","fr","Durée"},{"solver.col.duration","es","Duración"},{"solver.col.duration","de","Dauer"},
			{"solver.col.minRest","en","Minimum rest"},{"solver.col.minRest","fr","Repos minimal"},{"solver.col.minRest","es","Descanso mínimo"},{"solver.col.minRest","de","Mindestruhezeit"},
			{"solver.col.maxPerDay","en","Max shifts/day"},{"solver.col.maxPerDay","fr","Max quarts/jour"},{"solver.col.maxPerDay","es","Máx turnos/día"},{"solver.col.maxPerDay","de","Max. Schichten/Tag"},
			{"solver.col.balance","en","Balance"},{"solver.col.balance","fr","Équilibrage"},{"solver.col.balance","es","Equilibrio"},{"solver.col.balance","de","Ausgleich"},
			{"solver.balance.hours","en","Hours"},{"solver.balance.hours","fr","Heures"},{"solver.balance.hours","es","Horas"},{"solver.balance.hours","de","Stunden"},
			{"solver.balance.shifts","en","Shifts"},{"solver.balance.shifts","fr","Quarts"},{"solver.balance.shifts","es","Turnos"},{"solver.balance.shifts","de","Schichten"},
			{"toast.solverSettingsSaved","en","Solver settings saved."},{"toast.solverSettingsSaved","fr","Paramètres du solveur enregistrés."},{"toast.solverSettingsSaved","es","Parámetros del solver guardados."},{"toast.solverSettingsSaved","de","Solver-Einstellungen gespeichert."},
		};
		insertLabelTranslations(data);
	}

	private void seedLabelTranslations3() {
		String[][] data = {
			{"config.template.sourceWeek","en","Source week"},{"config.template.sourceWeek","fr","Semaine source"},{"config.template.sourceWeek","es","Semana de origen"},{"config.template.sourceWeek","de","Quellwoche"},
			{"config.template.prepopulate","en","Pre-populate from this week"},{"config.template.prepopulate","fr","Préremplir à partir de cette semaine"},{"config.template.prepopulate","es","Rellenar desde esta semana"},{"config.template.prepopulate","de","Aus dieser Woche vorbefüllen"},
			{"config.template.hint","en","Recurring weekly schema: click an empty area to add a shift, or a shift to edit it. It will be used to pre-populate new windows."},{"config.template.hint","fr","Schéma hebdomadaire récurrent : cliquez sur une zone vide pour ajouter un quart, ou sur un quart pour le modifier. Il servira à préremplir les nouvelles fenêtres."},{"config.template.hint","es","Esquema semanal recurrente: haga clic en un área vacía para añadir un turno, o en un turno para editarlo. Se usará para rellenar las nuevas ventanas."},{"config.template.hint","de","Wiederkehrendes Wochenschema: Klicken Sie auf einen leeren Bereich, um eine Schicht hinzuzufügen, oder auf eine Schicht, um sie zu bearbeiten. Es wird zum Vorbefüllen neuer Fenster verwendet."},
			{"config.template.hasShifts","en","Day with shifts"},{"config.template.hasShifts","fr","Jour avec quarts"},{"config.template.hasShifts","es","Día con turnos"},{"config.template.hasShifts","de","Tag mit Schichten"},
			{"config.template.daysWithShifts","en","Days with shifts"},{"config.template.daysWithShifts","fr","Jours avec quarts"},{"config.template.daysWithShifts","es","Días con turnos"},{"config.template.daysWithShifts","de","Tage mit Schichten"},
			{"confirm.applyTemplate","en","Replace the shifts in the visible period with those from the template? Existing shifts in the period will be deleted."},{"confirm.applyTemplate","fr","Remplacer les quarts de la période visible par ceux du modèle ? Les quarts existants dans la période seront supprimés."},{"confirm.applyTemplate","es","¿Sustituir los turnos del período visible por los de la plantilla? Los turnos existentes en el período se eliminarán."},{"confirm.applyTemplate","de","Die Schichten im sichtbaren Zeitraum durch die aus der Vorlage ersetzen? Vorhandene Schichten im Zeitraum werden gelöscht."},
			{"confirm.deleteDateTitle","en","Delete Date"},{"confirm.deleteDateTitle","fr","Supprimer la date"},{"confirm.deleteDateTitle","es","Eliminar fecha"},{"confirm.deleteDateTitle","de","Datum löschen"},
			{"confirm.deleteDateMessage","en","Delete this time range?"},{"confirm.deleteDateMessage","fr","Supprimer cette plage horaire ?"},{"confirm.deleteDateMessage","es","¿Eliminar esta franja horaria?"},{"confirm.deleteDateMessage","de","Diesen Zeitbereich löschen?"},
			{"confirm.deleteEmployeeMessage","en","Delete this operator?"},{"confirm.deleteEmployeeMessage","fr","Supprimer cet opérateur ?"},{"confirm.deleteEmployeeMessage","es","¿Eliminar este operador?"},{"confirm.deleteEmployeeMessage","de","Diesen Mitarbeiter löschen?"},
			{"confirm.deleteLocationMessage","en","Delete this location?"},{"confirm.deleteLocationMessage","fr","Supprimer ce lieu ?"},{"confirm.deleteLocationMessage","es","¿Eliminar esta ubicación?"},{"confirm.deleteLocationMessage","de","Diesen Standort löschen?"},
			{"confirm.deleteShiftTitle","en","Delete Shift"},{"confirm.deleteShiftTitle","fr","Supprimer le quart"},{"confirm.deleteShiftTitle","es","Eliminar turno"},{"confirm.deleteShiftTitle","de","Schicht löschen"},
			{"confirm.deleteShiftMessage","en","Delete this shift?"},{"confirm.deleteShiftMessage","fr","Supprimer ce quart ?"},{"confirm.deleteShiftMessage","es","¿Eliminar este turno?"},{"confirm.deleteShiftMessage","de","Diese Schicht löschen?"},
			{"ctx.addShift","en","Add shift"},{"ctx.addShift","fr","Ajouter un quart"},{"ctx.addShift","es","Añadir turno"},{"ctx.addShift","de","Schicht hinzufügen"},
			{"ctx.editShift","en","Edit shift"},{"ctx.editShift","fr","Modifier le quart"},{"ctx.editShift","es","Editar turno"},{"ctx.editShift","de","Schicht bearbeiten"},
			{"ctx.deleteShift","en","Delete shift"},{"ctx.deleteShift","fr","Supprimer le quart"},{"ctx.deleteShift","es","Eliminar turno"},{"ctx.deleteShift","de","Schicht löschen"},
			{"dowShort.mon","en","Mo"},{"dowShort.mon","fr","Lu"},{"dowShort.mon","es","Lu"},{"dowShort.mon","de","Mo"},
			{"dowShort.tue","en","Tu"},{"dowShort.tue","fr","Ma"},{"dowShort.tue","es","Ma"},{"dowShort.tue","de","Di"},
			{"dowShort.wed","en","We"},{"dowShort.wed","fr","Me"},{"dowShort.wed","es","Mi"},{"dowShort.wed","de","Mi"},
			{"dowShort.thu","en","Th"},{"dowShort.thu","fr","Je"},{"dowShort.thu","es","Ju"},{"dowShort.thu","de","Do"},
			{"dowShort.fri","en","Fr"},{"dowShort.fri","fr","Ve"},{"dowShort.fri","es","Vi"},{"dowShort.fri","de","Fr"},
			{"dowShort.sat","en","Sa"},{"dowShort.sat","fr","Sa"},{"dowShort.sat","es","Sa"},{"dowShort.sat","de","Sa"},
			{"dowShort.sun","en","Su"},{"dowShort.sun","fr","Di"},{"dowShort.sun","es","Do"},{"dowShort.sun","de","So"},
			{"label.dayOfWeek","en","Day"},{"label.dayOfWeek","fr","Jour"},{"label.dayOfWeek","es","Día"},{"label.dayOfWeek","de","Tag"},
			{"label.name","en","Name"},{"label.name","fr","Nom"},{"label.name","es","Nombre"},{"label.name","de","Name"},
			{"label.description","en","Description"},{"label.description","fr","Description"},{"label.description","es","Descripción"},{"label.description","de","Beschreibung"},
			{"label.key","en","Key"},{"label.key","fr","Clé"},{"label.key","es","Clave"},{"label.key","de","Schlüssel"},
			{"label.order","en","Order"},{"label.order","fr","Ordre"},{"label.order","es","Orden"},{"label.order","de","Reihenfolge"},
			{"label.operator","en","Operator"},{"label.operator","fr","Opérateur"},{"label.operator","es","Operador"},{"label.operator","de","Mitarbeiter"},
			{"label.translations","en","Translations"},{"label.translations","fr","Traductions"},{"label.translations","es","Traducciones"},{"label.translations","de","Übersetzungen"},
			{"label.violatedConstraints","en","Violated constraints"},{"label.violatedConstraints","fr","Contraintes violées"},{"label.violatedConstraints","es","Restricciones incumplidas"},{"label.violatedConstraints","de","Verletzte Bedingungen"},
			{"label.satisfiedConstraints","en","Satisfied constraints"},{"label.satisfiedConstraints","fr","Contraintes respectées"},{"label.satisfiedConstraints","es","Restricciones cumplidas"},{"label.satisfiedConstraints","de","Erfüllte Bedingungen"},
			{"modal.addShift","en","Add Shift"},{"modal.addShift","fr","Ajouter un quart"},{"modal.addShift","es","Añadir turno"},{"modal.addShift","de","Schicht hinzufügen"},
			{"modal.editShift","en","Edit Shift"},{"modal.editShift","fr","Modifier le quart"},{"modal.editShift","es","Editar turno"},{"modal.editShift","de","Schicht bearbeiten"},
			{"modal.addTemplate","en","Add template shift"},{"modal.addTemplate","fr","Ajouter un quart-modèle"},{"modal.addTemplate","es","Añadir turno de plantilla"},{"modal.addTemplate","de","Vorlagenschicht hinzufügen"},
			{"modal.editTemplate","en","Edit template shift"},{"modal.editTemplate","fr","Modifier le quart-modèle"},{"modal.editTemplate","es","Editar turno de plantilla"},{"modal.editTemplate","de","Vorlagenschicht bearbeiten"},
			{"modal.addLabel","en","Add Label"},{"modal.addLabel","fr","Ajouter une étiquette"},{"modal.addLabel","es","Añadir etiqueta"},{"modal.addLabel","de","Etikett hinzufügen"},
			{"modal.editLabel","en","Edit Label"},{"modal.editLabel","fr","Modifier l'étiquette"},{"modal.editLabel","es","Editar etiqueta"},{"modal.editLabel","de","Etikett bearbeiten"},
			{"modal.solveResult","en","Solve Result"},{"modal.solveResult","fr","Résultat de la résolution"},{"modal.solveResult","es","Resultado de la resolución"},{"modal.solveResult","de","Lösungsergebnis"},
			{"month.january","en","January"},{"month.january","fr","Janvier"},{"month.january","es","Enero"},{"month.january","de","Januar"},
			{"month.february","en","February"},{"month.february","fr","Février"},{"month.february","es","Febrero"},{"month.february","de","Februar"},
			{"month.march","en","March"},{"month.march","fr","Mars"},{"month.march","es","Marzo"},{"month.march","de","März"},
			{"month.april","en","April"},{"month.april","fr","Avril"},{"month.april","es","Abril"},{"month.april","de","April"},
			{"month.june","en","June"},{"month.june","fr","Juin"},{"month.june","es","Junio"},{"month.june","de","Juni"},
			{"month.july","en","July"},{"month.july","fr","Juillet"},{"month.july","es","Julio"},{"month.july","de","Juli"},
			{"month.august","en","August"},{"month.august","fr","Août"},{"month.august","es","Agosto"},{"month.august","de","August"},
			{"month.september","en","September"},{"month.september","fr","Septembre"},{"month.september","es","Septiembre"},{"month.september","de","September"},
			{"month.october","en","October"},{"month.october","fr","Octobre"},{"month.october","es","Octubre"},{"month.october","de","Oktober"},
			{"month.november","en","November"},{"month.november","fr","Novembre"},{"month.november","es","Noviembre"},{"month.november","de","November"},
			{"month.december","en","December"},{"month.december","fr","Décembre"},{"month.december","es","Diciembre"},{"month.december","de","Dezember"},
			{"monthShort.jan","en","Jan"},{"monthShort.jan","fr","Janv."},{"monthShort.jan","es","Ene"},{"monthShort.jan","de","Jan"},
			{"monthShort.feb","en","Feb"},{"monthShort.feb","fr","Févr."},{"monthShort.feb","es","Feb"},{"monthShort.feb","de","Feb"},
			{"monthShort.mar","en","Mar"},{"monthShort.mar","fr","Mars"},{"monthShort.mar","es","Mar"},{"monthShort.mar","de","Mär"},
			{"monthShort.apr","en","Apr"},{"monthShort.apr","fr","Avr."},{"monthShort.apr","es","Abr"},{"monthShort.apr","de","Apr"},
			{"monthShort.may","en","May"},{"monthShort.may","fr","Mai"},{"monthShort.may","es","May"},{"monthShort.may","de","Mai"},
			{"monthShort.jun","en","Jun"},{"monthShort.jun","fr","Juin"},{"monthShort.jun","es","Jun"},{"monthShort.jun","de","Jun"},
			{"monthShort.jul","en","Jul"},{"monthShort.jul","fr","Juil."},{"monthShort.jul","es","Jul"},{"monthShort.jul","de","Jul"},
			{"monthShort.aug","en","Aug"},{"monthShort.aug","fr","Août"},{"monthShort.aug","es","Ago"},{"monthShort.aug","de","Aug"},
			{"monthShort.sep","en","Sep"},{"monthShort.sep","fr","Sept."},{"monthShort.sep","es","Sep"},{"monthShort.sep","de","Sep"},
			{"monthShort.oct","en","Oct"},{"monthShort.oct","fr","Oct."},{"monthShort.oct","es","Oct"},{"monthShort.oct","de","Okt"},
			{"monthShort.nov","en","Nov"},{"monthShort.nov","fr","Nov."},{"monthShort.nov","es","Nov"},{"monthShort.nov","de","Nov"},
			{"monthShort.dec","en","Dec"},{"monthShort.dec","fr","Déc."},{"monthShort.dec","es","Dic"},{"monthShort.dec","de","Dez"},
			{"msg.assigned","en","Assigned"},{"msg.assigned","fr","Assigné"},{"msg.assigned","es","Asignado"},{"msg.assigned","de","Zugewiesen"},
			{"msg.dataNotAvailable","en","Data not available."},{"msg.dataNotAvailable","fr","Données non disponibles."},{"msg.dataNotAvailable","es","Datos no disponibles."},{"msg.dataNotAvailable","de","Daten nicht verfügbar."},
			{"msg.solvingInProgress","en","Solving in progress…"},{"msg.solvingInProgress","fr","Résolution en cours…"},{"msg.solvingInProgress","es","Resolución en curso…"},{"msg.solvingInProgress","de","Lösung läuft…"},
			{"msg.noDates","en","No dates."},{"msg.noDates","fr","Aucune date."},{"msg.noDates","es","Sin fechas."},{"msg.noDates","de","Keine Daten."},
			{"msg.noShiftsForLocation","en","No shifts for this location."},{"msg.noShiftsForLocation","fr","Aucun quart pour ce lieu."},{"msg.noShiftsForLocation","es","No hay turnos para esta ubicación."},{"msg.noShiftsForLocation","de","Keine Schichten für diesen Standort."},
			{"msg.selectStructure","en","Select a structure."},{"msg.selectStructure","fr","Sélectionnez une structure."},{"msg.selectStructure","es","Seleccione una estructura."},{"msg.selectStructure","de","Wählen Sie eine Struktur."},
			{"msg.allConstraintsSatisfied","en","All constraints satisfied ✓"},{"msg.allConstraintsSatisfied","fr","Toutes les contraintes respectées ✓"},{"msg.allConstraintsSatisfied","es","Todas las restricciones cumplidas ✓"},{"msg.allConstraintsSatisfied","de","Alle Bedingungen erfüllt ✓"},
			{"msg.constraintsViolated","en","constraint(s) violated"},{"msg.constraintsViolated","fr","contrainte(s) violée(s)"},{"msg.constraintsViolated","es","restricción(es) incumplida(s)"},{"msg.constraintsViolated","de","Bedingung(en) verletzt"},
			{"msg.noConstraintData","en","No constraint data available."},{"msg.noConstraintData","fr","Aucune donnée de contrainte disponible."},{"msg.noConstraintData","es","No hay datos de restricciones disponibles."},{"msg.noConstraintData","de","Keine Bedingungsdaten verfügbar."},
			{"nav.shifts","en","Shift Management"},{"nav.shifts","fr","Gestion des quarts"},{"nav.shifts","es","Gestión de turnos"},{"nav.shifts","de","Schichtverwaltung"},
			{"nav.shiftManagement","en","Shift Management"},{"nav.shiftManagement","fr","Gestion des quarts"},{"nav.shiftManagement","es","Gestión de turnos"},{"nav.shiftManagement","de","Schichtverwaltung"},
			{"nav.employees","en","Employees"},{"nav.employees","fr","Employés"},{"nav.employees","es","Empleados"},{"nav.employees","de","Mitarbeiter"},
			{"nav.locations","en","Locations"},{"nav.locations","fr","Lieux"},{"nav.locations","es","Ubicaciones"},{"nav.locations","de","Standorte"},
			{"nav.skills","en","Skills"},{"nav.skills","fr","Compétences"},{"nav.skills","es","Competencias"},{"nav.skills","de","Kompetenzen"},
			{"nav.dates","en","Employee Date Preferences"},{"nav.dates","fr","Préférences de dates des employés"},{"nav.dates","es","Preferencias de fechas de empleados"},{"nav.dates","de","Datumspräferenzen der Mitarbeiter"},
			{"nav.report","en","Report"},{"nav.report","fr","Rapport"},{"nav.report","es","Informe"},{"nav.report","de","Bericht"},
			{"nav.config","en","Configuration"},{"nav.config","fr","Configuration"},{"nav.config","es","Configuración"},{"nav.config","de","Konfiguration"},
			{"nav.structure","en","Structure"},{"nav.structure","fr","Structure"},{"nav.structure","es","Estructura"},{"nav.structure","de","Struktur"},
			{"table.violations","en","Violations"},{"table.violations","fr","Violations"},{"table.violations","es","Violaciones"},{"table.violations","de","Verstöße"},
			{"toast.solveCompleted","en","Solve completed!"},{"toast.solveCompleted","fr","Résolution terminée !"},{"toast.solveCompleted","es","¡Resolución completada!"},{"toast.solveCompleted","de","Lösung abgeschlossen!"},
			{"toast.errorSolverPolling","en","Error while polling the solver."},{"toast.errorSolverPolling","fr","Erreur lors de l'interrogation du solveur."},{"toast.errorSolverPolling","es","Error durante el sondeo del solver."},{"toast.errorSolverPolling","de","Fehler beim Abfragen des Solvers."},
			{"toast.errorLoadCalendar","en","Error loading the calendar."},{"toast.errorLoadCalendar","fr","Erreur lors du chargement du calendrier."},{"toast.errorLoadCalendar","es","Error al cargar el calendario."},{"toast.errorLoadCalendar","de","Fehler beim Laden des Kalenders."},
			{"toast.errorLoad","en","Error while loading."},{"toast.errorLoad","fr","Erreur lors du chargement."},{"toast.errorLoad","es","Error al cargar."},{"toast.errorLoad","de","Fehler beim Laden."},
			{"toast.errorLoadDates","en","Error loading the dates."},{"toast.errorLoadDates","fr","Erreur lors du chargement des dates."},{"toast.errorLoadDates","es","Error al cargar las fechas."},{"toast.errorLoadDates","de","Fehler beim Laden der Daten."},
			{"toast.errorSave","en","Error while saving."},{"toast.errorSave","fr","Erreur lors de l'enregistrement."},{"toast.errorSave","es","Error al guardar."},{"toast.errorSave","de","Fehler beim Speichern."},
			{"toast.errorEdit","en","Error while editing."},{"toast.errorEdit","fr","Erreur lors de la modification."},{"toast.errorEdit","es","Error durante la edición."},{"toast.errorEdit","de","Fehler bei der Bearbeitung."},
			{"toast.selectLocation","en","Select a location."},{"toast.selectLocation","fr","Sélectionnez un lieu."},{"toast.selectLocation","es","Seleccione una ubicación."},{"toast.selectLocation","de","Wählen Sie einen Standort."},
			{"toast.locationSkillsLoadFailed","en","Could not load the location's skills: check them before saving."},{"toast.locationSkillsLoadFailed","fr","Impossible de charger les compétences du lieu : vérifiez-les avant d'enregistrer."},{"toast.locationSkillsLoadFailed","es","No se pudieron cargar las competencias de la sede: verifíquelas antes de guardar."},{"toast.locationSkillsLoadFailed","de","Die Fähigkeiten des Standorts konnten nicht geladen werden: vor dem Speichern prüfen."},
			{"toast.insertStartEnd","en","Enter start and end time."},{"toast.insertStartEnd","fr","Saisissez l'heure de début et de fin."},{"toast.insertStartEnd","es","Introduzca la hora de inicio y de fin."},{"toast.insertStartEnd","de","Start- und Endzeit eingeben."},
			{"toast.invalidDateFormat","en","Invalid date/time format."},{"toast.invalidDateFormat","fr","Format de date/heure non valide."},{"toast.invalidDateFormat","es","Formato de fecha/hora no válido."},{"toast.invalidDateFormat","de","Ungültiges Datums-/Zeitformat."},
			{"toast.shiftNoCrossDay","en","A shift cannot span two days."},{"toast.shiftNoCrossDay","fr","Un quart ne peut pas couvrir deux jours."},{"toast.shiftNoCrossDay","es","Un turno no puede abarcar dos días."},{"toast.shiftNoCrossDay","de","Eine Schicht kann sich nicht über zwei Tage erstrecken."},
			{"toast.shiftUpdated","en","Shift updated!"},{"toast.shiftUpdated","fr","Quart mis à jour !"},{"toast.shiftUpdated","es","¡Turno actualizado!"},{"toast.shiftUpdated","de","Schicht aktualisiert!"},
			{"toast.shiftAdded","en","Shift added!"},{"toast.shiftAdded","fr","Quart ajouté !"},{"toast.shiftAdded","es","¡Turno añadido!"},{"toast.shiftAdded","de","Schicht hinzugefügt!"},
			{"toast.shiftDeleted","en","Shift deleted."},{"toast.shiftDeleted","fr","Quart supprimé."},{"toast.shiftDeleted","es","Turno eliminado."},{"toast.shiftDeleted","de","Schicht gelöscht."},
			{"toast.locationUpdated","en","Location updated!"},{"toast.locationUpdated","fr","Lieu mis à jour !"},{"toast.locationUpdated","es","¡Ubicación actualizada!"},{"toast.locationUpdated","de","Standort aktualisiert!"},
			{"toast.locationAdded","en","Location added!"},{"toast.locationAdded","fr","Lieu ajouté !"},{"toast.locationAdded","es","¡Ubicación añadida!"},{"toast.locationAdded","de","Standort hinzugefügt!"},
			{"toast.codeDuplicate","en","Location code already in use. Choose a different one."},{"toast.codeDuplicate","fr","Code de lieu déjà utilisé. Choisissez-en un autre."},{"toast.codeDuplicate","es","El código de ubicación ya está en uso. Elija uno diferente."},{"toast.codeDuplicate","de","Standortcode wird bereits verwendet. Wählen Sie einen anderen."},
			{"toast.structureUpdated","en","Structure updated."},{"toast.structureUpdated","fr","Structure mise à jour."},{"toast.structureUpdated","es","Estructura actualizada."},{"toast.structureUpdated","de","Struktur aktualisiert."},
			{"toast.structureAdded","en","Structure added."},{"toast.structureAdded","fr","Structure ajoutée."},{"toast.structureAdded","es","Estructura añadida."},{"toast.structureAdded","de","Struktur hinzugefügt."},
			{"toast.labelUpdated","en","Label updated."},{"toast.labelUpdated","fr","Étiquette mise à jour."},{"toast.labelUpdated","es","Etiqueta actualizada."},{"toast.labelUpdated","de","Etikett aktualisiert."},
			{"toast.labelAdded","en","Label added."},{"toast.labelAdded","fr","Étiquette ajoutée."},{"toast.labelAdded","es","Etiqueta añadida."},{"toast.labelAdded","de","Etikett hinzugefügt."},
			{"toast.keyRequired","en","The key is required."},{"toast.keyRequired","fr","La clé est obligatoire."},{"toast.keyRequired","es","La clave es obligatoria."},{"toast.keyRequired","de","Der Schlüssel ist erforderlich."},
			{"toast.descriptionRequired","en","Description is required."},{"toast.descriptionRequired","fr","La description est obligatoire."},{"toast.descriptionRequired","es","La descripción es obligatoria."},{"toast.descriptionRequired","de","Die Beschreibung ist erforderlich."},
			{"toast.datesSaved","en","Dates saved!"},{"toast.datesSaved","fr","Dates enregistrées !"},{"toast.datesSaved","es","¡Fechas guardadas!"},{"toast.datesSaved","de","Daten gespeichert!"},
			{"toast.dateDeleted","en","Date deleted."},{"toast.dateDeleted","fr","Date supprimée."},{"toast.dateDeleted","es","Fecha eliminada."},{"toast.dateDeleted","de","Datum gelöscht."},
			{"toast.templateApplied","en","Shifts created from template"},{"toast.templateApplied","fr","Quarts créés à partir du modèle"},{"toast.templateApplied","es","Turnos creados desde la plantilla"},{"toast.templateApplied","de","Schichten aus Vorlage erstellt"},
			{"toast.templateUpdated","en","Template updated!"},{"toast.templateUpdated","fr","Modèle mis à jour !"},{"toast.templateUpdated","es","¡Plantilla actualizada!"},{"toast.templateUpdated","de","Vorlage aktualisiert!"},
			{"toast.templateAdded","en","Template added!"},{"toast.templateAdded","fr","Modèle ajouté !"},{"toast.templateAdded","es","¡Plantilla añadida!"},{"toast.templateAdded","de","Vorlage hinzugefügt!"},
			{"toast.templateDeleted","en","Template deleted."},{"toast.templateDeleted","fr","Modèle supprimé."},{"toast.templateDeleted","es","Plantilla eliminada."},{"toast.templateDeleted","de","Vorlage gelöscht."},
			{"toast.templatePrepopulated","en","Template pre-populated from the selected week."},{"toast.templatePrepopulated","fr","Modèle prérempli à partir de la semaine sélectionnée."},{"toast.templatePrepopulated","es","Plantilla rellenada desde la semana seleccionada."},{"toast.templatePrepopulated","de","Vorlage aus der ausgewählten Woche vorbefüllt."},
			{"tooltip.employeeHasShifts","en","Employee has assigned shifts — cannot be deleted"},{"tooltip.employeeHasShifts","fr","L'employé a des quarts assignés — suppression impossible"},{"tooltip.employeeHasShifts","es","El empleado tiene turnos asignados — no se puede eliminar"},{"tooltip.employeeHasShifts","de","Mitarbeiter hat zugewiesene Schichten — kann nicht gelöscht werden"},
			{"tooltip.locationHasShifts","en","Location has assigned shifts — cannot be deleted"},{"tooltip.locationHasShifts","fr","Le lieu a des quarts assignés — suppression impossible"},{"tooltip.locationHasShifts","es","La ubicación tiene turnos asignados — no se puede eliminar"},{"tooltip.locationHasShifts","de","Standort hat zugewiesene Schichten — kann nicht gelöscht werden"},
			{"confirm.deleteEmployeeTitle","en","Delete Employee"},{"confirm.deleteEmployeeTitle","fr","Supprimer l'employé"},{"confirm.deleteEmployeeTitle","es","Eliminar empleado"},{"confirm.deleteEmployeeTitle","de","Mitarbeiter löschen"},
			{"confirm.deleteLocationTitle","en","Delete Location"},{"confirm.deleteLocationTitle","fr","Supprimer le lieu"},{"confirm.deleteLocationTitle","es","Eliminar ubicación"},{"confirm.deleteLocationTitle","de","Standort löschen"},
			{"confirm.deletePrefix","en","Are you sure you want to delete"},{"confirm.deletePrefix","fr","Voulez-vous vraiment supprimer"},{"confirm.deletePrefix","es","¿Seguro que desea eliminar"},{"confirm.deletePrefix","de","Möchten Sie wirklich löschen:"},
			{"msg.noEmployees","en","No employees."},{"msg.noEmployees","fr","Aucun employé."},{"msg.noEmployees","es","Sin empleados."},{"msg.noEmployees","de","Keine Mitarbeiter."},
			{"msg.noLocations","en","No locations."},{"msg.noLocations","fr","Aucun lieu."},{"msg.noLocations","es","Sin ubicaciones."},{"msg.noLocations","de","Keine Standorte."},
			{"toast.employeeDeleted","en","Employee deleted."},{"toast.employeeDeleted","fr","Employé supprimé."},{"toast.employeeDeleted","es","Empleado eliminado."},{"toast.employeeDeleted","de","Mitarbeiter gelöscht."},
			{"toast.employeeUpdated","en","Employee updated!"},{"toast.employeeUpdated","fr","Employé mis à jour !"},{"toast.employeeUpdated","es","¡Empleado actualizado!"},{"toast.employeeUpdated","de","Mitarbeiter aktualisiert!"},
			{"toast.employeeAdded","en","Employee added!"},{"toast.employeeAdded","fr","Employé ajouté !"},{"toast.employeeAdded","es","¡Empleado añadido!"},{"toast.employeeAdded","de","Mitarbeiter hinzugefügt!"},
			{"toast.employeeCodeDuplicate","en","Employee code already in use. Choose a different one."},{"toast.employeeCodeDuplicate","fr","Code d'employé déjà utilisé. Choisissez-en un autre."},{"toast.employeeCodeDuplicate","es","El código de empleado ya está en uso. Elija uno diferente."},{"toast.employeeCodeDuplicate","de","Mitarbeitercode wird bereits verwendet. Wählen Sie einen anderen."},
			{"toast.locationDeleted","en","Location deleted."},{"toast.locationDeleted","fr","Lieu supprimé."},{"toast.locationDeleted","es","Ubicación eliminada."},{"toast.locationDeleted","de","Standort gelöscht."},
			{"toast.errorDelete","en","Error while deleting."},{"toast.errorDelete","fr","Erreur lors de la suppression."},{"toast.errorDelete","es","Error al eliminar."},{"toast.errorDelete","de","Fehler beim Löschen."},
			{"toast.errorAdd","en","Error while adding."},{"toast.errorAdd","fr","Erreur lors de l'ajout."},{"toast.errorAdd","es","Error al añadir."},{"toast.errorAdd","de","Fehler beim Hinzufügen."},
			{"label.active","en","Active"},{"label.active","fr","Actif"},{"label.active","es","Activo"},{"label.active","de","Aktiv"},
			{"label.activeYes","en","Yes"},{"label.activeYes","fr","Oui"},{"label.activeYes","es","Sí"},{"label.activeYes","de","Ja"},
			{"label.inactive","en","No"},{"label.inactive","fr","Non"},{"label.inactive","es","No"},{"label.inactive","de","Nein"},
			{"hint.inactiveEmployee","en","If deactivated, it no longer appears in Shift Management and the solver won't assign shifts to them."},{"hint.inactiveEmployee","fr","S'il est désactivé, il n'apparaît plus dans la gestion des quarts et le solveur ne lui attribue aucun quart."},{"hint.inactiveEmployee","es","Si está desactivado, no aparece en la gestión de turnos y el solver no le asigna turnos."},{"hint.inactiveEmployee","de","Wenn deaktiviert, erscheint er nicht mehr in der Schichtverwaltung und der Solver weist ihm keine Schichten zu."},
			{"hint.inactiveLocation","en","If deactivated, it no longer appears in Shift Management and the solver ignores it."},{"hint.inactiveLocation","fr","Si elle est désactivée, elle n'apparaît plus dans la gestion des quarts et le solveur l'ignore."},{"hint.inactiveLocation","es","Si está desactivada, no aparece en la gestión de turnos y el solver la ignora."},{"hint.inactiveLocation","de","Wenn deaktiviert, erscheint er nicht mehr in der Schichtverwaltung und der Solver ignoriert ihn."},
			{"tooltip.today","en","Go to today"},{"tooltip.today","fr","Aller à aujourd'hui"},{"tooltip.today","es","Ir a hoy"},{"tooltip.today","de","Zu heute springen"},
			{"tooltip.prevPeriod","en","Previous period"},{"tooltip.prevPeriod","fr","Période précédente"},{"tooltip.prevPeriod","es","Período anterior"},{"tooltip.prevPeriod","de","Vorheriger Zeitraum"},
			{"tooltip.nextPeriod","en","Next period"},{"tooltip.nextPeriod","fr","Période suivante"},{"tooltip.nextPeriod","es","Período siguiente"},{"tooltip.nextPeriod","de","Nächster Zeitraum"},
			{"tooltip.applyTemplate","en","Replace the shifts in the visible period with those from the template"},{"tooltip.applyTemplate","fr","Remplacer les quarts de la période visible par ceux du modèle"},{"tooltip.applyTemplate","es","Sustituir los turnos del período visible por los de la plantilla"},{"tooltip.applyTemplate","de","Die Schichten im sichtbaren Zeitraum durch die aus der Vorlage ersetzen"},
			{"tooltip.exportJson","en","Export JSON"},{"tooltip.exportJson","fr","Exporter JSON"},{"tooltip.exportJson","es","Exportar JSON"},{"tooltip.exportJson","de","JSON exportieren"},
			// --- Additional messages ---
			{"msg.solving.banner","en","Solving shifts in progress..."},{"msg.solving.banner","fr","Résolution des quarts en cours..."},{"msg.solving.banner","es","Resolución de turnos en progreso..."},{"msg.solving.banner","de","Schichtoptimierung läuft..."},
			{"msg.unknownLocation","en","Unknown Location"},{"msg.unknownLocation","fr","Lieu inconnu"},{"msg.unknownLocation","es","Ubicación desconocida"},{"msg.unknownLocation","de","Unbekannter Ort"},
			{"msg.unassigned","en","Unassigned"},{"msg.unassigned","fr","Non assigné"},{"msg.unassigned","es","Sin asignar"},{"msg.unassigned","de","Nicht zugewiesen"},
			{"msg.unassignedShifts","en","There are {0} unassigned shifts."},{"msg.unassignedShifts","fr","Il y a {0} quarts non assignés."},{"msg.unassignedShifts","es","Hay {0} turnos sin asignar."},{"msg.unassignedShifts","de","Es gibt {0} nicht zugewiesene Schichten."},
			{"msg.error.loadLabels","en","Error loading labels."},{"msg.error.loadLabels","fr","Erreur lors du chargement des étiquettes."},{"msg.error.loadLabels","es","Error al cargar etiquetas."},{"msg.error.loadLabels","de","Fehler beim Laden der Beschriftungen."},
			{"msg.error.loadLanguages","en","Error loading languages."},{"msg.error.loadLanguages","fr","Erreur lors du chargement des langues."},{"msg.error.loadLanguages","es","Error al cargar idiomas."},{"msg.error.loadLanguages","de","Fehler beim Laden der Sprachen."},
			{"msg.error.generic","en","Error"},{"msg.error.generic","fr","Erreur"},{"msg.error.generic","es","Error"},{"msg.error.generic","de","Fehler"},
			{"msg.error.loadTranslations","en","Error loading translations"},{"msg.error.loadTranslations","fr","Erreur lors du chargement des traductions"},{"msg.error.loadTranslations","es","Error al cargar traducciones"},{"msg.error.loadTranslations","de","Fehler beim Laden der Übersetzungen"},
			{"msg.success.addLabel","en","Label added."},{"msg.success.addLabel","fr","Étiquette ajoutée."},{"msg.success.addLabel","es","Etiqueta añadida."},{"msg.success.addLabel","de","Beschriftung hinzugefügt."},
			{"msg.success.updateLabel","en","Label updated."},{"msg.success.updateLabel","fr","Étiquette mise à jour."},{"msg.success.updateLabel","es","Etiqueta actualizada."},{"msg.success.updateLabel","de","Beschriftung aktualisiert."},
			{"msg.success.deleteLabel","en","Label deleted."},{"msg.success.deleteLabel","fr","Étiquette supprimée."},{"msg.success.deleteLabel","es","Etiqueta eliminada."},{"msg.success.deleteLabel","de","Beschriftung gelöscht."},
			{"msg.success.saveTranslations","en","Translations saved."},{"msg.success.saveTranslations","fr","Traductions enregistrées."},{"msg.success.saveTranslations","es","Traducciones guardadas."},{"msg.success.saveTranslations","de","Übersetzungen gespeichert."},
			{"msg.success.addLanguage","en","Language added successfully."},{"msg.success.addLanguage","fr","Langue ajoutée avec succès."},{"msg.success.addLanguage","es","Idioma añadido con éxito."},{"msg.success.addLanguage","de","Sprache erfolgreich hinzugefügt."},
			{"msg.success.updateLanguage","en","Language updated."},{"msg.success.updateLanguage","fr","Langue mise à jour."},{"msg.success.updateLanguage","es","Idioma actualizado."},{"msg.success.updateLanguage","de","Sprache aktualisiert."},
			{"msg.success.deleteLanguage","en","Language deleted."},{"msg.success.deleteLanguage","fr","Langue supprimée."},{"msg.success.deleteLanguage","es","Idioma eliminado."},{"msg.success.deleteLanguage","de","Sprache gelöscht."},
			{"msg.success.activateLanguage","en","Active language updated."},{"msg.success.activateLanguage","fr","Langue active mise à jour."},{"msg.success.activateLanguage","es","Idioma activo actualizado."},{"msg.success.activateLanguage","de","Aktive Sprache aktualisiert."},
			{"msg.warning.labelKeyDescRequired","en","Key and description are required."},{"msg.warning.labelKeyDescRequired","fr","Clé et description sont obligatoires."},{"msg.warning.labelKeyDescRequired","es","Clave y descripción son obligatorias."},{"msg.warning.labelKeyDescRequired","de","Schlüssel und Beschreibung sind erforderlich."},
			{"msg.warning.langCodeDescRequired","en","Code and description are required."},{"msg.warning.langCodeDescRequired","fr","Code et description sont obligatoires."},{"msg.warning.langCodeDescRequired","es","Código y descripción son obligatorios."},{"msg.warning.langCodeDescRequired","de","Code und Beschreibung sind erforderlich."},
			// --- Additional confirm dialogs ---
			{"confirm.deleteLanguage","en","Delete this language?"},{"confirm.deleteLanguage","fr","Supprimer cette langue?"},{"confirm.deleteLanguage","es","¿Eliminar este idioma?"},{"confirm.deleteLanguage","de","Diese Sprache löschen?"},
			// --- Structures ---
			{"modal.manageStructures","en","Manage Structures"},{"modal.manageStructures","fr","Gestion des Structures"},{"modal.manageStructures","es","Gestión de Estructuras"},{"modal.manageStructures","de","Strukturen verwalten"},
			{"modal.addStructure","en","Add Structure"},{"modal.addStructure","fr","Ajouter Structure"},{"modal.addStructure","es","Añadir Estructura"},{"modal.addStructure","de","Struktur hinzufügen"},
			{"modal.editStructure","en","Edit Structure"},{"modal.editStructure","fr","Modifier Structure"},{"modal.editStructure","es","Editar Estructura"},{"modal.editStructure","de","Struktur bearbeiten"},
			{"label.structureName","en","Structure Name"},{"label.structureName","fr","Nom de la Structure"},{"label.structureName","es","Nombre de la Estructura"},{"label.structureName","de","Strukturname"},
			{"label.address","en","Address"},{"label.address","fr","Adresse"},{"label.address","es","Dirección"},{"label.address","de","Adresse"},
			{"label.phone","en","Phone"},{"label.phone","fr","Téléphone"},{"label.phone","es","Teléfono"},{"label.phone","de","Telefon"},
			{"placeholder.structureName","en","Enter structure name"},{"placeholder.structureName","fr","Nom de la structure"},{"placeholder.structureName","es","Nombre de la estructura"},{"placeholder.structureName","de","Strukturname eingeben"},
			{"placeholder.address","en","Enter address"},{"placeholder.address","fr","Entrer l'adresse"},{"placeholder.address","es","Ingrese dirección"},{"placeholder.address","de","Adresse eingeben"},
			{"placeholder.phone","en","Enter phone"},{"placeholder.phone","fr","Entrer le téléphone"},{"placeholder.phone","es","Ingrese teléfono"},{"placeholder.phone","de","Telefon eingeben"},
			{"table.structureName","en","Structure"},{"table.structureName","fr","Structure"},{"table.structureName","es","Estructura"},{"table.structureName","de","Struktur"},
			{"table.address","en","Address"},{"table.address","fr","Adresse"},{"table.address","es","Dirección"},{"table.address","de","Adresse"},
			{"table.phone","en","Phone"},{"table.phone","fr","Téléphone"},{"table.phone","es","Teléfono"},{"table.phone","de","Telefon"},
			{"confirm.deleteStructure","en","Delete this structure?"},{"confirm.deleteStructure","fr","Supprimer cette structure?"},{"confirm.deleteStructure","es","¿Eliminar esta estructura?"},{"confirm.deleteStructure","de","Diese Struktur löschen?"},
			{"msg.error.deleteStructure","en","Cannot delete: structure is in use."},{"msg.error.deleteStructure","fr","Impossible de supprimer: la structure est utilisée."},{"msg.error.deleteStructure","es","No se puede eliminar: la estructura está en uso."},{"msg.error.deleteStructure","de","Löschen nicht möglich: Struktur wird verwendet."},
			{"msg.error.addStructure","en","Error adding structure."},{"msg.error.addStructure","fr","Erreur lors de l'ajout de la structure."},{"msg.error.addStructure","es","Error al agregar la estructura."},{"msg.error.addStructure","de","Fehler beim Hinzufügen der Struktur."},
			{"msg.error.editStructure","en","Error editing structure."},{"msg.error.editStructure","fr","Erreur lors de la modification de la structure."},{"msg.error.editStructure","es","Error al editar la estructura."},{"msg.error.editStructure","de","Fehler beim Bearbeiten der Struktur."},
			{"msg.success.addStructure","en","Structure added successfully."},{"msg.success.addStructure","fr","Structure ajoutée avec succès."},{"msg.success.addStructure","es","Estructura añadida con éxito."},{"msg.success.addStructure","de","Struktur erfolgreich hinzugefügt."},
			{"msg.success.updateStructure","en","Structure updated."},{"msg.success.updateStructure","fr","Structure mise à jour."},{"msg.success.updateStructure","es","Estructura actualizada."},{"msg.success.updateStructure","de","Struktur aktualisiert."},
			{"msg.success.deleteStructure","en","Structure deleted."},{"msg.success.deleteStructure","fr","Structure supprimée."},{"msg.success.deleteStructure","es","Estructura eliminada."},{"msg.success.deleteStructure","de","Struktur gelöscht."},
			{"msg.warning.structureNameRequired","en","Structure name is required."},{"msg.warning.structureNameRequired","fr","Le nom de la structure est obligatoire."},{"msg.warning.structureNameRequired","es","El nombre de la estructura es obligatorio."},{"msg.warning.structureNameRequired","de","Strukturname ist erforderlich."},
			// --- Score analysis ---
			{"msg.no.score.to.analyze","en","No score to analyze yet, please first press the 'Solve' button."},{"msg.no.score.to.analyze","fr","Aucun score à analyser, veuillez d'abord appuyer sur 'Résoudre'."},{"msg.no.score.to.analyze","es","Sin score para analizar. Pulse primero el botón 'Resolver'."},{"msg.no.score.to.analyze","de","Kein Score zum Analysieren. Bitte zuerst 'Lösen' drücken."},
			{"constraint.missing_required_skill","en","Missing required skill"},{"constraint.missing_required_skill","fr","Compétence requise manquante"},{"constraint.missing_required_skill","es","Competencia requerida faltante"},{"constraint.missing_required_skill","de","Erforderliche Fähigkeit fehlt"},
			{"constraint.overlapping_shift","en","Overlapping shift"},{"constraint.overlapping_shift","fr","Quarts qui se chevauchent"},{"constraint.overlapping_shift","es","Turnos superpuestos"},{"constraint.overlapping_shift","de","Überlappende Schicht"},
			{"constraint.at_least_10_hours_between_2_shifts","en","At least 10 hours between 2 shifts"},{"constraint.at_least_10_hours_between_2_shifts","fr","Au moins 10 heures entre 2 quarts"},{"constraint.at_least_10_hours_between_2_shifts","es","Al menos 10 horas entre 2 turnos"},{"constraint.at_least_10_hours_between_2_shifts","de","Mindestens 10 Stunden zwischen 2 Schichten"},
			{"constraint.max_one_shift_per_day","en","Max one shift per day"},{"constraint.max_one_shift_per_day","fr","Maximum un quart par jour"},{"constraint.max_one_shift_per_day","es","Máximo un turno por día"},{"constraint.max_one_shift_per_day","de","Maximal eine Schicht pro Tag"},
			{"constraint.unavailable_employee","en","Unavailable employee"},{"constraint.unavailable_employee","fr","Employé indisponible"},{"constraint.unavailable_employee","es","Empleado no disponible"},{"constraint.unavailable_employee","de","Nicht verfügbarer Mitarbeiter"},
			{"constraint.undesired_day_for_employee","en","Undesired day for employee"},{"constraint.undesired_day_for_employee","fr","Jour indésirable pour l'employé"},{"constraint.undesired_day_for_employee","es","Día no deseado para el empleado"},{"constraint.undesired_day_for_employee","de","Unerwünschter Tag für Mitarbeiter"},
			{"constraint.desired_day_for_employee","en","Desired day for employee"},{"constraint.desired_day_for_employee","fr","Jour souhaité pour l'employé"},{"constraint.desired_day_for_employee","es","Día deseado para el empleado"},{"constraint.desired_day_for_employee","de","Gewünschter Tag für Mitarbeiter"},
			{"constraint.balance_employee_shift_assignments","en","Balance employee shift assignments"},{"constraint.balance_employee_shift_assignments","fr","Équilibrage des affectations de quarts"},{"constraint.balance_employee_shift_assignments","es","Equilibrio de asignaciones de turnos"},{"constraint.balance_employee_shift_assignments","de","Ausgleich der Schichtzuweisungen"},
			// --- Pagination ---
			{"pagination.labels","en","labels"},{"pagination.labels","fr","étiquettes"},{"pagination.labels","es","etiquetas"},{"pagination.labels","de","Beschriftungen"},
			{"pagination.page","en","Page"},{"pagination.page","fr","Page"},{"pagination.page","es","Pág."},{"pagination.page","de","Seite"},
			{"pagination.results","en","results"},{"pagination.results","fr","résultats"},{"pagination.results","es","resultados"},{"pagination.results","de","Ergebnisse"},
		};

		String sql =
			"INSERT OR IGNORE INTO localizzazioni (entity_type, entity_id, field_name, language_id, value) " +
			"SELECT 'labels', l.id, 'value', lg.id, ? " +
			"FROM labels l, languages lg WHERE l.key=? AND lg.code=?;";

		try (Connection conn = DatabaseConnection.connect(dbName);
			 PreparedStatement stmt = conn.prepareStatement(sql)) {
			for (String[] row : data) {
				stmt.setString(1, row[2]); // value
				stmt.setString(2, row[0]); // labelKey
				stmt.setString(3, row[1]); // langCode
				stmt.addBatch();
			}
			stmt.executeBatch();
		} catch (Exception e) {
			logger.log(Level.SEVERE, "Error seeding label translations", e);
		}
	}

	/** Seeds UI translations added after the original translation catalog. */
	private void seedAdditionalLabelTranslations() {
		String[][] data = {
			{"label.email","en","Email"},{"label.email","fr","E-mail"},{"label.email","es","Correo electrónico"},{"label.email","de","E-Mail"},
			{"placeholder.email","en","name@example.com"},{"placeholder.email","fr","nom@exemple.fr"},{"placeholder.email","es","nombre@ejemplo.es"},{"placeholder.email","de","name@beispiel.de"},
			{"validation.emailInvalid","en","Enter a valid email address."},{"validation.emailInvalid","fr","Saisissez une adresse e-mail valide."},{"validation.emailInvalid","es","Introduce una dirección de correo válida."},{"validation.emailInvalid","de","Geben Sie eine gültige E-Mail-Adresse ein."},
			{"config.menu.general","en","General settings"},{"config.menu.general","fr","Paramètres généraux"},{"config.menu.general","es","Parámetros generales"},{"config.menu.general","de","Allgemeine Einstellungen"},
			{"config.menu.solverSettings","en","Solver Settings"},{"config.menu.solverSettings","fr","Paramètres du solveur"},{"config.menu.solverSettings","es","Parámetros del solver"},{"config.menu.solverSettings","de","Solver-Einstellungen"},
			{"config.menu.solverSettings.tooltip","en","Configure optimization engine settings"},{"config.menu.solverSettings.tooltip","fr","Configurer les paramètres du moteur d’optimisation"},{"config.menu.solverSettings.tooltip","es","Configurar los parámetros del motor de optimización"},{"config.menu.solverSettings.tooltip","de","Einstellungen der Optimierungs-Engine konfigurieren"},
			{"solverSettings.intro","en","Optimization settings for the selected company."},{"solverSettings.intro","fr","Paramètres d’optimisation pour l’entreprise sélectionnée."},{"solverSettings.intro","es","Parámetros de optimización para la empresa seleccionada."},{"solverSettings.intro","de","Optimierungseinstellungen für das ausgewählte Unternehmen."},
			{"config.menu.localizations","en","Localizations"},{"config.menu.localizations","fr","Localisations"},{"config.menu.localizations","es","Localizaciones"},{"config.menu.localizations","de","Lokalisierungen"},
			{"config.menu.templates","en","Shift templates"},{"config.menu.templates","fr","Modèles de quarts"},{"config.menu.templates","es","Plantillas de turnos"},{"config.menu.templates","de","Schichtvorlagen"},
			{"config.menu.structures","en","Structures"},{"config.menu.structures","fr","Structures"},{"config.menu.structures","es","Estructuras"},{"config.menu.structures","de","Strukturen"},
			{"config.menu.skills","en","Skills"},{"config.menu.skills","fr","Compétences"},{"config.menu.skills","es","Competencias"},{"config.menu.skills","de","Kompetenzen"},
			{"config.menu.general.tooltip","en","Configure general settings"},{"config.menu.general.tooltip","fr","Configurer les paramètres généraux"},{"config.menu.general.tooltip","es","Configurar los parámetros generales"},{"config.menu.general.tooltip","de","Allgemeine Einstellungen konfigurieren"},
			{"config.menu.localizations.tooltip","en","Manage labels and translations"},{"config.menu.localizations.tooltip","fr","Gérer les libellés et les traductions"},{"config.menu.localizations.tooltip","es","Gestionar etiquetas y traducciones"},{"config.menu.localizations.tooltip","de","Beschriftungen und Übersetzungen verwalten"},
			{"config.menu.templates.tooltip","en","Configure shift templates"},{"config.menu.templates.tooltip","fr","Configurer les modèles de quarts"},{"config.menu.templates.tooltip","es","Configurar las plantillas de turnos"},{"config.menu.templates.tooltip","de","Schichtvorlagen konfigurieren"},
			{"config.menu.structures.tooltip","en","Manage organizational structures"},{"config.menu.structures.tooltip","fr","Gérer les structures organisationnelles"},{"config.menu.structures.tooltip","es","Gestionar las estructuras organizativas"},{"config.menu.structures.tooltip","de","Organisationsstrukturen verwalten"},
			{"config.menu.skills.tooltip","en","Manage skills"},{"config.menu.skills.tooltip","fr","Gérer les compétences"},{"config.menu.skills.tooltip","es","Gestionar las competencias"},{"config.menu.skills.tooltip","de","Kompetenzen verwalten"},
			{"skills.namesRequired","en","All names are required."},{"skills.namesRequired","fr","Tous les noms sont obligatoires."},{"skills.namesRequired","es","Todos los nombres son obligatorios."},{"skills.namesRequired","de","Alle Namen müssen ausgefüllt werden."},
			{"skills.saved","en","Skills saved!"},{"skills.saved","fr","Compétences enregistrées !"},{"skills.saved","es","¡Competencias guardadas!"},{"skills.saved","de","Kompetenzen gespeichert!"},
			{"skills.deleted","en","Skill deleted."},{"skills.deleted","fr","Compétence supprimée."},{"skills.deleted","es","Competencia eliminada."},{"skills.deleted","de","Kompetenz gelöscht."},
			{"skills.addTooltip","en","Add a skill"},{"skills.addTooltip","fr","Ajouter une compétence"},{"skills.addTooltip","es","Añadir una competencia"},{"skills.addTooltip","de","Kompetenz hinzufügen"},
			{"skills.new","en","New"},{"skills.new","fr","Nouvelle"},{"skills.new","es","Nueva"},{"skills.new","de","Neu"},
			{"skills.noResults","en","No skills."},{"skills.noResults","fr","Aucune compétence."},{"skills.noResults","es","No hay competencias."},{"skills.noResults","de","Keine Kompetenzen."},
			{"skills.newRow","en","New"},{"skills.newRow","fr","Nouveau"},{"skills.newRow","es","Nuevo"},{"skills.newRow","de","Neu"},
			{"skills.namePlaceholder","en","Skill name"},{"skills.namePlaceholder","fr","Nom de la compétence"},{"skills.namePlaceholder","es","Nombre de la competencia"},{"skills.namePlaceholder","de","Name der Kompetenz"},
			{"skills.inUseTooltip","en","Skill in use — cannot be deleted"},{"skills.inUseTooltip","fr","Compétence utilisée — suppression impossible"},{"skills.inUseTooltip","es","Competencia en uso — no se puede eliminar"},{"skills.inUseTooltip","de","Kompetenz wird verwendet — Löschen nicht möglich"},
			{"skills.deleteTitle","en","Delete skill"},{"skills.deleteTitle","fr","Supprimer la compétence"},{"skills.deleteTitle","es","Eliminar competencia"},{"skills.deleteTitle","de","Kompetenz löschen"},
			{"modal.addSkill","en","Add skill"},{"modal.addSkill","fr","Ajouter une compétence"},{"modal.addSkill","es","Añadir competencia"},{"modal.addSkill","de","Kompetenz hinzufügen"},
			{"modal.editSkill","en","Edit skill"},{"modal.editSkill","fr","Modifier la compétence"},{"modal.editSkill","es","Editar competencia"},{"modal.editSkill","de","Kompetenz bearbeiten"},
			{"structures.loadError","en","Error loading structures."},{"structures.loadError","fr","Erreur lors du chargement des structures."},{"structures.loadError","es","Error al cargar las estructuras."},{"structures.loadError","de","Fehler beim Laden der Strukturen."},
			{"structures.addTooltip","en","Add a new structure"},{"structures.addTooltip","fr","Ajouter une nouvelle structure"},{"structures.addTooltip","es","Añadir una nueva estructura"},{"structures.addTooltip","de","Neue Struktur hinzufügen"},
			{"structures.noResults","en","No structures."},{"structures.noResults","fr","Aucune structure."},{"structures.noResults","es","No hay estructuras."},{"structures.noResults","de","Keine Strukturen."},
			{"structures.active","en","Active"},{"structures.active","fr","Active"},{"structures.active","es","Activa"},{"structures.active","de","Aktiv"},
			{"structures.defaultDeleteTooltip","en","The default structure cannot be deleted"},{"structures.defaultDeleteTooltip","fr","La structure par défaut ne peut pas être supprimée"},{"structures.defaultDeleteTooltip","es","La estructura predeterminada no se puede eliminar"},{"structures.defaultDeleteTooltip","de","Die Standardstruktur kann nicht gelöscht werden"},
			{"structures.deleteTitle","en","Delete structure"},{"structures.deleteTitle","fr","Supprimer la structure"},{"structures.deleteTitle","es","Eliminar estructura"},{"structures.deleteTitle","de","Struktur löschen"},
			{"labels.addTooltip","en","Add a new label"},{"labels.addTooltip","fr","Ajouter un nouveau libellé"},{"labels.addTooltip","es","Añadir una nueva etiqueta"},{"labels.addTooltip","de","Neue Beschriftung hinzufügen"},
			{"labels.searchPlaceholder","en","Search by key or description…"},{"labels.searchPlaceholder","fr","Rechercher par clé ou description…"},{"labels.searchPlaceholder","es","Buscar por clave o descripción…"},{"labels.searchPlaceholder","de","Nach Schlüssel oder Beschreibung suchen…"},
			{"labels.results","en","{{count}} result(s)"},{"labels.results","fr","{{count}} résultat(s)"},{"labels.results","es","{{count}} resultado(s)"},{"labels.results","de","{{count}} Ergebnis(se)"},
			{"labels.noResults","en","No labels found."},{"labels.noResults","fr","Aucun libellé trouvé."},{"labels.noResults","es","No se encontraron etiquetas."},{"labels.noResults","de","Keine Beschriftungen gefunden."},
			{"labels.deleteTitle","en","Delete label"},{"labels.deleteTitle","fr","Supprimer le libellé"},{"labels.deleteTitle","es","Eliminar etiqueta"},{"labels.deleteTitle","de","Beschriftung löschen"},
			{"labels.showTranslationKeys","en","Show keys"},{"labels.showTranslationKeys","fr","Afficher les clés"},{"labels.showTranslationKeys","es","Mostrar claves"},{"labels.showTranslationKeys","de","Schlüssel anzeigen"},
			{"tooltip.showTranslationKeys","en","Show translation keys"},{"tooltip.showTranslationKeys","fr","Afficher les clés de traduction"},{"tooltip.showTranslationKeys","es","Mostrar claves de traducción"},{"tooltip.showTranslationKeys","de","Übersetzungsschlüssel anzeigen"},
			{"tooltip.hideTranslationKeys","en","Hide translation keys"},{"tooltip.hideTranslationKeys","fr","Masquer les clés de traduction"},{"tooltip.hideTranslationKeys","es","Ocultar claves de traducción"},{"tooltip.hideTranslationKeys","de","Übersetzungsschlüssel ausblenden"},
			{"hint.skillTranslationFallback","en","Languages left empty will use the base Name."},{"hint.skillTranslationFallback","fr","Les langues laissées vides utiliseront le Nom de base."},{"hint.skillTranslationFallback","es","Los idiomas vacíos usarán el Nombre base."},{"hint.skillTranslationFallback","de","Leere Sprachen verwenden den Basisnamen."},
			// --- Shift Templates section: description + window ---
			{"col.company","en","Company"},{"col.company","fr","Entreprise"},{"col.company","es","Empresa"},{"col.company","de","Unternehmen"},
			{"config.template.shiftsConfigured","en","Configured shifts"},{"config.template.shiftsConfigured","fr","Quarts configurés"},{"config.template.shiftsConfigured","es","Turnos configurados"},{"config.template.shiftsConfigured","de","Konfigurierte Schichten"},
			{"config.template.window","en","Window"},{"config.template.window","fr","Fenêtre"},{"config.template.window","es","Ventana"},{"config.template.window","de","Fenster"},
			{"config.template.window.hint","en","Granularity set in General settings."},{"config.template.window.hint","fr","Granularité définie dans les Paramètres généraux."},{"config.template.window.hint","es","Granularidad definida en Parámetros generales."},{"config.template.window.hint","de","Granularität wird in den Allgemeinen Einstellungen festgelegt."},
			{"placeholder.templateDescription","en","Template description…"},{"placeholder.templateDescription","fr","Description du modèle…"},{"placeholder.templateDescription","es","Descripción de la plantilla…"},{"placeholder.templateDescription","de","Beschreibung der Vorlage…"},
			{"toast.templateDescSaved","en","Description saved!"},{"toast.templateDescSaved","fr","Description enregistrée !"},{"toast.templateDescSaved","es","¡Descripción guardada!"},{"toast.templateDescSaved","de","Beschreibung gespeichert!"},
			// --- Shift Management: save week as template + load from list ---
			{"btn.saveToTemplate","en","Save to template"},{"btn.saveToTemplate","fr","Enregistrer dans le modèle"},{"btn.saveToTemplate","es","Guardar en plantilla"},{"btn.saveToTemplate","de","In Vorlage speichern"},
			{"tooltip.saveToTemplate","en","Save the visible week's shifts as a new template (without assigned operators)"},{"tooltip.saveToTemplate","fr","Enregistrer les quarts de la semaine visible comme nouveau modèle (sans opérateurs affectés)"},{"tooltip.saveToTemplate","es","Guardar los turnos de la semana visible como nueva plantilla (sin operadores asignados)"},{"tooltip.saveToTemplate","de","Die Schichten der sichtbaren Woche als neue Vorlage speichern (ohne zugewiesene Operatoren)"},
			{"tooltip.saveToTemplateWeekOnly","en","Available only in weekly view"},{"tooltip.saveToTemplateWeekOnly","fr","Disponible uniquement en vue hebdomadaire"},{"tooltip.saveToTemplateWeekOnly","es","Disponible solo en vista semanal"},{"tooltip.saveToTemplateWeekOnly","de","Nur in der Wochenansicht verfügbar"},
			{"modal.saveTemplate","en","Save to template"},{"modal.saveTemplate","fr","Enregistrer dans le modèle"},{"modal.saveTemplate","es","Guardar en plantilla"},{"modal.saveTemplate","de","In Vorlage speichern"},
			{"modal.loadTemplate","en","Load from template"},{"modal.loadTemplate","fr","Charger depuis un modèle"},{"modal.loadTemplate","es","Cargar desde plantilla"},{"modal.loadTemplate","de","Aus Vorlage laden"},
			{"hint.saveTemplateNoOperators","en","Only the per-location shifts are saved, without the assigned operators."},{"hint.saveTemplateNoOperators","fr","Seuls les quarts par site sont enregistrés, sans les opérateurs affectés."},{"hint.saveTemplateNoOperators","es","Solo se guardan los turnos por sede, sin los operadores asignados."},{"hint.saveTemplateNoOperators","de","Es werden nur die Schichten pro Standort gespeichert, ohne die zugewiesenen Operatoren."},
			{"hint.loadTemplateReplaces","en","Applying a template will replace the shifts in the visible period."},{"hint.loadTemplateReplaces","fr","L'application d'un modèle remplacera les quarts de la période visible."},{"hint.loadTemplateReplaces","es","Al aplicar una plantilla se sustituirán los turnos del período visible."},{"hint.loadTemplateReplaces","de","Beim Anwenden einer Vorlage werden die Schichten im sichtbaren Zeitraum ersetzt."},
			{"savedTemplates.empty","en","No saved templates."},{"savedTemplates.empty","fr","Aucun modèle enregistré."},{"savedTemplates.empty","es","No hay plantillas guardadas."},{"savedTemplates.empty","de","Keine gespeicherten Vorlagen."},
			{"col.createdAt","en","Created on"},{"col.createdAt","fr","Créé le"},{"col.createdAt","es","Creado el"},{"col.createdAt","de","Erstellt am"},
			{"btn.apply","en","Apply"},{"btn.apply","fr","Appliquer"},{"btn.apply","es","Aplicar"},{"btn.apply","de","Anwenden"},
			{"confirm.deleteSavedTemplate","en","Delete this saved template?"},{"confirm.deleteSavedTemplate","fr","Supprimer ce modèle enregistré ?"},{"confirm.deleteSavedTemplate","es","¿Eliminar esta plantilla guardada?"},{"confirm.deleteSavedTemplate","de","Diese gespeicherte Vorlage löschen?"},
			{"toast.savedTemplateAdded","en","Template saved!"},{"toast.savedTemplateAdded","fr","Modèle enregistré !"},{"toast.savedTemplateAdded","es","¡Plantilla guardada!"},{"toast.savedTemplateAdded","de","Vorlage gespeichert!"},
			{"toast.savedTemplateDeleted","en","Template deleted."},{"toast.savedTemplateDeleted","fr","Modèle supprimé."},{"toast.savedTemplateDeleted","es","Plantilla eliminada."},{"toast.savedTemplateDeleted","de","Vorlage gelöscht."},
			{"config.template.workingSchema","en","Working weekly schema"},{"config.template.workingSchema","fr","Schéma hebdomadaire de travail"},{"config.template.workingSchema","es","Esquema semanal de trabajo"},{"config.template.workingSchema","de","Wöchentliches Arbeitsschema"},
			{"config.template.listHint","en","List of templates saved from Shift Management. Use the pencil to edit description and shifts."},{"config.template.listHint","fr","Liste des modèles enregistrés depuis la Gestion des quarts. Utilisez le crayon pour modifier la description et les quarts."},{"config.template.listHint","es","Lista de plantillas guardadas desde Gestión de Turnos. Usa el lápiz para editar descripción y turnos."},{"config.template.listHint","de","Liste der aus der Schichtverwaltung gespeicherten Vorlagen. Mit dem Stift Beschreibung und Schichten bearbeiten."},
			{"modal.editSavedTemplate","en","Edit template"},{"modal.editSavedTemplate","fr","Modifier le modèle"},{"modal.editSavedTemplate","es","Editar plantilla"},{"modal.editSavedTemplate","de","Vorlage bearbeiten"},
			{"tooltip.openSolverSettings","en","Open Solver Settings in a new tab"},{"tooltip.openSolverSettings","fr","Ouvrir les paramètres du solveur dans un nouvel onglet"},{"tooltip.openSolverSettings","es","Abrir los parámetros del solver en una nueva pestaña"},{"tooltip.openSolverSettings","de","Solver-Einstellungen in einem neuen Tab öffnen"},
			{"hint.clickConstraintForSettings","en","Click a constraint to open Solver Settings in a new tab."},{"hint.clickConstraintForSettings","fr","Cliquez sur une contrainte pour ouvrir les paramètres du solveur dans un nouvel onglet."},{"hint.clickConstraintForSettings","es","Haz clic en una restricción para abrir los parámetros del solver en una nueva pestaña."},{"hint.clickConstraintForSettings","de","Klicken Sie auf eine Bedingung, um die Solver-Einstellungen in einem neuen Tab zu öffnen."},
			{"msg.infeasibleSaveWarning","en","The solution violates hard constraints: saving it will write those violations into the shifts. Press \"Save anyway\" to confirm."},{"msg.infeasibleSaveWarning","fr","La solution viole des contraintes strictes : en l'enregistrant, ces violations seront écrites dans les quarts. Appuyez sur « Enregistrer quand même » pour confirmer."},{"msg.infeasibleSaveWarning","es","La solución viola restricciones estrictas: al guardarla, esas violaciones se escribirán en los turnos. Pulsa \"Guardar de todos modos\" para confirmar."},{"msg.infeasibleSaveWarning","de","Die Lösung verletzt harte Bedingungen: Beim Speichern werden diese Verstöße in die Schichten geschrieben. Zum Bestätigen \"Trotzdem speichern\" drücken."},
			{"btn.saveAnyway","en","Save anyway"},{"btn.saveAnyway","fr","Enregistrer quand même"},{"btn.saveAnyway","es","Guardar de todos modos"},{"btn.saveAnyway","de","Trotzdem speichern"},
			// --- System Info section (Configuration → Info) ---
			{"config.menu.info","en","System Info"},{"config.menu.info","fr","Infos système"},{"config.menu.info","es","Info del sistema"},{"config.menu.info","de","Systeminfo"},
			{"config.menu.info.tooltip","en","System versions and information"},{"config.menu.info.tooltip","fr","Versions et informations système"},{"config.menu.info.tooltip","es","Versiones e información del sistema"},{"config.menu.info.tooltip","de","Systemversionen und -informationen"},
			{"config.info.appTitle","en","Application"},{"config.info.appTitle","fr","Application"},{"config.info.appTitle","es","Aplicación"},{"config.info.appTitle","de","Anwendung"},
			{"config.info.mainTitle","en","Main components"},{"config.info.mainTitle","fr","Composants principaux"},{"config.info.mainTitle","es","Componentes principales"},{"config.info.mainTitle","de","Hauptkomponenten"},
			{"config.info.description","en","Versions of the application's main components."},{"config.info.description","fr","Versions des principaux composants de l'application."},{"config.info.description","es","Versiones de los componentes principales de la aplicación."},{"config.info.description","de","Versionen der Hauptkomponenten der Anwendung."},
			{"config.info.checkUpdates","en","Check for updates"},{"config.info.checkUpdates","fr","Vérifier les mises à jour"},{"config.info.checkUpdates","es","Buscar actualizaciones"},{"config.info.checkUpdates","de","Nach Updates suchen"},
			{"config.info.error","en","Unable to load backend information."},{"config.info.error","fr","Impossible de charger les informations du backend."},{"config.info.error","es","No se pudo cargar la información del backend."},{"config.info.error","de","Backend-Informationen konnten nicht geladen werden."},
			{"config.info.component","en","Component"},{"config.info.component","fr","Composant"},{"config.info.component","es","Componente"},{"config.info.component","de","Komponente"},
			{"config.info.version","en","Version"},{"config.info.version","fr","Version"},{"config.info.version","es","Versión"},{"config.info.version","de","Version"},
			{"config.info.updates","en","Updates"},{"config.info.updates","fr","Mises à jour"},{"config.info.updates","es","Actualizaciones"},{"config.info.updates","de","Aktualisierungen"},
			{"config.info.unavailable","en","Unavailable"},{"config.info.unavailable","fr","Non disponible"},{"config.info.unavailable","es","No disponible"},{"config.info.unavailable","de","Nicht verfügbar"},
			{"config.info.updateAvailable","en","Available"},{"config.info.updateAvailable","fr","Disponible"},{"config.info.updateAvailable","es","Disponible"},{"config.info.updateAvailable","de","Verfügbar"},
			{"config.info.upToDate","en","Up to date"},{"config.info.upToDate","fr","À jour"},{"config.info.upToDate","es","Actualizado"},{"config.info.upToDate","de","Aktuell"},
			{"config.info.secondaryTitle","en","Secondary libraries and components"},{"config.info.secondaryTitle","fr","Bibliothèques et composants secondaires"},{"config.info.secondaryTitle","es","Bibliotecas y componentes secundarios"},{"config.info.secondaryTitle","de","Sekundäre Bibliotheken und Komponenten"},
			// --- Complete missing localizations (2026-07-13 audit): backups ---
			{"backup.autoRetentionDays","en","Automatic backups retention"},{"backup.autoRetentionDays","fr","Conservation des sauvegardes automatiques"},{"backup.autoRetentionDays","es","Retención de copias automáticas"},{"backup.autoRetentionDays","de","Aufbewahrung automatischer Backups"},
			{"backup.days","en","days"},{"backup.days","fr","jours"},{"backup.days","es","días"},{"backup.days","de","Tage"},
			{"backup.delete","en","Delete this backup"},{"backup.delete","fr","Supprimer cette sauvegarde"},{"backup.delete","es","Eliminar esta copia de seguridad"},{"backup.delete","de","Dieses Backup löschen"},
			{"backup.deleteConfirm","en","Delete"},{"backup.deleteConfirm","fr","Supprimer"},{"backup.deleteConfirm","es","Eliminar"},{"backup.deleteConfirm","de","Löschen"},
			{"backup.deleteMsg","en","Permanently delete this backup?"},{"backup.deleteMsg","fr","Supprimer définitivement cette sauvegarde ?"},{"backup.deleteMsg","es","¿Eliminar definitivamente esta copia de seguridad?"},{"backup.deleteMsg","de","Dieses Backup endgültig löschen?"},
			{"backup.deleteTitle","en","Delete backup"},{"backup.deleteTitle","fr","Supprimer la sauvegarde"},{"backup.deleteTitle","es","Eliminar copia de seguridad"},{"backup.deleteTitle","de","Backup löschen"},
			{"backup.intervalMinutes","en","Automatic interval"},{"backup.intervalMinutes","fr","Intervalle automatique"},{"backup.intervalMinutes","es","Intervalo automático"},{"backup.intervalMinutes","de","Automatisches Intervall"},
			{"backup.otherRetentionDays","en","Other backups retention"},{"backup.otherRetentionDays","fr","Conservation des autres sauvegardes"},{"backup.otherRetentionDays","es","Retención de otras copias"},{"backup.otherRetentionDays","de","Aufbewahrung sonstiger Backups"},
			{"backup.rotationHint","en","Files older than the specified days are deleted on rotation; the maximum backup count limits also remain active."},{"backup.rotationHint","fr","Les fichiers plus anciens que le nombre de jours indiqué sont supprimés lors de la rotation ; les limites maximales par nombre de sauvegardes restent également actives."},{"backup.rotationHint","es","Los archivos más antiguos que los días indicados se eliminan en la rotación; también siguen activos los límites máximos por número de copias."},{"backup.rotationHint","de","Dateien, die älter als die angegebenen Tage sind, werden bei der Rotation gelöscht; die Höchstgrenzen für die Anzahl der Backups bleiben ebenfalls aktiv."},
			{"backup.rotationSettings","en","Automatic rotation"},{"backup.rotationSettings","fr","Rotation automatique"},{"backup.rotationSettings","es","Rotación automática"},{"backup.rotationSettings","de","Automatische Rotation"},
			{"backup.validation.autoDays","en","Automatic backups retention must be between 1 and 3650 days."},{"backup.validation.autoDays","fr","La conservation des sauvegardes automatiques doit être comprise entre 1 et 3650 jours."},{"backup.validation.autoDays","es","La retención de copias automáticas debe estar entre 1 y 3650 días."},{"backup.validation.autoDays","de","Die Aufbewahrung automatischer Backups muss zwischen 1 und 3650 Tagen liegen."},
			{"backup.validation.coherence","en","Automatic backups retention must cover at least two full backup intervals."},{"backup.validation.coherence","fr","La conservation des sauvegardes automatiques doit couvrir au moins deux intervalles complets de sauvegarde."},{"backup.validation.coherence","es","La retención de copias automáticas debe cubrir al menos dos intervalos completos de copia."},{"backup.validation.coherence","de","Die Aufbewahrung automatischer Backups muss mindestens zwei vollständige Backup-Intervalle abdecken."},
			{"backup.validation.interval","en","The interval must be an integer between 1 and 1440 minutes."},{"backup.validation.interval","fr","L’intervalle doit être un nombre entier compris entre 1 et 1440 minutes."},{"backup.validation.interval","es","El intervalo debe ser un número entero entre 1 y 1440 minutos."},{"backup.validation.interval","de","Das Intervall muss eine ganze Zahl zwischen 1 und 1440 Minuten sein."},
			{"backup.validation.otherDays","en","Other backups retention must be between 1 and 3650 days."},{"backup.validation.otherDays","fr","La conservation des autres sauvegardes doit être comprise entre 1 et 3650 jours."},{"backup.validation.otherDays","es","La retención de otras copias debe estar entre 1 y 3650 días."},{"backup.validation.otherDays","de","Die Aufbewahrung sonstiger Backups muss zwischen 1 und 3650 Tagen liegen."},
			{"toast.backupDeleteError","en","Error deleting the backup."},{"toast.backupDeleteError","fr","Erreur lors de la suppression de la sauvegarde."},{"toast.backupDeleteError","es","Error al eliminar la copia de seguridad."},{"toast.backupDeleteError","de","Fehler beim Löschen des Backups."},
			{"toast.backupDeleted","en","Backup deleted."},{"toast.backupDeleted","fr","Sauvegarde supprimée."},{"toast.backupDeleted","es","Copia de seguridad eliminada."},{"toast.backupDeleted","de","Backup gelöscht."},
			{"toast.backupSettingsError","en","Error saving backup settings."},{"toast.backupSettingsError","fr","Erreur lors de l’enregistrement des paramètres de sauvegarde."},{"toast.backupSettingsError","es","Error al guardar los parámetros de copia de seguridad."},{"toast.backupSettingsError","de","Fehler beim Speichern der Backup-Einstellungen."},
			{"toast.backupSettingsSaved","en","Backup settings saved."},{"toast.backupSettingsSaved","fr","Paramètres de sauvegarde enregistrés."},{"toast.backupSettingsSaved","es","Parámetros de copia de seguridad guardados."},{"toast.backupSettingsSaved","de","Backup-Einstellungen gespeichert."},
			// --- Complete missing localizations: PDF templates ---
			{"config.menu.pdfTemplate","en","PDF templates"},{"config.menu.pdfTemplate","fr","Modèles PDF"},{"config.menu.pdfTemplate","es","Plantillas PDF"},{"config.menu.pdfTemplate","de","PDF-Vorlagen"},
			{"config.menu.pdfTemplate.tooltip","en","Configure PDF logo, header and footer"},{"config.menu.pdfTemplate.tooltip","fr","Configurer le logo, l’en-tête et le pied de page des PDF"},{"config.menu.pdfTemplate.tooltip","es","Configurar el logotipo, el encabezado y el pie de página de los PDF"},{"config.menu.pdfTemplate.tooltip","de","Logo, Kopf- und Fußzeile der PDFs konfigurieren"},
			{"pdfTpl.addTitle","en","New PDF template"},{"pdfTpl.addTitle","fr","Nouveau modèle PDF"},{"pdfTpl.addTitle","es","Nueva plantilla PDF"},{"pdfTpl.addTitle","de","Neue PDF-Vorlage"},
			{"pdfTpl.chooseLogo","en","Choose logo"},{"pdfTpl.chooseLogo","fr","Choisir un logo"},{"pdfTpl.chooseLogo","es","Elegir logotipo"},{"pdfTpl.chooseLogo","de","Logo auswählen"},
			{"pdfTpl.color","en","Main color"},{"pdfTpl.color","fr","Couleur principale"},{"pdfTpl.color","es","Color principal"},{"pdfTpl.color","de","Hauptfarbe"},
			{"pdfTpl.deleteConfirm","en","Delete the PDF template of"},{"pdfTpl.deleteConfirm","fr","Supprimer le modèle PDF de"},{"pdfTpl.deleteConfirm","es","Eliminar la plantilla PDF de"},{"pdfTpl.deleteConfirm","de","PDF-Vorlage löschen von"},
			{"pdfTpl.deleteTitle","en","Delete PDF template"},{"pdfTpl.deleteTitle","fr","Supprimer le modèle PDF"},{"pdfTpl.deleteTitle","es","Eliminar plantilla PDF"},{"pdfTpl.deleteTitle","de","PDF-Vorlage löschen"},
			{"pdfTpl.deleted","en","PDF template deleted."},{"pdfTpl.deleted","fr","Modèle PDF supprimé."},{"pdfTpl.deleted","es","Plantilla PDF eliminada."},{"pdfTpl.deleted","de","PDF-Vorlage gelöscht."},
			{"pdfTpl.editTitle","en","Edit PDF template"},{"pdfTpl.editTitle","fr","Modifier le modèle PDF"},{"pdfTpl.editTitle","es","Editar plantilla PDF"},{"pdfTpl.editTitle","de","PDF-Vorlage bearbeiten"},
			{"pdfTpl.footer","en","Footer text"},{"pdfTpl.footer","fr","Texte de pied de page"},{"pdfTpl.footer","es","Texto del pie de página"},{"pdfTpl.footer","de","Fußzeilentext"},
			{"pdfTpl.header","en","Header text"},{"pdfTpl.header","fr","Texte d’en-tête"},{"pdfTpl.header","es","Texto del encabezado"},{"pdfTpl.header","de","Kopfzeilentext"},
			{"pdfTpl.logo","en","Logo"},{"pdfTpl.logo","fr","Logo"},{"pdfTpl.logo","es","Logotipo"},{"pdfTpl.logo","de","Logo"},
			{"pdfTpl.logoHint","en","PNG or JPG, up to 5 MB. Automatically resized."},{"pdfTpl.logoHint","fr","PNG ou JPG, 5 Mo maximum. Redimensionnement automatique."},{"pdfTpl.logoHint","es","PNG o JPG, máximo 5 MB. Redimensionado automático."},{"pdfTpl.logoHint","de","PNG oder JPG, maximal 5 MB. Automatische Größenanpassung."},
			{"pdfTpl.logoInvalid","en","Select a valid PNG or JPG image."},{"pdfTpl.logoInvalid","fr","Sélectionnez une image PNG ou JPG valide."},{"pdfTpl.logoInvalid","es","Selecciona una imagen PNG o JPG válida."},{"pdfTpl.logoInvalid","de","Wählen Sie ein gültiges PNG- oder JPG-Bild aus."},
			{"pdfTpl.logoTooLarge","en","The logo cannot exceed 5 MB."},{"pdfTpl.logoTooLarge","fr","Le logo ne peut pas dépasser 5 Mo."},{"pdfTpl.logoTooLarge","es","El logotipo no puede superar los 5 MB."},{"pdfTpl.logoTooLarge","de","Das Logo darf 5 MB nicht überschreiten."},
			{"pdfTpl.noFormats","en","No PDF format configured."},{"pdfTpl.noFormats","fr","Aucun format PDF configuré."},{"pdfTpl.noFormats","es","Ningún formato PDF configurado."},{"pdfTpl.noFormats","de","Kein PDF-Format konfiguriert."},
			{"pdfTpl.preview","en","Preview"},{"pdfTpl.preview","fr","Aperçu"},{"pdfTpl.preview","es","Vista previa"},{"pdfTpl.preview","de","Vorschau"},
			{"pdfTpl.previewFooter","en","Generation date and page number"},{"pdfTpl.previewFooter","fr","Date de génération et numéro de page"},{"pdfTpl.previewFooter","es","Fecha de generación y número de página"},{"pdfTpl.previewFooter","de","Erstellungsdatum und Seitenzahl"},
			{"pdfTpl.structure","en","Company"},{"pdfTpl.structure","fr","Entreprise"},{"pdfTpl.structure","es","Empresa"},{"pdfTpl.structure","de","Unternehmen"},
			{"pdfTpl.structureLocked","en","The associated company cannot be changed while editing."},{"pdfTpl.structureLocked","fr","L’entreprise associée ne peut pas être changée pendant la modification."},{"pdfTpl.structureLocked","es","La empresa asociada no se puede cambiar durante la edición."},{"pdfTpl.structureLocked","de","Das zugeordnete Unternehmen kann während der Bearbeitung nicht geändert werden."},
			{"toast.pdfTemplateSaved","en","PDF template saved."},{"toast.pdfTemplateSaved","fr","Modèle PDF enregistré."},{"toast.pdfTemplateSaved","es","Plantilla PDF guardada."},{"toast.pdfTemplateSaved","de","PDF-Vorlage gespeichert."},
			// --- Complete missing localizations: common entries ---
			{"btn.remove","en","Remove"},{"btn.remove","fr","Retirer"},{"btn.remove","es","Quitar"},{"btn.remove","de","Entfernen"},
			{"common.close","en","Close"},{"common.close","fr","Fermer"},{"common.close","es","Cerrar"},{"common.close","de","Schließen"},
			{"common.no","en","No"},{"common.no","fr","Non"},{"common.no","es","No"},{"common.no","de","Nein"},
			{"common.yes","en","Yes"},{"common.yes","fr","Oui"},{"common.yes","es","Sí"},{"common.yes","de","Ja"},
			{"msg.error.delete","en","Error while deleting."},{"msg.error.delete","fr","Erreur lors de la suppression."},{"msg.error.delete","es","Error al eliminar."},{"msg.error.delete","de","Fehler beim Löschen."},
			{"skills.active","en","Active"},{"skills.active","fr","Active"},{"skills.active","es","Activa"},{"skills.active","de","Aktiv"},
			{"toast.endAfterStart","en","End time must be after start time."},{"toast.endAfterStart","fr","L’heure de fin doit être après le début."},{"toast.endAfterStart","es","La hora de fin debe ser posterior al inicio."},{"toast.endAfterStart","de","Die Endzeit muss nach der Startzeit liegen."},
			{"toast.errorSolverStart","en","Error starting the solver."},{"toast.errorSolverStart","fr","Erreur lors du démarrage du solveur."},{"toast.errorSolverStart","es","Error al iniciar el solver."},{"toast.errorSolverStart","de","Fehler beim Starten des Solvers."},
			{"tooltip.edit","en","Edit"},{"tooltip.edit","fr","Modifier"},{"tooltip.edit","es","Editar"},{"tooltip.edit","de","Bearbeiten"}
		};
		insertLabelTranslations(data);
	}

	/**
	 * Solver/backup translations — keys registered in ensureLabelsTable but lacking
	 * localization (they appeared only through the frontend's Italian fallbacks). Kept in a
	 * separate method to avoid growing seedLabelTranslations (JVM 64 KB/method limit).
	 */
	private void seedLabelTranslations4() {
		String[][] data = {
			{"solver.err.unimproved","en","The unimproved stop cannot exceed the maximum duration."},{"solver.err.unimproved","fr","L'arrêt sans amélioration ne peut pas dépasser la durée maximale."},{"solver.err.unimproved","es","La parada sin mejoras no puede superar la duración máxima."},{"solver.err.unimproved","de","Der Stopp ohne Verbesserung darf die maximale Dauer nicht überschreiten."},
			{"solver.err.weekly","en","Minimum weekly shifts cannot exceed the maximum."},{"solver.err.weekly","fr","Les quarts hebdomadaires minimum ne peuvent pas dépasser le maximum."},{"solver.err.weekly","es","Los turnos semanales mínimos no pueden superar el máximo."},{"solver.err.weekly","de","Die wöchentlichen Mindestschichten dürfen das Maximum nicht überschreiten."},
			// Weights of the four configurable soft constraints (weekly_shift/days_off/consecutive_days)
			{"solver.label.weekly_shift_weight","en","Weekly shifts (min/max)"},{"solver.label.weekly_shift_weight","fr","Quarts hebdomadaires (min/max)"},{"solver.label.weekly_shift_weight","es","Turnos semanales (mín/máx)"},{"solver.label.weekly_shift_weight","de","Wöchentliche Schichten (Min/Max)"},
			{"solver.hint.weekly_shift_weight","en","0–10. Weight for respecting the weekly min/max shifts."},{"solver.hint.weekly_shift_weight","fr","0–10. Poids du respect des quarts hebdomadaires min/max."},{"solver.hint.weekly_shift_weight","es","0–10. Peso del cumplimiento de los turnos semanales mín/máx."},{"solver.hint.weekly_shift_weight","de","0–10. Gewicht für die Einhaltung der wöchentlichen Min-/Max-Schichten."},
			{"solver.help.weekly_shift_weight","en","How much respecting the configured weekly min/max shifts matters; 0 disables the penalty (0–10)."},{"solver.help.weekly_shift_weight","fr","Importance du respect des quarts hebdomadaires min/max configurés ; 0 désactive la pénalité (0–10)."},{"solver.help.weekly_shift_weight","es","Cuánto importa cumplir los turnos semanales mín/máx configurados; 0 desactiva la penalización (0–10)."},{"solver.help.weekly_shift_weight","de","Wie wichtig die Einhaltung der konfigurierten wöchentlichen Min-/Max-Schichten ist; 0 deaktiviert die Strafe (0–10)."},
			{"solver.label.days_off_weight","en","Minimum weekly days off"},{"solver.label.days_off_weight","fr","Repos hebdomadaires minimum"},{"solver.label.days_off_weight","es","Descansos semanales mínimos"},{"solver.label.days_off_weight","de","Wöchentliche Mindestruhetage"},
			{"solver.hint.days_off_weight","en","0–10. Weight for respecting the minimum weekly days off."},{"solver.hint.days_off_weight","fr","0–10. Poids du respect des repos hebdomadaires minimum."},{"solver.hint.days_off_weight","es","0–10. Peso del cumplimiento de los descansos semanales mínimos."},{"solver.hint.days_off_weight","de","0–10. Gewicht für die Einhaltung der wöchentlichen Mindestruhetage."},
			{"solver.help.days_off_weight","en","How much respecting the configured minimum weekly days off matters; 0 disables the penalty (0–10)."},{"solver.help.days_off_weight","fr","Importance du respect des repos hebdomadaires minimum configurés ; 0 désactive la pénalité (0–10)."},{"solver.help.days_off_weight","es","Cuánto importa cumplir los descansos semanales mínimos configurados; 0 desactiva la penalización (0–10)."},{"solver.help.days_off_weight","de","Wie wichtig die Einhaltung der konfigurierten wöchentlichen Mindestruhetage ist; 0 deaktiviert die Strafe (0–10)."},
			{"solver.label.consecutive_days_weight","en","Maximum consecutive days"},{"solver.label.consecutive_days_weight","fr","Jours consécutifs maximum"},{"solver.label.consecutive_days_weight","es","Días consecutivos máximos"},{"solver.label.consecutive_days_weight","de","Maximale aufeinanderfolgende Tage"},
			{"solver.hint.consecutive_days_weight","en","0–10. Weight for respecting the maximum consecutive days."},{"solver.hint.consecutive_days_weight","fr","0–10. Poids du respect des jours consécutifs maximum."},{"solver.hint.consecutive_days_weight","es","0–10. Peso del cumplimiento de los días consecutivos máximos."},{"solver.hint.consecutive_days_weight","de","0–10. Gewicht für die Einhaltung der maximalen aufeinanderfolgenden Tage."},
			{"solver.help.consecutive_days_weight","en","How much respecting the configured maximum consecutive working days matters; 0 disables the penalty (0–10)."},{"solver.help.consecutive_days_weight","fr","Importance du respect du maximum de jours travaillés consécutifs configuré ; 0 désactive la pénalité (0–10)."},{"solver.help.consecutive_days_weight","es","Cuánto importa cumplir el máximo de días laborables consecutivos configurado; 0 desactiva la penalización (0–10)."},{"solver.help.consecutive_days_weight","de","Wie wichtig die Einhaltung der konfigurierten maximalen aufeinanderfolgenden Arbeitstage ist; 0 deaktiviert die Strafe (0–10)."},
			// Backup COUNT limit, now configurable from the UI (second rotation criterion besides days)
			{"backup.autoKeep","en","Max automatic backups"},{"backup.autoKeep","fr","Max sauvegardes automatiques"},{"backup.autoKeep","es","Máx. copias automáticas"},{"backup.autoKeep","de","Max. automatische Backups"},
			{"backup.otherKeep","en","Max other backups"},{"backup.otherKeep","fr","Max autres sauvegardes"},{"backup.otherKeep","es","Máx. otras copias"},{"backup.otherKeep","de","Max. andere Backups"},
			{"backup.files","en","files"},{"backup.files","fr","fichiers"},{"backup.files","es","archivos"},{"backup.files","de","Dateien"},
			{"backup.validation.autoKeep","en","The maximum number of automatic backups must be between 1 and 100000."},{"backup.validation.autoKeep","fr","Le nombre maximum de sauvegardes automatiques doit être compris entre 1 et 100000."},{"backup.validation.autoKeep","es","El número máximo de copias automáticas debe estar entre 1 y 100000."},{"backup.validation.autoKeep","de","Die maximale Anzahl automatischer Backups muss zwischen 1 und 100000 liegen."},
			{"backup.validation.otherKeep","en","The maximum number of other backups must be between 1 and 100000."},{"backup.validation.otherKeep","fr","Le nombre maximum d'autres sauvegardes doit être compris entre 1 et 100000."},{"backup.validation.otherKeep","es","El número máximo de otras copias debe estar entre 1 y 100000."},{"backup.validation.otherKeep","de","Die maximale Anzahl anderer Backups muss zwischen 1 und 100000 liegen."}
		};
		insertLabelTranslations(data);
	}

	private void insertLabelTranslations(String[][] data) {
		String sql =
			"INSERT OR IGNORE INTO localizzazioni (entity_type, entity_id, field_name, language_id, value) " +
			"SELECT 'labels', l.id, 'value', lg.id, ? " +
			"FROM labels l, languages lg WHERE l.key=? AND lg.code=?;";
		try (Connection conn = DatabaseConnection.connect(dbName);
			 PreparedStatement stmt = conn.prepareStatement(sql)) {
			for (String[] row : data) {
				stmt.setString(1, row[2]);
				stmt.setString(2, row[0]);
				stmt.setString(3, row[1]);
				stmt.addBatch();
			}
			stmt.executeBatch();
		} catch (Exception e) {
			logger.log(Level.SEVERE, "Error seeding additional label translations", e);
		}
	}


	// -----------------------------------------------------------------------
	// Language management
	// -----------------------------------------------------------------------

	/**
	 * @brief Creates the languages table if it does not exist and seeds initial rows.
	 */
	private void ensureLanguagesTable() {
		try (Connection conn = DatabaseConnection.connect(dbName);
			 Statement stmt = conn.createStatement()) {
			stmt.execute(
				"CREATE TABLE IF NOT EXISTS languages (" +
				"  id INTEGER PRIMARY KEY AUTOINCREMENT," +
				"  code TEXT NOT NULL UNIQUE," +
				"  description TEXT NOT NULL," +
				"  active INTEGER NOT NULL DEFAULT 0" +
				");"
			);
			stmt.execute("INSERT OR IGNORE INTO languages (code, description, active) VALUES ('en','English',0);");
			stmt.execute("INSERT OR IGNORE INTO languages (code, description, active) VALUES ('fr','Français',0);");
			stmt.execute("INSERT OR IGNORE INTO languages (code, description, active) VALUES ('es','Español',0);");
			stmt.execute("INSERT OR IGNORE INTO languages (code, description, active) VALUES ('de','Deutsch',0);");
			// Chinese must exist for the startup catalog sync (ui-translations.tsv, zh column)
			// to apply; without this row the legacy bootstrap path would leave zh empty.
			stmt.execute("INSERT OR IGNORE INTO languages (code, description, active) VALUES ('zh','简体中文',1);");
		} catch (Exception e) {
			logger.log(Level.SEVERE, "Error during languages table migration", e);
		}
	}

	/**
	 * @brief Retrieves all languages from the database.
	 * @return list of Language objects
	 */
	public List<Language> getLanguages() {
		List<Language> list = new ArrayList<>();
		String sql = "SELECT id, code, description, active FROM languages ORDER BY id;";
		try (Connection conn = DatabaseConnection.connect(dbName);
			 PreparedStatement stmt = conn.prepareStatement(sql);
			 ResultSet rs = stmt.executeQuery()) {
			while (rs.next()) {
				list.add(new Language(
					rs.getInt("id"),
					rs.getString("code"),
					rs.getString("description"),
					rs.getInt("active") == 1
				));
			}
		} catch (Exception e) {
			logger.log(Level.SEVERE, "Error while fetching languages", e);
		}
		return list;
	}

	/**
	 * @brief Inserts a new language and returns its generated id.
	 * @param lang the Language to insert
	 * @return generated id, or -1 on failure
	 */
	public int addLanguage(Language lang) {
		String sql = "INSERT INTO languages (code, description, active) VALUES (?, ?, 0);";
		try (Connection conn = DatabaseConnection.connect(dbName);
			 PreparedStatement stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
			stmt.setString(1, lang.getCode());
			stmt.setString(2, lang.getDescription());
			stmt.executeUpdate();
			try (ResultSet keys = stmt.getGeneratedKeys()) {
				if (keys.next()) { invalidateTranslationsCache(); return keys.getInt(1); }
			}
		} catch (Exception e) {
			logger.log(Level.SEVERE, "Error while adding language", e);
		}
		return -1;
	}

	/**
	 * @brief Updates code and description of an existing language.
	 * @param id the language id to update
	 * @param lang the Language containing new values
	 * @return number of rows affected
	 */
	public int updateLanguage(int id, Language lang) {
		String sql = "UPDATE languages SET code = ?, description = ? WHERE id = ?;";
		try (Connection conn = DatabaseConnection.connect(dbName);
			 PreparedStatement stmt = conn.prepareStatement(sql)) {
			stmt.setString(1, lang.getCode());
			stmt.setString(2, lang.getDescription());
			stmt.setInt(3, id);
			int rows = stmt.executeUpdate();
			if (rows > 0) invalidateTranslationsCache();
			return rows;
		} catch (Exception e) {
			logger.log(Level.SEVERE, "Error while updating language", e);
		}
		return 0;
	}

	/**
	 * @brief Deletes a language by id. Returns false if the language is active.
	 * @param id the language id to delete
	 * @return true if deleted, false if active or not found
	 */
	public boolean deleteLanguageById(int id) {
		// Check if active
		String checkSql = "SELECT active FROM languages WHERE id = ?;";
		try (Connection conn = DatabaseConnection.connect(dbName);
			 PreparedStatement check = conn.prepareStatement(checkSql)) {
			check.setInt(1, id);
			try (ResultSet rs = check.executeQuery()) {
				if (rs.next() && rs.getInt("active") == 1) return false;
			}
			try (PreparedStatement del = conn.prepareStatement("DELETE FROM languages WHERE id = ?;")) {
				del.setInt(1, id);
				boolean deleted = del.executeUpdate() > 0;
				if (deleted) invalidateTranslationsCache();
				return deleted;
			}
		} catch (Exception e) {
			logger.log(Level.SEVERE, "Error while deleting language", e);
		}
		return false;
	}

	/**
	 * @brief Sets the active language, deactivating all others.
	 * @param id the language id to activate
	 */
	public void setActiveLanguage(int id) {
		String deactivate = "UPDATE languages SET active = 0;";
		String activate   = "UPDATE languages SET active = 1 WHERE id = ?;";
		try (Connection conn = DatabaseConnection.connect(dbName);
			 Statement deact = conn.createStatement();
			 PreparedStatement act = conn.prepareStatement(activate)) {
			deact.execute(deactivate);
			act.setInt(1, id);
			act.executeUpdate();
		} catch (Exception e) {
			logger.log(Level.SEVERE, "Error while setting active language", e);
		}
		invalidateTranslationsCache();
	}


	// -----------------------------------------------------------------------
	// Structures
	// -----------------------------------------------------------------------

	/**
	 * @brief Creates the structures table if absent and seeds the Default structure.
	 *        Also migrates employees and locations tables to add structure_id column.
	 */
	private void ensureStructuresTable() {
		try (Connection conn = DatabaseConnection.connect(dbName);
			 Statement stmt = conn.createStatement()) {
			stmt.execute("CREATE TABLE IF NOT EXISTS structures (" +
				"id INTEGER PRIMARY KEY AUTOINCREMENT, " +
				"name TEXT NOT NULL, " +
				"address TEXT NOT NULL DEFAULT '', " +
				"phone TEXT NOT NULL DEFAULT ''" +
				");");
			stmt.execute("INSERT OR IGNORE INTO structures (id, name, address, phone) VALUES (1, 'Default', '', '');");
			// Migrate employees and locations to include structure_id
			try { stmt.execute("ALTER TABLE employees ADD COLUMN structure_id INTEGER NOT NULL DEFAULT 1;"); } catch (Exception ignored) {}
			try { stmt.execute("ALTER TABLE locations ADD COLUMN structure_id INTEGER NOT NULL DEFAULT 1;"); } catch (Exception ignored) {}
			// Active/disabled flag (1=active): disabled items remain manageable on the Employees/
			// Locations pages but are excluded from Shift Management and the solver (activeOnly).
			try { stmt.execute("ALTER TABLE employees ADD COLUMN active INTEGER NOT NULL DEFAULT 1;"); } catch (Exception ignored) {}
			try { stmt.execute("ALTER TABLE employees ADD COLUMN email TEXT NOT NULL DEFAULT '';"); } catch (Exception ignored) {}
			try { stmt.execute("ALTER TABLE locations ADD COLUMN active INTEGER NOT NULL DEFAULT 1;"); } catch (Exception ignored) {}
			stmt.execute("UPDATE employees SET structure_id = 1 WHERE structure_id IS NULL;");
			stmt.execute("UPDATE locations SET structure_id = 1 WHERE structure_id IS NULL;");
			try { stmt.execute("CREATE INDEX IF NOT EXISTS idx_employees_structure ON employees(structure_id);"); } catch (Exception ignored) {}
			try { stmt.execute("CREATE INDEX IF NOT EXISTS idx_locations_structure ON locations(structure_id);"); } catch (Exception ignored) {}
		} catch (Exception e) {
			logger.log(Level.SEVERE, "Error ensuring structures table", e);
		}
		seedTestStructureData();
	}

	/**
	 * @brief Seeds a second test structure "北区审核中心" (id=2) with a copy
	 *        of all locations, shifts, and employees from the Default structure.
	 *        Idempotent: does nothing if structure 2 already has locations.
	 */
	private void seedTestStructureData() {
		try (Connection conn = DatabaseConnection.connect(dbName)) {
			// --- Idempotency guard ---
			try (PreparedStatement check = conn.prepareStatement(
					"SELECT COUNT(*) FROM locations WHERE structure_id = 2;");
				 ResultSet rs = check.executeQuery()) {
				if (rs.next() && rs.getInt(1) > 0) return;
			}

			conn.setAutoCommit(false);
			try {
				// 1. Insert structure 2
				try (PreparedStatement ins = conn.prepareStatement(
						"INSERT OR IGNORE INTO structures (id, name, address, phone) VALUES (2, '北区审核中心', '示例市示例路 1 号', '010-5550 2000');")) {
					ins.executeUpdate();
				}

				// 2. Copy locations from structure 1 → structure 2 with codes N001–N014
				java.util.Map<Integer, Integer> locMap = new java.util.LinkedHashMap<>();
				try (PreparedStatement sel = conn.prepareStatement(
						"SELECT id, code, name, l_order, latitude, longitude FROM locations WHERE structure_id = 1 ORDER BY l_order, id;");
					 ResultSet rs = sel.executeQuery()) {
					int counter = 1;
					try (PreparedStatement ins = conn.prepareStatement(
							"INSERT INTO locations (code, name, l_order, structure_id, latitude, longitude) VALUES (?, ?, ?, 2, ?, ?);",
							Statement.RETURN_GENERATED_KEYS)) {
						while (rs.next()) {
							int oldId = rs.getInt("id");
							String newCode = String.format("N%03d", counter++);
							ins.setString(1, newCode);
							ins.setString(2, rs.getString("name"));
							ins.setInt(3, rs.getInt("l_order"));
							ins.setObject(4, rs.getObject("latitude"));
							ins.setObject(5, rs.getObject("longitude"));
							ins.executeUpdate();
							try (ResultSet keys = ins.getGeneratedKeys()) {
								if (keys.next()) locMap.put(oldId, keys.getInt(1));
							}
						}
					}
				}

				// 3. Copy location_skills for new locations
				for (java.util.Map.Entry<Integer, Integer> e : locMap.entrySet()) {
					try (PreparedStatement sel = conn.prepareStatement(
							"SELECT skill_id, skill_type_id FROM location_skills WHERE location_id = ?;")) {
						sel.setInt(1, e.getKey());
						try (ResultSet rs = sel.executeQuery();
							 PreparedStatement ins = conn.prepareStatement(
									"INSERT INTO location_skills (location_id, skill_id, skill_type_id) VALUES (?, ?, ?);")) {
							while (rs.next()) {
								ins.setInt(1, e.getValue());
								ins.setInt(2, rs.getInt("skill_id"));
								ins.setInt(3, rs.getInt("skill_type_id"));
								ins.executeUpdate();
							}
						}
					}
				}

				// 4. Copy shifts for each location and map old→new shift ids
				java.util.Map<Integer, Integer> shiftMap = new java.util.LinkedHashMap<>();
				for (java.util.Map.Entry<Integer, Integer> e : locMap.entrySet()) {
					try (PreparedStatement sel = conn.prepareStatement(
							"SELECT id, start_time, end_time FROM shifts WHERE location_id = ?;")) {
						sel.setInt(1, e.getKey());
						try (ResultSet rs = sel.executeQuery();
							 PreparedStatement ins = conn.prepareStatement(
									"INSERT INTO shifts (location_id, start_time, end_time) VALUES (?, ?, ?);",
									Statement.RETURN_GENERATED_KEYS)) {
							while (rs.next()) {
								int oldShiftId = rs.getInt("id");
								ins.setInt(1, e.getValue());
								ins.setString(2, rs.getString("start_time"));
								ins.setString(3, rs.getString("end_time"));
								ins.executeUpdate();
								try (ResultSet keys = ins.getGeneratedKeys()) {
									if (keys.next()) shiftMap.put(oldShiftId, keys.getInt(1));
								}
							}
						}
					}
				}

				// 5. Copy shift_skills for new shifts
				for (java.util.Map.Entry<Integer, Integer> e : shiftMap.entrySet()) {
					try (PreparedStatement sel = conn.prepareStatement(
							"SELECT skill_id, skill_type_id FROM shift_skills WHERE shift_id = ?;")) {
						sel.setInt(1, e.getKey());
						try (ResultSet rs = sel.executeQuery();
							 PreparedStatement ins = conn.prepareStatement(
									"INSERT INTO shift_skills (shift_id, skill_id, skill_type_id) VALUES (?, ?, ?);")) {
							while (rs.next()) {
								ins.setInt(1, e.getValue());
								ins.setInt(2, rs.getInt("skill_id"));
								ins.setInt(3, rs.getInt("skill_type_id"));
								ins.executeUpdate();
							}
						}
					}
				}

				// 6. Insert employees for structure 2 with Chinese test names
				String[][] employees = {
					{"N2-001", "审核员9001", "测试"},
					{"N2-002", "审核员9002", "测试"},
					{"N2-003", "审核员9003", "测试"},
					{"N2-004", "审核员9004", "测试"},
					{"N2-005", "审核员9005", "测试"},
					{"N2-006", "审核员9006", "测试"},
					{"N2-007", "审核员9007", "测试"},
					{"N2-008", "审核员9008", "测试"},
				};
				try (PreparedStatement ins = conn.prepareStatement(
						"INSERT OR IGNORE INTO employees (code, first_name, last_name, structure_id) VALUES (?, ?, ?, 2);")) {
					for (String[] emp : employees) {
						ins.setString(1, emp[0]);
						ins.setString(2, emp[1]);
						ins.setString(3, emp[2]);
						ins.executeUpdate();
					}
				}

				conn.commit();
				logger.info("[seedTestStructureData] 北区审核中心 seeded successfully.");
			} catch (Exception ex) {
				conn.rollback();
				logger.log(Level.SEVERE, "Error seeding test structure data — rolled back", ex);
			} finally {
				conn.setAutoCommit(true);
			}
		} catch (Exception e) {
			logger.log(Level.SEVERE, "Error in seedTestStructureData", e);
		}
	}






}
