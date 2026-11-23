package org.acme.employeescheduling.rest;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.acme.employeescheduling.dto.WorkflowDtos.ImportIssue;
import org.acme.employeescheduling.dto.WorkflowDtos.ImportPreview;
import org.acme.employeescheduling.persistence.ImportJobEntity;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/** Two-step XLSX import: preview with row-level issues, then explicit confirmation. */
@Path("/imports")
@RolesAllowed({"ADMIN", "CAPOSALA", "DISPATCHER"})
public class ImportResource {
    @Inject ImportWorkbookService importer;
    @Inject SecurityIdentity identity;
    @Inject ObjectMapper objectMapper;
    @Inject DatabaseBackupService backupService;
    @Inject AuditTrailService auditTrail;
    @Inject DispatchEventHub events;
    @Inject DemoDataRepository demoDataRepository;

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public List<Map<String, Object>> list(@QueryParam("structureId") int structureId) {
        return ImportJobEntity.<ImportJobEntity>find("structureId = ?1 order by createdAt desc", structureId)
                .page(0, 100).list().stream().map(job -> Map.<String, Object>of(
                        "token", job.token, "fileName", job.fileName, "status", job.status,
                        "rowCount", job.rowCount, "errorCount", job.errorCount,
                        "warningCount", job.warningCount, "createdBy", job.createdBy,
                        "createdAt", job.createdAt)).toList();
    }

    @GET
    @Path("/template")
    @Produces("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    public Response template() {
        return Response.ok(importer.template())
                .header("Content-Disposition", "attachment; filename=auditor-dispatch-import-template.xlsx").build();
    }

    @POST
    @Path("/preview")
    @Consumes({"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", MediaType.APPLICATION_OCTET_STREAM})
    @Produces(MediaType.APPLICATION_JSON)
    @Transactional
    public Response preview(@QueryParam("structureId") int structureId, @QueryParam("fileName") String fileName,
            byte[] bytes) {
        try {
            ImportPreview preview = importer.preview(bytes, fileName, structureId, actor());
            auditTrail.record(structureId, "IMPORT_PREVIEWED", "IMPORT_JOB", preview.token(),
                    Map.of("fileName", preview.fileName(), "errors", preview.errorCount(), "warnings", preview.warningCount()));
            return Response.ok(preview).build();
        } catch (IllegalArgumentException exception) {
            return ApiErrors.badRequest(exception.getMessage() == null ? "IMPORT_FILE_INVALID" : exception.getMessage());
        }
    }

    @POST
    @Path("/{token}/confirm")
    @Produces(MediaType.APPLICATION_JSON)
    @Transactional
    public Response confirm(@PathParam("token") String token, @QueryParam("structureId") int structureId) {
        ImportJobEntity job = ImportJobEntity.find("token = ?1 and structureId = ?2", token, structureId).firstResult();
        if (job == null) return ApiErrors.notFound("IMPORT_NOT_FOUND");
        if (job.errorCount > 0) return ApiErrors.conflict("IMPORT_HAS_ERRORS");
        if (!"PREVIEW".equals(job.status)) return ApiErrors.conflict("IMPORT_ALREADY_CONFIRMED");
        SafetyBackupOutcome backup = backupService.safetyBackup("preimport");
        if (!backup.isOk()) return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                .entity(Map.of("error", backup.errorCode())).build();
        try {
            Map<String, Object> summary = importer.confirm(job);
            // The solver keeps a read cache of employees. Import confirmation changes employees,
            // qualifications and availability in one transaction, so invalidate only after commit.
            demoDataRepository.invalidateEmployeesAfterCommit();
            job.confirmedBy = actor();
            job.confirmedAt = WorkflowSupport.now();
            auditTrail.record(structureId, "IMPORT_CONFIRMED", "IMPORT_JOB", job.token, summary);
            events.publish("import", Map.of("structureId", structureId, "token", token, "status", "CONFIRMED"));
            return Response.ok(Map.of("token", token, "status", "CONFIRMED", "summary", summary)).build();
        } catch (IllegalStateException exception) {
            return ApiErrors.conflict(exception.getMessage() == null ? "IMPORT_FAILED" : exception.getMessage());
        }
    }

    @GET
    @Path("/{token}/errors")
    @Produces("text/csv; charset=utf-8")
    public Response errors(@PathParam("token") String token, @QueryParam("structureId") int structureId) {
        ImportJobEntity job = ImportJobEntity.find("token = ?1 and structureId = ?2", token, structureId).firstResult();
        if (job == null) return ApiErrors.notFound("IMPORT_NOT_FOUND");
        try {
            List<ImportIssue> issues = objectMapper.readValue(job.errorsJson, new TypeReference<>() { });
            StringBuilder csv = new StringBuilder("sheet,row,field,severity,code,message\r\n");
            for (ImportIssue issue : issues) csv.append(csv(issue.sheet())).append(',').append(issue.row()).append(',')
                    .append(csv(issue.field())).append(',').append(issue.severity()).append(',')
                    .append(issue.code()).append(',').append(csv(issue.message())).append("\r\n");
            return Response.ok(csv.toString().getBytes(StandardCharsets.UTF_8))
                    .header("Content-Disposition", "attachment; filename=import-errors.csv").build();
        } catch (Exception exception) {
            return ApiErrors.serverError("IMPORT_ERRORS_INVALID");
        }
    }

    private String actor() {
        return identity == null || identity.isAnonymous() ? "system" : identity.getPrincipal().getName();
    }

    private static String csv(String value) {
        String text = value == null ? "" : value;
        return '"' + text.replace("\"", "\"\"") + '"';
    }
}
