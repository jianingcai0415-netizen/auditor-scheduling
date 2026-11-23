package org.acme.employeescheduling.rest;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.acme.employeescheduling.dto.WorkflowDtos.ImportIssue;
import org.acme.employeescheduling.dto.WorkflowDtos.ImportPreview;
import org.acme.employeescheduling.persistence.AuditOrderEntity;
import org.acme.employeescheduling.persistence.AuditOrderPhaseEntity;
import org.acme.employeescheduling.persistence.AuditPhaseSkillEntity;
import org.acme.employeescheduling.persistence.EmployeeDateEntity;
import org.acme.employeescheduling.persistence.EmployeeEntity;
import org.acme.employeescheduling.persistence.EmployeeSkillEntity;
import org.acme.employeescheduling.persistence.ImportJobEntity;
import org.acme.employeescheduling.persistence.LocationEntity;
import org.acme.employeescheduling.persistence.ShiftEntity;
import org.acme.employeescheduling.persistence.ShiftSkillEntity;
import org.acme.employeescheduling.persistence.SkillEntity;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.acme.employeescheduling.utils.TravelMatrix;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/** XLSX parser, preview validator and idempotent importer for the pilot dataset. */
@ApplicationScoped
public class ImportWorkbookService {
    static final int MAX_BYTES = 15 * 1024 * 1024;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    @Inject ObjectMapper objectMapper;

    public ImportPreview preview(byte[] bytes, String fileName, int structureId, String actor) {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES)
            throw new IllegalArgumentException("IMPORT_FILE_SIZE_INVALID");
        Parsed parsed = parse(bytes);
        validate(parsed, structureId);
        try {
            ImportJobEntity job = new ImportJobEntity();
            job.token = UUID.randomUUID().toString();
            job.structureId = structureId;
            job.fileName = fileName == null || fileName.isBlank() ? "audit-dispatch.xlsx" : fileName;
            job.fileSha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            job.rowCount = parsed.totalRows();
            job.errorCount = (int) parsed.issues.stream().filter(i -> "ERROR".equals(i.severity())).count();
            job.warningCount = parsed.issues.size() - job.errorCount;
            job.summaryJson = objectMapper.writeValueAsString(parsed.summary());
            job.payloadJson = objectMapper.writeValueAsString(parsed.tables);
            job.errorsJson = objectMapper.writeValueAsString(parsed.issues);
            job.createdBy = actor;
            job.createdAt = WorkflowSupport.now();
            job.persist();
            return new ImportPreview(job.token, job.fileName, job.rowCount, job.errorCount, job.warningCount,
                    parsed.summary(), parsed.issues, job.errorCount == 0);
        } catch (Exception exception) {
            throw new IllegalStateException("IMPORT_PREVIEW_STORE_FAILED", exception);
        }
    }

    public Map<String, Object> confirm(ImportJobEntity job) {
        if (!"PREVIEW".equals(job.status)) throw new IllegalStateException("IMPORT_ALREADY_CONFIRMED");
        if (job.errorCount > 0) throw new IllegalStateException("IMPORT_HAS_ERRORS");
        final Map<String, List<Map<String, String>>> tables;
        try {
            tables = objectMapper.readValue(job.payloadJson, new TypeReference<>() { });
        } catch (Exception exception) {
            throw new IllegalStateException("IMPORT_PAYLOAD_INVALID", exception);
        }

        Map<String, Integer> locations = upsertLocations(tables.getOrDefault("locations", List.of()), job.structureId);
        Map<String, Integer> employees = upsertEmployees(tables.getOrDefault("auditors", List.of()), job.structureId, locations);
        Map<String, Integer> skills = upsertSkills(tables.getOrDefault("qualifications", List.of()),
                tables.getOrDefault("phases", List.of()), job.structureId);
        upsertQualifications(tables.getOrDefault("qualifications", List.of()), employees, skills);
        upsertUnavailability(tables.getOrDefault("unavailability", List.of()), employees);
        Map<String, Integer> phases = upsertOrdersAndPhases(tables.getOrDefault("orders", List.of()),
                tables.getOrDefault("phases", List.of()), job.structureId, locations, skills);
        upsertExistingAssignments(tables.getOrDefault("assignments", List.of()), employees, phases);
        job.status = "CONFIRMED";
        return Map.of("employees", employees.size(), "locations", locations.size(), "skills", skills.size(),
                "phases", phases.size());
    }

    public byte[] template() {
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            String[][] definitions = {
                    {"审核员", "auditor_id", "employee_code", "姓氏", "名字", "测试邮箱", "active", "base_location_id", "缓冲前分钟", "缓冲后分钟"},
                    {"审核员资格", "auditor_id", "qualification_code", "资格名称", "资格级别", "测试证书编号", "签发日期", "到期日期", "状态"},
                    {"时间约束", "auditor_id", "date_type", "start_datetime", "end_datetime", "原因"},
                    {"地点", "location_id", "城市", "纬度", "经度"},
                    {"订单", "order_id", "客户名称", "项目名称", "优先级", "负责人", "状态"},
                    {"订单阶段", "order_phase_id", "order_id", "阶段类型", "所需资格", "location_id", "开始时间", "结束时间", "所需人数", "所需组长", "连续组", "交付方式"},
                    {"已排任务", "assignment_id", "order_phase_id", "auditor_id", "任务角色", "锁定", "状态"}
            };
            CellStyle header = workbook.createCellStyle();
            header.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
            header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            Font font = workbook.createFont(); font.setBold(true); font.setColor(IndexedColors.WHITE.getIndex());
            header.setFont(font);
            for (String[] definition : definitions) {
                Sheet sheet = workbook.createSheet(definition[0]);
                sheet.createFreezePane(0, 1);
                Row row = sheet.createRow(0);
                for (int index = 1; index < definition.length; index++) {
                    Cell cell = row.createCell(index - 1);
                    cell.setCellValue(definition[index]);
                    cell.setCellStyle(header);
                    sheet.setColumnWidth(index - 1, Math.min(45, Math.max(14, definition[index].length() + 4)) * 256);
                }
                sheet.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(0, 0, 0, definition.length - 2));
            }
            workbook.write(output);
            return output.toByteArray();
        } catch (Exception exception) {
            throw new IllegalStateException("IMPORT_TEMPLATE_FAILED", exception);
        }
    }

    private Parsed parse(byte[] bytes) {
        Parsed parsed = new Parsed();
        try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            read(workbook, parsed, "auditors", "审核员", "Auditors");
            read(workbook, parsed, "qualifications", "审核员资格", "Qualifications");
            read(workbook, parsed, "unavailability", "时间约束", "Unavailability");
            read(workbook, parsed, "locations", "地点", "Locations");
            read(workbook, parsed, "orders", "订单", "Orders");
            read(workbook, parsed, "phases", "订单阶段", "OrderPhases");
            read(workbook, parsed, "assignments", "已排任务", "ExistingAssignments");
            return parsed;
        } catch (Exception exception) {
            throw new IllegalArgumentException("IMPORT_FILE_INVALID", exception);
        }
    }

    private void read(Workbook workbook, Parsed parsed, String key, String... aliases) {
        Sheet sheet = null;
        for (String alias : aliases) {
            sheet = workbook.getSheet(alias);
            if (sheet != null) break;
        }
        if (sheet == null) {
            parsed.tables.put(key, new ArrayList<>());
            if (Set.of("auditors", "qualifications", "locations", "phases").contains(key))
                parsed.issues.add(new ImportIssue(aliases[0], 0, "", "ERROR", "SHEET_REQUIRED", "缺少工作表"));
            return;
        }
        DataFormatter formatter = new DataFormatter();
        Row header = sheet.getRow(sheet.getFirstRowNum());
        if (header == null) {
            parsed.tables.put(key, new ArrayList<>());
            parsed.issues.add(new ImportIssue(sheet.getSheetName(), 1, "", "ERROR", "HEADER_REQUIRED", "缺少表头"));
            return;
        }
        List<String> columns = new ArrayList<>();
        for (int cell = 0; cell < header.getLastCellNum(); cell++) columns.add(formatter.formatCellValue(header.getCell(cell)).trim());
        List<Map<String, String>> rows = new ArrayList<>();
        for (int rowIndex = header.getRowNum() + 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (row == null) continue;
            Map<String, String> values = new LinkedHashMap<>();
            boolean nonBlank = false;
            for (int cell = 0; cell < columns.size(); cell++) {
                String value = cellText(row.getCell(cell), formatter);
                if (value.length() > 2000) value = value.substring(0, 2000);
                if (!value.isBlank()) nonBlank = true;
                values.put(columns.get(cell), value);
            }
            if (nonBlank) { values.put("__row", String.valueOf(rowIndex + 1)); rows.add(values); }
        }
        parsed.tables.put(key, rows);
    }

    private void validate(Parsed parsed, int structureId) {
        Map<String, String> auditorIds = new LinkedHashMap<>();
        Set<String> employeeCodes = new LinkedHashSet<>();
        for (Map<String, String> row : parsed.rows("auditors")) {
            String code = value(row, "employee_code", "员工编号");
            require(parsed, "审核员", row, "employee_code", code, "EMPLOYEE_CODE_REQUIRED");
            if (!code.isBlank() && !employeeCodes.add(code)) issue(parsed, "审核员", row, "employee_code", "ERROR", "EMPLOYEE_DUPLICATE", "员工编号重复");
            String auditorId = value(row, "auditor_id", "employee_code");
            if (!auditorId.isBlank()) auditorIds.put(auditorId, code);
            EmployeeEntity existing = EmployeeEntity.find("code", code).firstResult();
            if (existing != null && existing.structureId != structureId)
                issue(parsed, "审核员", row, "employee_code", "ERROR", "EMPLOYEE_CROSS_STRUCTURE", "员工编号属于其他部门");
        }
        EmployeeEntity.<EmployeeEntity>list("structureId", structureId).forEach(employee -> auditorIds.putIfAbsent(employee.code, employee.code));
        Set<String> locationCodes = new LinkedHashSet<>();
        for (Map<String, String> row : parsed.rows("locations")) {
            String code = value(row, "location_id", "location_code", "地点编号");
            require(parsed, "地点", row, "location_id", code, "LOCATION_CODE_REQUIRED");
            if (!code.isBlank() && !locationCodes.add(code)) issue(parsed, "地点", row, "location_id", "ERROR", "LOCATION_DUPLICATE", "地点编号重复");
            if (value(row, "纬度", "latitude").isBlank() || value(row, "经度", "longitude").isBlank())
                issue(parsed, "地点", row, "纬度/经度", "WARNING", "LOCATION_COORDINATES_MISSING", "缺少坐标，将按120分钟估算转场");
        }
        Set<String> availableLocationCodes = new LinkedHashSet<>(locationCodes);
        LocationEntity.<LocationEntity>list("structureId", structureId).forEach(location -> availableLocationCodes.add(location.code));
        Set<String> qualificationKeys = new LinkedHashSet<>();
        Set<String> certificateNumbers = new LinkedHashSet<>();
        for (Map<String, String> row : parsed.rows("qualifications")) {
            String auditor = value(row, "auditor_id", "employee_code");
            String employeeCode = auditorIds.getOrDefault(auditor, auditor);
            String qualification = value(row, "qualification_code", "资格代码");
            String certificate = value(row, "测试证书编号", "certificate_number", "证书编号");
            require(parsed, "审核员资格", row, "auditor_id", employeeCode, "QUALIFICATION_EMPLOYEE_REQUIRED");
            require(parsed, "审核员资格", row, "qualification_code", qualification, "QUALIFICATION_CODE_REQUIRED");
            require(parsed, "审核员资格", row, "certificate_number", certificate, "CERTIFICATE_NUMBER_REQUIRED");
            if (!employeeCode.isBlank() && !auditorIds.containsValue(employeeCode))
                issue(parsed, "审核员资格", row, "auditor_id", "ERROR", "QUALIFICATION_EMPLOYEE_UNKNOWN", "审核员不存在");
            if (!certificate.isBlank() && !certificateNumbers.add(certificate))
                issue(parsed, "审核员资格", row, "certificate_number", "ERROR", "CERTIFICATE_DUPLICATE", "证书编号重复");
            if (!certificate.isBlank()) {
                EmployeeSkillEntity existingCertificate = EmployeeSkillEntity.find("certificateNumber", certificate).firstResult();
                if (existingCertificate != null) {
                    EmployeeEntity certificateOwner = EmployeeEntity.findById(existingCertificate.employeeId);
                    if (certificateOwner != null && !certificateOwner.code.equals(employeeCode))
                        issue(parsed, "审核员资格", row, "certificate_number", "ERROR", "CERTIFICATE_ALREADY_USED", "证书编号已属于其他审核员");
                }
            }
            String key = employeeCode + "|" + qualification;
            if (!employeeCode.isBlank() && !qualification.isBlank() && !qualificationKeys.add(key))
                issue(parsed, "审核员资格", row, "qualification_code", "ERROR", "QUALIFICATION_DUPLICATE", "同一审核员资格重复");
            String until = value(row, "到期日期", "valid_until");
            if (!until.isBlank()) {
                try {
                    LocalDate expiry = parseDate(until);
                    if (expiry.isBefore(LocalDate.now())) issue(parsed, "审核员资格", row, "到期日期", "WARNING", "QUALIFICATION_EXPIRED", "资格已经过期");
                    else if (expiry.isBefore(LocalDate.now().plusDays(90))) issue(parsed, "审核员资格", row, "到期日期", "WARNING", "QUALIFICATION_EXPIRING", "资格将在90天内到期");
                } catch (Exception ignored) {
                    issue(parsed, "审核员资格", row, "到期日期", "ERROR", "DATE_INVALID", "日期格式应为yyyy-MM-dd");
                }
            }
        }
        Set<String> phaseKeys = new LinkedHashSet<>();
        for (Map<String, String> row : parsed.rows("phases")) {
            String order = value(row, "order_id", "order_code", "订单编号");
            String phase = value(row, "order_phase_id", "phase_code", "阶段编号");
            require(parsed, "订单阶段", row, "order_id", order, "ORDER_CODE_REQUIRED");
            require(parsed, "订单阶段", row, "order_phase_id", phase, "PHASE_CODE_REQUIRED");
            require(parsed, "订单阶段", row, "location_id", value(row, "location_id"), "PHASE_LOCATION_REQUIRED");
            String locationCode = value(row, "location_id", "location_code", "地点编号");
            if (!locationCode.isBlank() && !availableLocationCodes.contains(locationCode))
                issue(parsed, "订单阶段", row, "location_id", "ERROR", "PHASE_LOCATION_UNKNOWN", "地点编号不存在");
            require(parsed, "订单阶段", row, "开始时间", value(row, "开始时间", "start_time"), "PHASE_START_REQUIRED");
            require(parsed, "订单阶段", row, "结束时间", value(row, "结束时间", "end_time"), "PHASE_END_REQUIRED");
            if (!phase.isBlank() && !phaseKeys.add(phase)) issue(parsed, "订单阶段", row, "order_phase_id", "ERROR", "PHASE_DUPLICATE", "阶段编号重复");
            try {
                int headcount = integer(value(row, "所需人数", "required_headcount"), 1);
                if (headcount < 1 || headcount > 100) throw new IllegalArgumentException();
                LocalDateTime start = WorkflowSupport.parseDateTime(value(row, "开始时间", "start_time"));
                LocalDateTime end = WorkflowSupport.parseDateTime(value(row, "结束时间", "end_time"));
                if (start == null || end == null || !end.isAfter(start)) throw new IllegalArgumentException();
            } catch (Exception ignored) {
                issue(parsed, "订单阶段", row, "时间/人数", "ERROR", "PHASE_VALUE_INVALID", "阶段时间或人数无效");
            }
        }
        Set<String> orderCodes = new LinkedHashSet<>();
        for (Map<String, String> row : parsed.rows("orders")) {
            String order = value(row, "order_id", "order_code", "订单编号");
            require(parsed, "订单", row, "order_id", order, "ORDER_CODE_REQUIRED");
            if (!order.isBlank() && !orderCodes.add(order))
                issue(parsed, "订单", row, "order_id", "ERROR", "ORDER_DUPLICATE", "订单编号重复");
        }
        for (Map<String, String> row : parsed.rows("unavailability")) {
            String auditor = value(row, "auditor_id", "employee_code");
            String employeeCode = auditorIds.getOrDefault(auditor, auditor);
            if (employeeCode.isBlank() || !auditorIds.containsValue(employeeCode))
                issue(parsed, "时间约束", row, "auditor_id", "ERROR", "UNAVAILABILITY_EMPLOYEE_UNKNOWN", "审核员不存在");
            try {
                LocalDateTime start = WorkflowSupport.parseDateTime(value(row, "start_datetime", "开始时间"));
                LocalDateTime end = WorkflowSupport.parseDateTime(value(row, "end_datetime", "结束时间"));
                if (start == null || end == null || !end.isAfter(start)) throw new IllegalArgumentException();
            } catch (Exception ignored) {
                issue(parsed, "时间约束", row, "start_datetime/end_datetime", "ERROR", "UNAVAILABILITY_TIME_INVALID", "不可用时间无效");
            }
        }
        for (Map<String, String> row : parsed.rows("assignments")) {
            String auditor = value(row, "auditor_id", "employee_code");
            String employeeCode = auditorIds.getOrDefault(auditor, auditor);
            if (employeeCode.isBlank() || !auditorIds.containsValue(employeeCode))
                issue(parsed, "已排任务", row, "auditor_id", "ERROR", "ASSIGNMENT_EMPLOYEE_UNKNOWN", "审核员不存在");
            if (!phaseKeys.contains(value(row, "order_phase_id", "phase_code")))
                issue(parsed, "已排任务", row, "order_phase_id", "ERROR", "ASSIGNMENT_PHASE_UNKNOWN", "阶段编号不存在");
        }
    }

    private Map<String, Integer> upsertLocations(List<Map<String, String>> rows, int structureId) {
        Map<String, Integer> result = new LinkedHashMap<>();
        for (Map<String, String> row : rows) {
            String code = value(row, "location_id", "location_code", "地点编号");
            LocationEntity entity = LocationEntity.find("code", code).firstResult();
            if (entity == null) { entity = new LocationEntity(); entity.code = code; entity.structureId = structureId; }
            entity.name = defaultText(value(row, "城市", "name", "地点名称"), code);
            entity.active = true;
            entity.latitude = decimal(value(row, "纬度", "latitude"));
            entity.longitude = decimal(value(row, "经度", "longitude"));
            if (!entity.isPersistent()) entity.persist();
            result.put(code, entity.id);
        }
        // Imported coordinates may differ from the stored ones: drop the cached driving-time
        // matrix so scoring/solve recomputes against the imported sites.
        TravelMatrix.invalidateAfterCommit();
        return result;
    }

    private Map<String, Integer> upsertEmployees(List<Map<String, String>> rows, int structureId,
            Map<String, Integer> locations) {
        Map<String, Integer> result = new LinkedHashMap<>();
        for (Map<String, String> row : rows) {
            String code = value(row, "employee_code", "员工编号");
            EmployeeEntity entity = EmployeeEntity.find("code", code).firstResult();
            if (entity == null) { entity = new EmployeeEntity(); entity.code = code; entity.structureId = structureId; }
            entity.lastName = defaultText(value(row, "姓氏", "last_name"), "-");
            entity.firstName = defaultText(value(row, "名字", "first_name"), "-");
            entity.email = value(row, "测试邮箱", "email");
            entity.active = bool(value(row, "active"), true);
            entity.currentLocationId = locations.get(value(row, "base_location_id", "current_location_id"));
            entity.bufferBeforeMinutes = integer(value(row, "缓冲前分钟", "buffer_before_minutes"), 30);
            entity.bufferAfterMinutes = integer(value(row, "缓冲后分钟", "buffer_after_minutes"), 30);
            if (!entity.isPersistent()) entity.persist();
            result.put(value(row, "auditor_id", "employee_code"), entity.id);
            result.put(code, entity.id);
        }
        return result;
    }

    private Map<String, Integer> upsertSkills(List<Map<String, String>> qualificationRows,
            List<Map<String, String>> phaseRows, int structureId) {
        Map<String, Integer> result = new LinkedHashMap<>();
        for (Map<String, String> row : qualificationRows) {
            String code = value(row, "qualification_code", "资格代码");
            ensureSkills(result, List.of(code), structureId);
        }
        for (Map<String, String> row : phaseRows) {
            ensureSkills(result, splitQualifications(value(row, "所需资格", "qualification_code", "资格代码")), structureId);
        }
        return result;
    }

    private void ensureSkills(Map<String, Integer> result, List<String> codes, int structureId) {
        for (String code : codes) {
            if (code.isBlank() || result.containsKey(code)) continue;
            SkillEntity entity = SkillEntity.find("structureId = ?1 and name = ?2", structureId, code).firstResult();
            if (entity == null) {
                entity = new SkillEntity(); entity.structureId = structureId; entity.name = code; entity.active = true;
                entity.skillOrder = (int) SkillEntity.count("structureId", structureId) + 1; entity.persist();
            }
            result.put(code, entity.id);
        }
    }

    private void upsertQualifications(List<Map<String, String>> rows, Map<String, Integer> employees,
            Map<String, Integer> skills) {
        for (Map<String, String> row : rows) {
            Integer employeeId = employees.get(value(row, "auditor_id", "employee_code"));
            Integer skillId = skills.get(value(row, "qualification_code", "资格代码"));
            if (employeeId == null || skillId == null) continue;
            EmployeeSkillEntity entity = EmployeeSkillEntity.find("employeeId = ?1 and skillId = ?2", employeeId, skillId).firstResult();
            if (entity == null) { entity = new EmployeeSkillEntity(); entity.employeeId = employeeId; entity.skillId = skillId; }
            entity.certificateNumber = value(row, "测试证书编号", "certificate_number", "证书编号");
            entity.qualificationLevel = defaultText(value(row, "资格级别", "qualification_level"), "AUDITOR");
            entity.validFrom = dateText(value(row, "签发日期", "valid_from"));
            entity.validUntil = dateText(value(row, "到期日期", "valid_until"));
            entity.qualificationStatus = normalizeQualificationStatus(value(row, "状态", "qualification_status"));
            if (!entity.isPersistent()) entity.persist();
        }
    }

    private void upsertUnavailability(List<Map<String, String>> rows, Map<String, Integer> employees) {
        for (Map<String, String> row : rows) {
            Integer employeeId = employees.get(value(row, "auditor_id", "employee_code"));
            if (employeeId == null) continue;
            String start = WorkflowSupport.dbDateTime(value(row, "start_datetime", "开始时间"));
            String end = WorkflowSupport.dbDateTime(value(row, "end_datetime", "结束时间"));
            String type = value(row, "date_type", "类型").toUpperCase();
            int typeId = type.contains("DESIRED") || type.contains("期望") ? 1
                    : type.contains("UNDESIRED") || type.contains("不期望") ? 2 : 3;
            EmployeeDateEntity existing = EmployeeDateEntity.find(
                    "employeeId = ?1 and dateStart = ?2 and dateEnd = ?3 and dateTypeId = ?4", employeeId, start, end, typeId).firstResult();
            if (existing == null) {
                EmployeeDateEntity entity = new EmployeeDateEntity(); entity.employeeId = employeeId;
                entity.dateStart = start; entity.dateEnd = end; entity.dateTypeId = typeId; entity.persist();
            }
        }
    }

    private Map<String, Integer> upsertOrdersAndPhases(List<Map<String, String>> orderRows,
            List<Map<String, String>> phaseRows, int structureId, Map<String, Integer> locations,
            Map<String, Integer> skills) {
        Map<String, Map<String, String>> orderPayload = new LinkedHashMap<>();
        for (Map<String, String> row : orderRows) orderPayload.put(value(row, "order_id", "order_code", "订单编号"), row);
        Map<String, Integer> phaseIds = new LinkedHashMap<>();
        for (Map<String, String> row : phaseRows) {
            String orderCode = value(row, "order_id", "order_code", "订单编号");
            Map<String, String> orderRow = orderPayload.getOrDefault(orderCode, Map.of());
            AuditOrderEntity order = AuditOrderEntity.find("structureId = ?1 and orderCode = ?2", structureId, orderCode).firstResult();
            if (order == null) {
                order = new AuditOrderEntity(); order.structureId = structureId; order.orderCode = orderCode;
                order.createdAt = order.updatedAt = WorkflowSupport.now(); order.persistAndFlush();
            }
            order.customerName = value(orderRow, "客户名称", "customer_name");
            order.projectName = defaultText(value(orderRow, "项目名称", "project_name"), orderCode);
            order.priority = integer(value(orderRow, "优先级", "priority"), 3);
            order.ownerName = value(orderRow, "负责人", "owner_name");
            // 导入的订单是既成事实，默认按已批准处理；旧模板里的 ACTIVE 等价于新状态机的 APPROVED。
            String importedStatus = defaultText(value(orderRow, "状态", "status"), "APPROVED").toUpperCase();
            order.status = "ACTIVE".equals(importedStatus) ? "APPROVED" : importedStatus;
            order.updatedAt = WorkflowSupport.now();

            String phaseCode = value(row, "order_phase_id", "phase_code", "阶段编号");
            AuditOrderPhaseEntity phase = AuditOrderPhaseEntity.find(
                    "auditOrderId = ?1 and phaseCode = ?2", order.id, phaseCode).firstResult();
            if (phase == null) {
                phase = new AuditOrderPhaseEntity(); phase.auditOrderId = order.id; phase.phaseCode = phaseCode;
                phase.createdAt = WorkflowSupport.now();
            }
            phase.phaseName = defaultText(value(row, "阶段类型", "phase_name"), phaseCode);
            phase.startTime = WorkflowSupport.dbDateTime(value(row, "开始时间", "start_time"));
            phase.endTime = WorkflowSupport.dbDateTime(value(row, "结束时间", "end_time"));
            String locationCode = value(row, "location_id", "location_code", "地点编号");
            Integer locationId = locations.get(locationCode);
            if (locationId == null) {
                LocationEntity existingLocation = LocationEntity.find(
                        "structureId = ?1 and code = ?2", structureId, locationCode).firstResult();
                locationId = existingLocation == null ? null : existingLocation.id;
            }
            if (locationId == null) throw new IllegalStateException("IMPORT_PHASE_LOCATION_UNKNOWN:" + locationCode);
            phase.locationId = locationId;
            phase.requiredHeadcount = integer(value(row, "所需人数", "required_headcount"), 1);
            phase.leadRequired = bool(value(row, "所需组长", "lead_required"), false);
            phase.continuityGroup = value(row, "连续组", "continuity_group");
            phase.deliveryMode = normaliseDeliveryMode(value(row, "交付方式", "delivery_mode"));
            phase.status = "READY"; phase.updatedAt = WorkflowSupport.now();
            if (!phase.isPersistent()) phase.persistAndFlush();
            AuditPhaseSkillEntity.delete("phaseId", phase.id);
            List<Integer> requiredSkillIds = new ArrayList<>();
            for (String qualification : splitQualifications(value(row, "所需资格", "qualification_code"))) {
                Integer skillId = skills.get(qualification);
                if (skillId != null) requiredSkillIds.add(skillId);
            }
            for (Integer skillId : requiredSkillIds) {
                AuditPhaseSkillEntity requirement = new AuditPhaseSkillEntity(); requirement.phaseId = phase.id;
                requirement.skillId = skillId; requirement.minimumCount = phase.requiredHeadcount; requirement.persist();
            }
            materialize(order, phase, requiredSkillIds);
            phaseIds.put(phaseCode, phase.id);
        }
        return phaseIds;
    }

    private void materialize(AuditOrderEntity order, AuditOrderPhaseEntity phase, List<Integer> skillIds) {
        List<ShiftEntity> shifts = new ArrayList<>(ShiftEntity.<ShiftEntity>find("auditOrderPhaseId = ?1 order by id", phase.id).list());
        while (shifts.size() > phase.requiredHeadcount) {
            ShiftEntity candidate = shifts.get(shifts.size() - 1);
            if (candidate.employeeId != null || org.acme.employeescheduling.persistence.SchedulePlanAssignmentEntity.count("shiftId", candidate.id) > 0)
                throw new IllegalStateException("IMPORT_PHASE_HEADCOUNT_IN_USE:" + phase.phaseCode);
            shifts.remove(shifts.size() - 1).delete();
        }
        while (shifts.size() < phase.requiredHeadcount) {
            ShiftEntity shift = new ShiftEntity(); shift.auditOrderPhaseId = phase.id; shift.locationId = phase.locationId;
            shift.startTime = phase.startTime; shift.endTime = phase.endTime; shift.auditOrderCode = order.orderCode;
            shift.auditStage = phase.phaseName; shift.persist(); shifts.add(shift);
        }
        for (int index = 0; index < shifts.size(); index++) {
            ShiftEntity shift = shifts.get(index); shift.locationId = phase.locationId; shift.startTime = phase.startTime;
            shift.endTime = phase.endTime; shift.auditOrderCode = order.orderCode; shift.auditStage = phase.phaseName;
            shift.requiredRole = phase.leadRequired && index == 0 ? "LEAD_AUDITOR" : "AUDITOR";
            ShiftSkillEntity.delete("shiftId", shift.id);
            for (Integer skillId : skillIds) {
                ShiftSkillEntity link = new ShiftSkillEntity(); link.shiftId = shift.id; link.skillId = skillId; link.skillTypeId = 1; link.persist();
            }
        }
    }

    private void upsertExistingAssignments(List<Map<String, String>> rows, Map<String, Integer> employees,
            Map<String, Integer> phases) {
        Map<Integer, Integer> nextSlot = new LinkedHashMap<>();
        for (Map<String, String> row : rows) {
            Integer phaseId = phases.get(value(row, "order_phase_id", "phase_code"));
            Integer employeeId = employees.get(value(row, "auditor_id", "employee_code"));
            if (phaseId == null || employeeId == null) continue;
            List<ShiftEntity> shifts = ShiftEntity.<ShiftEntity>find("auditOrderPhaseId = ?1 order by id", phaseId).list();
            int index = nextSlot.getOrDefault(phaseId, 0);
            if (index < shifts.size()) {
                ShiftEntity shift = shifts.get(index); shift.employeeId = employeeId;
                shift.pinned = bool(value(row, "锁定", "locked"), false);
                nextSlot.put(phaseId, index + 1);
            }
        }
    }

    private static String value(Map<String, String> row, String... names) {
        for (String name : names) {
            String value = row.get(name);
            if (value != null && !value.isBlank()) return value.trim();
        }
        return "";
    }

    private static String defaultText(String value, String fallback) { return value == null || value.isBlank() ? fallback : value; }

    /**
     * @brief 执行方式归一化：Excel 里既能填中文也能填英文枚举，统一落成 ONSITE/REMOTE/HYBRID。
     *
     * @details 库里存的是英文枚举（前端下拉的 option value）。模板给业务人员用的是中文，
     *          若原样写入会造成"下拉选不中"的历史问题，所以这里把两种写法都收进来；
     *          无法识别的一律按现场处理，且不阻断导入。
     */
    private static String normaliseDeliveryMode(String value) {
        String text = value == null ? "" : value.trim();
        if (text.isEmpty()) return "ONSITE";
        return switch (text.toUpperCase()) {
            case "REMOTE", "远程", "线上" -> "REMOTE";
            case "HYBRID", "混合", "半现场" -> "HYBRID";
            default -> "ONSITE";
        };
    }
    private static boolean bool(String value, boolean fallback) {
        if (value == null || value.isBlank()) return fallback;
        return Set.of("1", "TRUE", "YES", "Y", "是", "锁定").contains(value.trim().toUpperCase());
    }
    private static int integer(String value, int fallback) {
        if (value == null || value.isBlank()) return fallback;
        return (int) Double.parseDouble(value.trim());
    }
    private static Double decimal(String value) { return value == null || value.isBlank() ? null : Double.valueOf(value); }
    private static String cellText(Cell cell, DataFormatter formatter) {
        if (cell == null) return "";
        if (cell.getCellType() == org.apache.poi.ss.usermodel.CellType.NUMERIC && DateUtil.isCellDateFormatted(cell)) {
            LocalDateTime value = cell.getLocalDateTimeCellValue();
            return value.toLocalTime().equals(java.time.LocalTime.MIDNIGHT)
                    ? value.toLocalDate().format(DATE)
                    : value.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        }
        return formatter.formatCellValue(cell).trim();
    }
    private static LocalDate parseDate(String value) {
        String normalized = value.trim();
        for (DateTimeFormatter formatter : List.of(DATE, DateTimeFormatter.ofPattern("M/d/yy"),
                DateTimeFormatter.ofPattern("M/d/yyyy"), DateTimeFormatter.ofPattern("yyyy/M/d"))) {
            try { return LocalDate.parse(normalized.length() > 10 ? normalized.substring(0, 10) : normalized, formatter); }
            catch (Exception ignored) { }
        }
        throw new IllegalArgumentException("DATE_INVALID");
    }
    private static String dateText(String value) { return value == null || value.isBlank() ? null : parseDate(value).format(DATE); }
    private static List<String> splitQualifications(String value) {
        if (value == null || value.isBlank()) return List.of();
        List<String> result = new ArrayList<>();
        for (String part : value.split("[,;|、，；]+")) if (!part.trim().isBlank()) result.add(part.trim());
        return result;
    }
    private static String normalizeQualificationStatus(String value) {
        String normalized = defaultText(value, "ACTIVE").trim().toUpperCase();
        if (Set.of("有效", "正常", "VALID").contains(normalized)) return "ACTIVE";
        if (Set.of("即将到期", "临期", "EXPIRING_SOON").contains(normalized)) return "EXPIRING";
        if (Set.of("过期", "失效", "INVALID").contains(normalized)) return "EXPIRED";
        if (Set.of("暂停", "停用").contains(normalized)) return "SUSPENDED";
        return normalized;
    }
    private static void require(Parsed parsed, String sheet, Map<String, String> row, String field, String value, String code) {
        if (value == null || value.isBlank()) issue(parsed, sheet, row, field, "ERROR", code, "必填字段为空");
    }
    private static void issue(Parsed parsed, String sheet, Map<String, String> row, String field,
            String severity, String code, String message) {
        parsed.issues.add(new ImportIssue(sheet, integer(row.get("__row"), 0), field, severity, code, message));
    }

    private static final class Parsed {
        final Map<String, List<Map<String, String>>> tables = new LinkedHashMap<>();
        final List<ImportIssue> issues = new ArrayList<>();
        List<Map<String, String>> rows(String key) { return tables.getOrDefault(key, List.of()); }
        int totalRows() { return tables.values().stream().mapToInt(List::size).sum(); }
        Map<String, Integer> summary() {
            Map<String, Integer> result = new LinkedHashMap<>();
            tables.forEach((key, value) -> result.put(key, value.size()));
            return result;
        }
    }
}
