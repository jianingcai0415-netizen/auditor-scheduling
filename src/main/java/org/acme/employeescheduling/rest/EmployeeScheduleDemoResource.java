package org.acme.employeescheduling.rest;


import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import io.quarkus.security.identity.SecurityIdentity;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.DefaultValue;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import java.time.LocalDateTime;
import java.time.LocalDate;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.acme.employeescheduling.domain.EmployeeSchedule;
import org.acme.employeescheduling.dto.Skill;
import org.acme.employeescheduling.dto.ShiftTemplate;
import org.acme.employeescheduling.dto.Location;
import org.acme.employeescheduling.dto.ShiftWithLocations;
import org.acme.employeescheduling.dto.Employee;
import org.acme.employeescheduling.dto.AuditorQualification;
import org.acme.employeescheduling.dto.EmployeeStatusUpdate;
import org.acme.employeescheduling.dto.EmployeeDate;
import org.acme.employeescheduling.dto.PdfTemplate;
import org.acme.employeescheduling.domain.Shift;
import org.acme.employeescheduling.persistence.SkillEntity;
import org.acme.employeescheduling.persistence.LocationEntity;
import org.acme.employeescheduling.persistence.LocationSkillEntity;
import org.acme.employeescheduling.persistence.LocalizzazioneEntity;
import org.acme.employeescheduling.persistence.EmployeeEntity;
import org.acme.employeescheduling.persistence.EmployeeSkillEntity;
import org.acme.employeescheduling.persistence.EmployeeDateEntity;
import org.acme.employeescheduling.persistence.EmailLogEntity;
import org.acme.employeescheduling.persistence.ShiftEntity;
import org.acme.employeescheduling.persistence.ShiftSkillEntity;
import org.acme.employeescheduling.persistence.ShiftTemplateEntity;
import org.acme.employeescheduling.persistence.ShiftTemplateSkillEntity;
import org.acme.employeescheduling.persistence.ShiftTemplateHeaderEntity;
import org.acme.employeescheduling.persistence.SchedulePlanAssignmentEntity;
import org.acme.employeescheduling.persistence.SchedulePlanEntity;
import org.acme.employeescheduling.persistence.StructureEntity;
import org.acme.employeescheduling.security.RichHtmlSanitizer;
import org.acme.employeescheduling.utils.TravelMatrix;
import io.quarkus.panache.common.Sort;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import org.jboss.logging.Logger;







/**
 * @brief REST resource for managing demo data including skills, locations, shifts, employees, and dates.
 * @details Provides JAX-RS endpoints for full CRUD operations on scheduling demo data.
 *          Supports generating unsolved schedules, managing skills, locations with
 *          associated skills, shifts with required/optional competencies, employees
 *          with skill associations, and employee/shift date management.
 * @author Employee Scheduling Team
 * @version 1.0
 */
@Tag(name = "Demo data", description = "Endpoints for managing demo data and skills.")
@RolesAllowed({"ADMIN", "CAPOSALA", "DISPATCHER"})
@Path("/demo-data")
@Produces(MediaType.APPLICATION_JSON)
public class EmployeeScheduleDemoResource {


private final DemoDataRepository demoDataRepository;

/** @brief Safety backup before destructive operations (best effort). */
@Inject
DatabaseBackupService backupService;

@Inject
AuditTrailService auditTrail;

@Inject
DispatchEventHub dispatchEvents;

/** 简历附件的落盘/读取（PDF 放文件系统，库里只留引用）。 */
@Inject
ResumeStorage resumeStorage;

/**
 * @brief 求解时是否只排已批准订单的班次。
 *
 * @details 默认关闭（false），行为与改造前完全一致。打开后，未批准订单的班次不会进入求解，
 * 但仍会出现在排班管理页的时间轴上 —— 只是"还不能排"。用配置而不是硬编码，
 * 是为了能在不重新构建的情况下灰度启用或立即回退。
 */
@ConfigProperty(name = "app.solver.require-approved-orders", defaultValue = "false")
boolean requireApprovedOrders;

/** 用于把「谁保存了结果」写进方案定稿记录。 */
@Inject
SecurityIdentity identity;

/** Entity manager used only for portable JPQL/HQL projections. */
@Inject
EntityManager em;

private static final Logger logger = Logger.getLogger(EmployeeScheduleDemoResource.class);
private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
/** Maximum window to which a template can be applied: the UI works by week/month. */
private static final int MAX_TEMPLATE_WINDOW_DAYS = 62;
/** @brief DB datetime format (same dbFormatter as the legacy repository). */
private static final java.time.format.DateTimeFormatter DB_FORMATTER =
        java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

private static boolean isValidEmail(String email) {
    return email == null || email.isBlank() || EMAIL_PATTERN.matcher(email.trim()).matches();
}

/** @brief True if a required field is absent or consists only of whitespace. */
private static boolean isMissing(String value) {
    return value == null || value.trim().isEmpty();
}

/**
 * @brief Checks required employee fields during both creation and editing.
 * @details First name, last name, and code identify the person on every screen (shift grid,
 *          PDF report, email). Accepting empty values creates indistinguishable anonymous rows
 *          that the user can no longer correct from the list.
 * @return the error response to return, or null if the fields are valid.
 */
private static Response validateEmployeeFields(Employee employee) {
    if (isMissing(employee.getFirstName())) return ApiErrors.badRequest("EMPLOYEE_FIRST_NAME_REQUIRED");
    if (isMissing(employee.getLastName())) return ApiErrors.badRequest("EMPLOYEE_LAST_NAME_REQUIRED");
    if (isMissing(employee.getCode())) return ApiErrors.badRequest("EMPLOYEE_CODE_REQUIRED");
    return null;
}


/**
 * @brief Constructs the demo resource with an injected DemoDataRepository.
 * @param demoDataRepository the repository providing database access for demo data
 */
@Inject
public EmployeeScheduleDemoResource(DemoDataRepository demoDataRepository) {
    this.demoDataRepository = demoDataRepository;
}

    


    
    

    
/**
 * @brief Generates an unsolved demo schedule from database data.
 * @return a Response containing the generated EmployeeSchedule in JSON format
 */
@APIResponse(
    responseCode = "200",
description = "Unsolved demo schedule.",
    content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = EmployeeSchedule.class))
)
@Operation(summary = "Generate an unsolved demo schedule using data from the database.")
@GET
@Path("/generate")
public Response generateDemoSchedule(
        @QueryParam("structureId") @DefaultValue("0") int structureId,
        @QueryParam("start") String start,
        @QueryParam("end") String end,
        @QueryParam("activeOnly") @DefaultValue("false") boolean activeOnly,
        @QueryParam("context") @DefaultValue("false") boolean context) {
    // Optional start/end: when present, shifts are filtered to the window; when absent, all
    // shifts are returned (backward compatible with Locations/Employees/Report).
    // activeOnly=true (Shift Management + solver): excludes disabled employees/locations.
    // context=true (solver payload only): adds shifts adjacent to the window as pinned context
    // (SolverSettings.context_days), marked context=true.
    LocalDateTime windowStart = parseWindowBound(start);
    LocalDateTime windowEnd = parseWindowBound(end);
    boolean hasStart = start != null && !start.isBlank();
    boolean hasEnd = end != null && !end.isBlank();
    if (hasStart != hasEnd || (hasStart && (windowStart == null || windowEnd == null))) {
        return Response.status(Response.Status.BAD_REQUEST)
                .entity(Map.of("message", "start and end must be valid date-time bounds and must be provided together"))
                .build();
    }
    if (windowStart != null && !windowEnd.isAfter(windowStart)) {
        return Response.status(Response.Status.BAD_REQUEST)
                .entity(Map.of("message", "end must be after start"))
                .build();
    }
    EmployeeSchedule schedule = demoDataRepository.generateDemoData(structureId, windowStart, windowEnd, activeOnly,
            context, requireApprovedOrders);
    return Response.ok(schedule).build();
}

/**
 * @brief Returns the first/last shift date for a structure (min/max), lightweight.
 * @details The frontend uses it to position the timeline on the month of the first shift without
 *          downloading every shift.
 */
@GET
@Path("/shift-date-range")
public Response getShiftDateRange(@QueryParam("structureId") @DefaultValue("0") int structureId) {
    return Response.ok(demoDataRepository.getShiftDateRange(structureId)).build();
}

/** Lightweight list of employee/location IDs referenced by shifts. */
@GET
@Path("/usage")
public Response getScheduleUsage(@QueryParam("structureId") @DefaultValue("0") int structureId) {
    return Response.ok(demoDataRepository.getScheduleUsage(structureId)).build();
}

/**
 * @brief Parses a window bound tolerating multiple formats; null/blank/invalid becomes null.
 * @details Accepts "yyyy-MM-dd HH:mm:ss" and ISO "yyyy-MM-ddTHH:mm:ss", with optional 'Z' and fractions.
 */
private static LocalDateTime parseWindowBound(String value) {
    if (value == null || value.isBlank()) return null;
    String v = value.trim().replace(' ', 'T');
    if (v.endsWith("Z")) v = v.substring(0, v.length() - 1);
    int dot = v.indexOf('.');
    if (dot > 0) v = v.substring(0, dot); // Remove milliseconds.
    try {
        return LocalDateTime.parse(v); // ISO_LOCAL_DATE_TIME (optional seconds).
    } catch (Exception e) {
        return null;
    }
}

    
    
   
    
    
    
    
/**
 * @brief Retrieves all available skills.
 * @return a Response containing a list of Skill objects in JSON format
 */
@APIResponse(
     responseCode = "200",
     description = "List of all skills.",
    content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = Skill.class))
)
@Operation(summary = "Retrieve a list of all skills.")
@GET
@Path("/get_skills")
    public Response getSkills(@QueryParam("structureId") @DefaultValue("0") int structureId) {
        // ORM (Panache): same response as legacy (ordered by skill_order, fixed used=true as in
        // the old getSkills — see SkillEntity.toDto()).
        List<Skill> skills = listSkillsForStructure(structureId);
        return Response.ok(skills).build();
    }









/**
 * @brief Saves a list of skills (insert new or update existing).
 *
 * @details ADMIN only, overriding the class-level annotation. The skill catalogue is
 *          administrative: head nurses assign skills, they do not create or rename them.
 *          That was enforced only in the interface — ConfigPage redirects non-admins — so
 *          a head nurse with curl could create and rename skills for the whole structure.
 *          LocalizzazioneResource, which renames the same skills in all six languages, has
 *          been ADMIN-only from the start.
 *
 * @param skills the list of Skill objects to save
 * @return a Response indicating success or failure of the save operation
 */
@POST
@RolesAllowed("ADMIN")
@Path("/save_skills")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
@Transactional
    public Response saveSkills(List<Skill> skills,
            @QueryParam("structureId") @DefaultValue("0") int structureId) {

    logger.debug("JSON ricevuto dal frontend: " + skills);

        if (skills == null || skills.stream().anyMatch(skill -> skill == null)) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("message", "Payload skills non valido")).build();
        }
        if (structureId <= 0 || !structureExists(structureId)) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("message", "Struttura non valida")).build();
        }

        try {
            // ORM (Panache): batch upsert with legacy semantics — id==0 = new skill (INSERT),
            // id!=0 = update; a nonexistent ID is silently ignored (like the legacy UPDATE
            // affecting zero rows).
            for (Skill skill : skills) {
            SkillEntity entity = null;
            if (skill.getId() == 0) {
                entity = new SkillEntity();
                entity.structureId = structureId;
                entity.applyDto(skill);
                entity.persist();
            } else {
                entity = SkillEntity.findById(skill.getId());
                // Skill from another structure: ignore it rather than modify it. The catalog used
                // to be shared, so renaming here changed it for every structure; now each sees and
                // modifies only its own skills.
                if (entity != null
                        && (entity.structureId == null || entity.structureId != structureId)) {
                    continue;
                }
                if (entity != null) entity.applyDto(skill);
            }
                if (skill.getTranslationLanguageId() != null && skill.getTranslationValue() != null
                        && !skill.getTranslationValue().isBlank()) {
                    upsertSkillTranslation(entity != null ? entity.id : skill.getId(),
                            skill.getTranslationLanguageId(), skill.getTranslationValue());
                }
            }

        // Cached employees incorporate the complete skill catalog: rename/reorder/activation
        // must regenerate them (same rule as legacy).
        demoDataRepository.invalidateEmployeesAfterCommit();
        demoDataRepository.invalidateTranslationsAfterCommit();
        return Response.ok().entity(Map.of("message", "Skills salvate con successo!")).build();

    } catch (Exception e) {

        logger.error("Errore nel salvataggio delle skills", e);

        return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(Map.of("message", "Errore nel salvataggio delle skills")).build();

    }
}












/**
 * @brief Retrieves all available locations.
 * @return a Response containing a list of Location objects in JSON format
 */
@APIResponse(
	responseCode = "200",
description = "List of all locations.",
    content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = Location.class))
)
@Operation(summary = "Retrieve a list of all locations.")
@GET
@Path("/getlocations")
public Response getLocations(@QueryParam("structureId") @DefaultValue("0") int structureId) {
    return Response.ok(buildLocationDtos(structureId)).build();
}

/**
 * @brief Structure locations ordered by l_order, with ONLY associated skills (used=true) ordered
 *        by skill_order — same shape as legacy getLocations. Reused by /getlocations and
 *        /editshift (ShiftWithLocations).
 */
private List<Location> buildLocationDtos(int structureId) {
    List<LocationEntity> entities = LocationEntity.<LocationEntity>list(
            "structureId = ?1", Sort.by("displayOrder"), structureId);
    Map<Integer, Location> byId = new HashMap<>();
    List<Location> locations = new ArrayList<>();
    for (LocationEntity e : entities) {
        Location dto = e.toDto();
        dto.setRequiredSkill(new ArrayList<>());
        dto.setOptionalSkill(new ArrayList<>());
        byId.put(e.id, dto);
        locations.add(dto);
    }
    // Theta-join JPQL bridge×skills×locations (no @ManyToOne in the bridge).
    List<Object[]> rows = em.createQuery(
            "select ls.locationId, ls.skillTypeId, s.id, s.name, s.skillOrder, s.active " +
            "from LocationSkillEntity ls, SkillEntity s, LocationEntity l " +
            "where s.id = ls.skillId and l.id = ls.locationId and l.structureId = ?1 " +
            "order by s.skillOrder", Object[].class)
            .setParameter(1, structureId).getResultList();
    for (Object[] r : rows) {
        Location location = byId.get(((Number) r[0]).intValue());
        if (location == null) continue;
        Skill skill = new Skill(((Number) r[2]).intValue(), (String) r[3],
                r[4] != null ? ((Number) r[4]).intValue() : 0, true, (Boolean) r[5]);
        if (((Number) r[1]).intValue() == LocationSkillEntity.TYPE_REQUIRED) location.getRequiredSkill().add(skill);
        else location.getOptionalSkill().add(skill);
    }
    return locations;
}







/**
 * @brief Adds a new location with its required and optional skills.
 * @details Validates the location name and order, persists the location,
 *          and associates required and optional skills if provided.
 * @param location the Location object containing name, order, and skills
 * @return a Response with HTTP 201 on success, or HTTP 400 for invalid data
 */
@POST
@Path("/addlocation")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
@Transactional
public Response addLocation(Location location, @QueryParam("structureId") @DefaultValue("0") int structureId) {
	if (!structureExists(structureId)) return Response.status(Response.Status.NOT_FOUND).build();
	if (location == null) return Response.status(Response.Status.BAD_REQUEST).build();

	if (isMissing(location.getName())) {
	    return ApiErrors.badRequest("LOCATION_NAME_REQUIRED");
	}
	if (location.getOrder() <= 0) {
	    return ApiErrors.badRequest("LOCATION_ORDER_REQUIRED");
	}
	if (!validSkillLists(location.getRequiredSkill(), location.getOptionalSkill())) {
	    return ApiErrors.badRequest("LOCATION_SKILLS_INVALID");
	}
	if (!validCoordinates(location)) return ApiErrors.badRequest("LOCATION_COORDINATES_INVALID");

	if (location.getCode() != null && !location.getCode().trim().isEmpty()
	        && LocationEntity.count("code = ?1 and id != ?2", location.getCode().trim(), 0) > 0) {
	    return ApiErrors.conflict("LOCATION_CODE_IN_USE");
	}

	logger.debug("Payload ricevuto: " + location);

    // ORM (Panache): insert location + associated skills in ONE transaction (legacy used separate
    // connections). active keeps the true default (parity: the legacy INSERT did not set the
    // column -> default 1).
    LocationEntity entity = new LocationEntity();
    entity.name = location.getName();
    entity.displayOrder = location.getOrder();
    entity.code = location.getCode();
    entity.latitude = location.getLatitude();
    entity.longitude = location.getLongitude();
    entity.structureId = structureId;
    entity.persist();

    addLocationSkillRows(entity.id, location.getRequiredSkill(), LocationSkillEntity.TYPE_REQUIRED);
    addLocationSkillRows(entity.id, location.getOptionalSkill(), LocationSkillEntity.TYPE_OPTIONAL);

    // New site joins the driving-time matrix: drop the cache so the next scoring/solve recomputes.
    TravelMatrix.invalidateAfterCommit();

    // ID only: the frontend composes the success message in the user's language.
    return Response.status(Response.Status.CREATED).entity(Map.of("id", entity.id)).build();

}

/**
 * @brief Associates skills with a location (id>0 filter as in legacy; null/empty ignored).
 * @details The structure is derived from the location, not the request parameter: identifiers
 *          come from the client, and a skill belonging to another company must be discarded,
 *          not associated.
 */
private static void addLocationSkillRows(int locationId, List<Skill> skills, int skillTypeId) {
    if (skills == null || skills.isEmpty()) return;
    java.util.Set<Integer> owned = ownedSkillIds(structureOfLocation(locationId));
    for (Skill skill : skills) {
        if (skill == null || skill.getId() <= 0 || !owned.contains(skill.getId())) continue;
        LocationSkillEntity row = new LocationSkillEntity();
        row.locationId = locationId;
        row.skillId = skill.getId();
        row.skillTypeId = skillTypeId;
        row.persist();
    }
}







/**
 * @brief Retrieves a specific location by its ID.
 * @param location_id the unique identifier of the location
 * @return a Response containing the Location in JSON, or HTTP 404 if not found
 */
@GET
@Path("/getlocation/{id}")
@Produces(MediaType.APPLICATION_JSON)
public Response getLocation(@PathParam("id") int location_id,
        @QueryParam("structureId") @DefaultValue("0") int structureId) {
    LocationEntity entity = LocationEntity.findById(location_id);
    if (entity == null || structureId <= 0 || entity.structureId != structureId) {
        return Response.status(Response.Status.NOT_FOUND).build();
    }
    // Legacy parity: requiredSkill/optionalSkill = ENTIRE CATALOG with used=true only on skills
    // associated with that type (needed by the edit modal).
    Location location = entity.toDto();
    location.setRequiredSkill(catalogWithUsedFlag(location_id, LocationSkillEntity.TYPE_REQUIRED));
    location.setOptionalSkill(catalogWithUsedFlag(location_id, LocationSkillEntity.TYPE_OPTIONAL));
    return Response.ok(location).build();
}

/** @brief Complete skill catalog (natural order), with used=true on associations of the given type. */
private static List<Skill> catalogWithUsedFlag(int locationId, int skillTypeId) {
    java.util.Set<Integer> usedIds = LocationSkillEntity.<LocationSkillEntity>list(
            "locationId = ?1 and skillTypeId = ?2", locationId, skillTypeId)
            .stream().map(ls -> ls.skillId).collect(java.util.stream.Collectors.toSet());
    // Catalog of the location's structure: with listAll(), the modal would also show skills from
    // other companies, which cannot even be assigned here.
    return SkillEntity.<SkillEntity>list("structureId", structureOfLocation(locationId)).stream()
            .map(s -> new Skill(s.id, s.name, s.skillOrder != null ? s.skillOrder : 0,
                    usedIds.contains(s.id), s.active))
            .toList();
}






/**
 * @brief Updates an existing location by its ID.
 * @details Validates that the path ID matches the body ID, then delegates
 *          the update to the repository.
 * @param location_id the unique identifier of the location to update
 * @param location the updated Location data
 * @return a Response with HTTP 200 on success, HTTP 400 for ID mismatch, or HTTP 404 if not found
 */
@APIResponse(
    responseCode = "200",
    description = "Location updated successfully.",
    content = @Content(mediaType = MediaType.APPLICATION_JSON)
)
@Operation(summary = "Update Location with id.")
@PUT
@Path("/updatelocation/{id}")
@Consumes(MediaType.APPLICATION_JSON)
@Transactional
public Response updateLocation(@PathParam("id") int location_id, Location location,
        @QueryParam("structureId") @DefaultValue("0") int structureId) {

	if (location == null || structureId <= 0 || location.getId() != location_id) {
	    return Response.status(Response.Status.BAD_REQUEST).entity("ID mismatch").build();
	}

	if (location.getCode() != null && !location.getCode().trim().isEmpty()
	        && LocationEntity.count("code = ?1 and id != ?2", location.getCode().trim(), location_id) > 0) {
	    return ApiErrors.conflict("LOCATION_CODE_IN_USE");
	}

	LocationEntity entity = LocationEntity.findById(location_id);
	if (entity == null || entity.structureId != structureId) {
	    return Response.status(Response.Status.NOT_FOUND).build();
	}
	// Same checks as creation, but reported individually: editing is where the user corrects an
	// error and needs to know WHICH field was rejected.
	if (isMissing(location.getName())) {
	    return ApiErrors.badRequest("LOCATION_NAME_REQUIRED");
	}
	if (location.getOrder() <= 0) {
	    return ApiErrors.badRequest("LOCATION_ORDER_REQUIRED");
	}
	if (!validSkillLists(location.getRequiredSkill(), location.getOptionalSkill())) {
	    return ApiErrors.badRequest("LOCATION_SKILLS_INVALID");
	}
	if (!validCoordinates(location)) return ApiErrors.badRequest("LOCATION_COORDINATES_INVALID");
	// Validate every reference before replacing skills.
	entity.applyDto(location); // Do NOT change structure_id (legacy parity).
	LocationSkillEntity.delete("locationId", location_id);
	addLocationSkillRows(location_id, location.getRequiredSkill(), LocationSkillEntity.TYPE_REQUIRED);
	addLocationSkillRows(location_id, location.getOptionalSkill(), LocationSkillEntity.TYPE_OPTIONAL);
	// Coordinates / active flag may have moved: the cached driving-time matrix is now stale.
	TravelMatrix.invalidateAfterCommit();
	return Response.ok().build();

}





/**
 * @brief Deletes a location by its ID.
 * @param id the unique identifier of the location to delete
 * @return a Response with HTTP 204 on success, HTTP 409 if the location is still
 *         referenced by shifts or shift templates, or HTTP 404 if not found
 */
@DELETE
@Path("/deletelocation/{id}")
@Transactional
public Response deleteLocation(@PathParam("id") int id,
        @QueryParam("structureId") @DefaultValue("0") int structureId) {
    if (!locationBelongsToStructure(id, structureId)) {
        return Response.status(Response.Status.NOT_FOUND).build();
    }
    long refs = ShiftEntity.count("locationId", id) + ShiftTemplateEntity.count("locationId", id);
    if (refs > 0) {
        return ApiErrors.conflict("LOCATION_IN_USE");
    }
    // Cascade (associated skills + name translations) + location deletion in ONE transaction
    // (legacy kept localizations and skills in the same transaction, with the check outside).
    LocationSkillEntity.delete("locationId", id);
    LocalizzazioneEntity.delete("entityType = ?1 and entityId = ?2", "locations", id);
    boolean isDeleted = LocationEntity.deleteById(id);
    if (isDeleted) {
        // The removed site must disappear from the driving-time matrix too.
        TravelMatrix.invalidateAfterCommit();
        return Response.status(Response.Status.NO_CONTENT).build();
    } else {
        return Response.status(Response.Status.NOT_FOUND).build();
    }
}




/**
 * @brief Retrieves required and optional skills available for location assignment.
 * @return a Response containing a map with "requiredSkills" and "optionalSkills" lists
 */
@GET
@Path("/skills-for-location")
@Produces(MediaType.APPLICATION_JSON)
    public Response getSkillsForLocation(@QueryParam("structureId") @DefaultValue("0") int structureId) {
        // ORM (Panache): complete catalog for both types (legacy parity with
        // getRequiredSkills/getOptionalSkills: same query, fixed used=true).
        Map<String, List<Skill>> skillsForShift = new HashMap<>();
        List<Skill> catalog = listSkillsForStructure(structureId);
        skillsForShift.put("requiredSkills", catalog);
        skillsForShift.put("optionalSkills", catalog);
        return Response.ok(skillsForShift).build();
    }

    /**
     * @brief Structure skill catalog, ordered by skill_order.
     * @details Skills belong to one structure (migration V5): without structureId there is no
     *          meaningful catalog to return, and a global list would mix skills from different
     *          companies. Return an empty list.
     */
    private List<Skill> listSkillsForStructure(int structureId) {
        if (structureId <= 0) return new ArrayList<>();
        return SkillEntity.<SkillEntity>list("structureId", Sort.by("skillOrder"), structureId).stream()
                .map(SkillEntity::toDto).toList();
    }

    /** @brief true if the skill exists and belongs to that structure. */
    private static boolean skillBelongsToStructure(int skillId, int structureId) {
        if (skillId <= 0 || structureId <= 0) return false;
        return SkillEntity.count("id = ?1 and structureId = ?2", skillId, structureId) > 0;
    }

    /**
     * @brief Identifiers of skills owned by the structure.
     *
     * @details Filter applied to EVERY assignment (employees, locations, shifts, templates):
     *          identifiers come from the client, and since skills belong to a structure, an ID
     *          from another company is no longer legitimate. The interface does not offer it,
     *          but the API would accept it. One query instead of one per skill.
     */
    private static java.util.Set<Integer> ownedSkillIds(int structureId) {
        if (structureId <= 0) return java.util.Set.of();
        return SkillEntity.<SkillEntity>list("structureId", structureId).stream()
                .map(s -> s.id).collect(java.util.stream.Collectors.toSet());
    }

    /** @brief Structure owning a location (0 if the location does not exist). */
    private static int structureOfLocation(int locationId) {
        LocationEntity location = LocationEntity.findById(locationId);
        return location == null ? 0 : location.structureId;
    }

    /** @brief Structure owning a shift, determined through its location. */
    private static int structureOfShift(int shiftId) {
        ShiftEntity shift = ShiftEntity.findById(shiftId);
        return shift == null ? 0 : structureOfLocation(shift.locationId);
    }


/**
 * @brief Returns the next suggested location code (e.g. "LOC015").
 */
@GET
@Path("/next-location-code")
@Produces(MediaType.APPLICATION_JSON)
public Response getNextLocationCode() {
    // Legacy parity (GLOB 'LOC[0-9]*'): prefilter with LIKE; parsing discards nonconforming values.
    int max = LocationEntity.<LocationEntity>list("code like 'LOC%'").stream()
            .map(l -> l.code.substring(3))
            .mapToInt(numPart -> {
                try { return Integer.parseInt(numPart); } catch (NumberFormatException e) { return 0; }
            })
            .max().orElse(0);
    return Response.ok("{\"code\": \"" + String.format("LOC%03d", max + 1) + "\"}").build();
}

/**
 * @brief Returns the next suggested employee code (e.g. "EMP023").
 */
@GET
@Path("/next-employee-code")
@Produces(MediaType.APPLICATION_JSON)
public Response getNextEmployeeCode() {
    // Legacy parity (GLOB 'EMP[0-9]*'): prefilter with LIKE; parsing discards nonconforming values.
    int max = EmployeeEntity.<EmployeeEntity>list("code like 'EMP%'").stream()
            .map(e -> e.code.substring(3))
            .mapToInt(numPart -> {
                try { return Integer.parseInt(numPart); } catch (NumberFormatException ex) { return 0; }
            })
            .max().orElse(0);
    return Response.ok("{\"code\": \"" + String.format("EMP%03d", max + 1) + "\"}").build();
}






/**
 * @brief Retrieves skills for a specific shift filtered by skill type.
 * @param shift_id the unique identifier of the shift
 * @param skill_type_id the type of skills to retrieve (1 = required, 2 = optional)
 * @return a Response containing a map with a "skills" list, or HTTP 500 on error
 */
@GET
@Path("/getskillsforshift/{shift_id}/{skill_type_id}")
@Produces(MediaType.APPLICATION_JSON)
public Response getSkillsForShiftFromId(@PathParam("shift_id") int shift_id,
        @PathParam("skill_type_id") int skill_type_id,
        @QueryParam("structureId") @DefaultValue("0") int structureId) {

	try {
        if (!shiftBelongsToStructure(shift_id, structureId))
            return Response.status(Response.Status.NOT_FOUND).build();
        if (skill_type_id != LocationSkillEntity.TYPE_REQUIRED
                && skill_type_id != LocationSkillEntity.TYPE_OPTIONAL)
            return Response.status(Response.Status.BAD_REQUEST).build();
        // ORM (Panache): catalog with used flag by type (legacy LEFT JOIN parity).
        List<Skill> skills = shiftSkillCatalog(shift_id, skill_type_id);

        Map<String, List<Skill>> skillsForShift = new HashMap<>();

        skillsForShift.put("skills", skills);

        return Response.ok(skillsForShift).build();

    } catch (Exception e) {
        logger.error("Errore durante il recupero delle skill", e);
        return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(Map.of("error", "Errore durante il recupero delle skill")).build();
    }
}







/**
 * @brief Retrieves all shifts for a specific location.
 * @param location_id the unique identifier of the location
 * @return a Response containing a list of Shift objects, or HTTP 400/404/500 on error
 */
@APIResponse(
	    responseCode = "200",
	    description = "Get Detail of given Location Shifts.",
	    content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = Shift.class))
	)
	@APIResponse(responseCode = "400", description = "Invalid location ID.")
	@APIResponse(responseCode = "404", description = "No shifts found for the given location.")
	@APIResponse(responseCode = "500", description = "An unexpected error occurred.")
	@Operation(summary = "Retrieve shifts for a specific location.")
	@GET
	@Path("/get_location_shifts/{location_id}")
	@Produces(MediaType.APPLICATION_JSON)
	public Response getLocationDates(@PathParam("location_id") int location_id,
	        @QueryParam("structureId") @DefaultValue("0") int structureId) {

	    //logger.info("Fetching shifts for Location ID: " + location_id);

	    // Validate input
	    if (location_id <= 0 || !locationBelongsToStructure(location_id, structureId)) {
	        logger.warn("Invalid Location ID: " + location_id);
	        return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("error", "Invalid Location ID. Must be a positive integer.")).build();
	    }


	    try {
	        // Retrieve shift details for the given location
	        List<Shift> locationShifts = demoDataRepository.getLocationShiftsOrm(location_id);

	        if (locationShifts == null || locationShifts.isEmpty()) {
	            logger.warn("No shifts found for Shift ID: " + location_id);
	            return Response.status(Response.Status.NOT_FOUND).entity(Map.of("error", "No shifts found for the given Shift ID.")).build();
	        }

	        //logger.info("Returning " + locationShifts.size() + " shifts for Location ID: " + location_id);
	        
	        return Response.ok(locationShifts).build();

	    } catch (Exception e) {
	        logger.error("Error fetching shifts for Location ID: " + location_id, e);
	        return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(Map.of("error", "An unexpected error occurred. Please try again later.")).build();
	    }
	    
	}





/**
 * @brief Retrieves a shift and all available locations, marking the currently assigned one.
 * @details Fetches the shift by ID, retrieves all locations, and sets the "used" flag
 *          on the location that matches the shift's assigned location.
 * @param shift_id the unique identifier of the shift to retrieve
 * @return a Response containing a ShiftWithLocations DTO, or HTTP 400/404/500 on error
 */
@APIResponse(
	    responseCode = "200",
description = "Details of the shift and a list of all locations.",
    content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = ShiftWithLocations.class))
)
@APIResponse(
    responseCode = "400",
description = "Invalid shift ID."
)
@APIResponse(
    responseCode = "404",
description = "Shift not found for the given ID."
)
@APIResponse(
    responseCode = "500",
description = "An unexpected error occurred."
)
@Operation(summary = "Retrieve a shift and its available locations.")
@GET
@Path("/editshift/{id}")
@Produces(MediaType.APPLICATION_JSON)
public Response getLocationDatesByIdShift(@PathParam("id") int shift_id,
        @QueryParam("structureId") @DefaultValue("0") int structureId) {

// Validate input
if (shift_id <= 0 || structureId <= 0) {
    return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("error", "Invalid shift ID. Must be a positive integer.")).build();
}

try {

    // ORM (Panache): shift + location description + skill catalogs with used flags (legacy
    // getShiftById parity: LEFT JOIN of entire catalog by type).
    ShiftEntity entity = ShiftEntity.findById(shift_id);
    if (entity == null || !shiftBelongsToStructure(shift_id, structureId)) {
        logger.warn("No shift found for shift_id: " + shift_id);
        return Response.status(Response.Status.NOT_FOUND).entity(Map.of("error", "No shift found for the given shift ID.")).build();
    }
    LocationEntity shiftLocation = LocationEntity.findById(entity.locationId);
    Shift shift = new Shift(shift_id,
            EmployeeDateEntity.parseDbDateTime(entity.startTime),
            EmployeeDateEntity.parseDbDateTime(entity.endTime),
            entity.locationId,
            shiftLocation != null ? shiftLocation.name : null,
            shiftSkillCatalog(shift_id, 1),
            shiftSkillCatalog(shift_id, 2),
            null);
    shift.setEmployeeId(entity.employeeId);
    shift.setPinned(entity.pinned);
    shift.setAuditOrderCode(entity.auditOrderCode);
    shift.setAuditStage(entity.auditStage);
    shift.setRequiredRole(entity.requiredRole);
    if (shiftLocation != null) {
        shift.setLocationLatitude(shiftLocation.latitude);
        shift.setLocationLongitude(shiftLocation.longitude);
    }

    // Structure locations (same shape as /getlocations), with the shift's assigned location
    // marked used.
    List<Location> allLocations = buildLocationDtos(structureId);
    allLocations.forEach(location -> {
        location.setUsed(location.getId() == shift.getLocation_id());
    });

    ShiftWithLocations shiftWithLocations = new ShiftWithLocations(shift, allLocations);
    return Response.ok(shiftWithLocations).build();

} catch (Exception e) {
    logger.error("Error fetching shift and locations for shift ID: " + shift_id, e);
    return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(Map.of("error", "An unexpected error occurred. Please try again later.")).build();
    }

}

/** @brief Complete skill catalog (natural order), with used=true on shift skills by type. */
private static List<Skill> shiftSkillCatalog(int shiftId, int skillTypeId) {
    java.util.Set<Integer> usedIds = ShiftSkillEntity.<ShiftSkillEntity>list(
            "shiftId = ?1 and skillTypeId = ?2", shiftId, skillTypeId)
            .stream().map(ss -> ss.skillId).collect(java.util.stream.Collectors.toSet());
    return SkillEntity.<SkillEntity>listAll().stream()
            .map(s -> new Skill(s.id, s.name, s.skillOrder != null ? s.skillOrder : 0,
                    usedIds.contains(s.id), s.active))
            .toList();
}

/** @brief Transactionally replaces ONE type of shift skills (id>0, null ignored). */
private static void replaceShiftSkills(int shiftId, List<Skill> skills, int skillTypeId) {
    ShiftSkillEntity.delete("shiftId = ?1 and skillTypeId = ?2", shiftId, skillTypeId);
    if (skills == null) return;
    // Structure determined from the shift through its location: another company's skills cannot
    // be assigned to this shift.
    java.util.Set<Integer> owned = ownedSkillIds(structureOfShift(shiftId));
    for (Skill skill : skills) {
        if (skill == null || skill.getId() <= 0 || !owned.contains(skill.getId())) continue;
        ShiftSkillEntity row = new ShiftSkillEntity();
        row.shiftId = shiftId;
        row.skillId = skill.getId();
        row.skillTypeId = skillTypeId;
        row.persist();
    }
}









/**
 * @brief Updates an existing shift by its ID.
 * @details Validates the shift ID and data, then delegates the update
 *          to the repository. Checks that start and end times are valid.
 * @param shift_id the unique identifier of the shift to update
 * @param shift the updated Shift data
 * @return a Response with HTTP 200 on success, or HTTP 400/500 on error
 */
@PUT
@Path("/updateshift/{id}")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
@Transactional
public Response updateShift(@PathParam("id") int shift_id, Shift shift,
        @QueryParam("structureId") @DefaultValue("0") int structureId) {
	
    //logger.info("Updating shift with ID: " + shift_id);

    if (shift_id <= 0 || structureId <= 0 || shift == null) {
        //logger.warn("Invalid shift_id: " + shift_id);
        return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("error", "Invalid shift ID. Must be a positive integer.")).build();
    }

    try {
    	
        if (!shiftBelongsToStructure(shift_id, structureId)
                || !locationBelongsToStructure(shift.getLocation_id(), structureId)) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        if (shift.getStart() == null || shift.getEnd() == null
                || !validSkillLists(shift.getRequiredSkill(), shift.getOptionalSkill())) {
            return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("error", "Invalid shift data. Location ID, start, and end times are required.")).build();
        }

        if (!shift.getEnd().isAfter(shift.getStart())) {
            return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("error", "End time must be after start time.")).build();
        }

        if (!shift.getStart().toLocalDate().equals(shift.getEnd().toLocalDate())) {
            return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("error", "Il turno non può essere a cavallo di due giorni.")).build();
        }

        // ORM (Panache): update fields + replace skills (both types) in ONE transaction.
        // Legacy parity: nonexistent ID -> still 200.
        ShiftEntity entity = ShiftEntity.findById(shift_id);
        if (entity != null) {
            entity.locationId = shift.getLocation_id();
            entity.startTime = shift.getStart().format(DB_FORMATTER);
            entity.endTime = shift.getEnd().format(DB_FORMATTER);
            entity.auditOrderCode = normalizedText(shift.getAuditOrderCode(), 80);
            entity.auditStage = normalizedText(shift.getAuditStage(), 80);
            entity.requiredRole = normalizedText(shift.getRequiredRole(), 32);
            replaceShiftSkills(shift_id, shift.getRequiredSkill(), 1);
            replaceShiftSkills(shift_id, shift.getOptionalSkill(), 2);
        }
        return Response.ok(Map.of("message", "Shift updated successfully.")).build();
    
    } catch (Exception e) {
    	
        logger.error("Error updating shift with ID: " + shift_id, e);
        
        return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(Map.of("error", "An unexpected error occurred. Please try again later.")).build();
    }
}



/**
 * @brief Adds a new shift with required and optional skills.
 * @details Extracts skill IDs from the Shift object's skill lists,
 *          handles null skills gracefully, and delegates insertion to the repository.
 * @param shift the Shift object containing location, times, and skills
 * @return a Response with HTTP 201 and the created Shift on success
 */
@POST
@Path("/addshift")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
@Transactional
public Response addShift(Shift shift,
        @QueryParam("structureId") @DefaultValue("0") int structureId) {

if (shift == null || !locationBelongsToStructure(shift.getLocation_id(), structureId)
        || shift.getStart() == null || shift.getEnd() == null
        || !shift.getEnd().isAfter(shift.getStart())
        || !validSkillLists(shift.getRequiredSkill(), shift.getOptionalSkill())) {
    return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("error", "Invalid shift data.")).build();
}
if (!shift.getStart().toLocalDate().equals(shift.getEnd().toLocalDate())) {
    return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("error", "Il turno non può essere a cavallo di due giorni.")).build();
}

// ORM (Panache): insert shift + skills (both types) in ONE transaction, with the same semantics
// as legacy addShift (generated ID returned in the DTO).
ShiftEntity entity = new ShiftEntity();
entity.locationId = shift.getLocation_id();
entity.startTime = shift.getStart().format(DB_FORMATTER);
entity.endTime = shift.getEnd().format(DB_FORMATTER);
entity.auditOrderCode = normalizedText(shift.getAuditOrderCode(), 80);
entity.auditStage = normalizedText(shift.getAuditStage(), 80);
entity.requiredRole = normalizedText(shift.getRequiredRole(), 32);
entity.persist();
shift.setId(entity.id);
replaceShiftSkills(entity.id, shift.getRequiredSkill(), 1);
replaceShiftSkills(entity.id, shift.getOptionalSkill(), 2);

return Response.status(Response.Status.CREATED).entity(shift) .build();
}

private static String normalizedText(String value, int maxLength) {
    String normalized = value == null ? "" : value.trim();
    return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength);
}

/**
 * @brief Employee date-constraint summary (only employees with at least one).
 * @details Populates the "Employee Date Preferences" page table.
 */
@GET
@Path("/employee-dates-summary")
public Response getEmployeeDatesSummary(@QueryParam("structureId") @DefaultValue("0") int structureId) {
    return Response.ok(demoDataRepository.getEmployeeDatesSummaryOrm(structureId)).build();
}

/**
 * @brief Persists shift-to-employee assignments from the solver solution accepted by the user.
 * @details Called by the "Save assignments" button in the Solve Result modal.
 */
@POST
@Path("/save-assignments")
@Consumes(MediaType.APPLICATION_JSON)
@Transactional
public Response saveAssignments(List<org.acme.employeescheduling.dto.ShiftAssignment> assignments,
                                @QueryParam("structureId") @DefaultValue("0") int structureId,
                                @QueryParam("start") String start,
                                @QueryParam("end") String end) {
    LocalDateTime ws = parseWindowBound(start);
    LocalDateTime we = parseWindowBound(end);
    if (structureId <= 0 || ws == null || we == null || !ws.isBefore(we))
        return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("error", "struttura o finestra non valida")).build();
    Response safetyFailure = safetyBackupFailure("preop");
    if (safetyFailure != null) return safetyFailure;
    // Optional start/end restrict UPDATE to the solve window: they exclude context shifts from
    // adjacent windows even if the payload contains them.
    int updated = demoDataRepository.saveShiftAssignmentsOrm(assignments, structureId,
            ws.format(DB_FORMATTER), we.format(DB_FORMATTER));
    // Shifts modified after solving: 409, not 400. The user did nothing wrong — they only need
    // to reload and rerun the solver against updated data.
    if (updated == DemoDataRepository.STALE_SHIFTS)
        return Response.status(Response.Status.CONFLICT)
                .entity(Map.of("error", "SHIFTS_CHANGED")).build();
    if (updated == DemoDataRepository.STALE_EMPLOYEES)
        return Response.status(Response.Status.CONFLICT)
                .entity(Map.of("error", "EMPLOYEE_STATUS_CHANGED")).build();
    // 同一个人被排进两个时间重叠的班次（典型场景：同一订单的两个名额点了同一个人）。
    // 409 而不是 400：请求格式没错，是内容冲突，需要调度员换人后重存。
    if (updated == DemoDataRepository.DUPLICATE_ASSIGNMENT)
        return Response.status(Response.Status.CONFLICT)
                .entity(Map.of("error", "EMPLOYEE_DOUBLE_BOOKED")).build();
    if (updated < 0) return Response.status(Response.Status.BAD_REQUEST)
            .entity(Map.of("error", "assegnazione non valida per la struttura")).build();
    // The solver path writes shift assignments directly, bypassing the plan workflow. Without
    // this entry, a save that rewrote shifts already placed by a published plan left no trace
    // anywhere in the audit trail. Recorded only after every guard passed: a rejected save
    // changes nothing and deserves no record.
    auditTrail.record(structureId, "SHIFT_ASSIGNMENTS_SAVED", "SHIFT_ASSIGNMENTS",
            ws.format(DB_FORMATTER) + "~" + we.format(DB_FORMATTER),
            Map.of("submitted", assignments.size(), "updated", updated));
    // 「保存结果 = 定稿」：把本次分配回填到覆盖这些班次的草案并封存快照。
    int finalized = finalizePlansForWindow(structureId, ws, we, assignments);
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("updated", updated);
    if (finalized > 0) body.put("finalizedPlans", finalized);
    return Response.ok(body).build();
}

/**
 * @brief 保存结果即定稿：把本次写入的分配回填到覆盖这些班次的 DRAFT 方案，并置为 EXECUTED。
 *
 * @details 这是「分配即生效」的落地点。方案快照必须跟着实际分配走，否则事后追溯看到的是
 * "当初打算派谁"而不是"最后派了谁"。
 *
 * <p>只有全部班次都有人的方案才定稿；仍有缺口的保持 DRAFT，让调度员继续排。
 * 找不到对应草案的班次不做任何事 —— 它们本来就不在方案体系里，硬造一个只会制造噪音。</p>
 *
 * @return 被定稿的方案数
 */
private int finalizePlansForWindow(int structureId, LocalDateTime ws, LocalDateTime we,
        List<org.acme.employeescheduling.dto.ShiftAssignment> assignments) {
    if (assignments == null || assignments.isEmpty()) return 0;
    Map<Integer, Integer> employeeByShift = new HashMap<>();
    for (org.acme.employeescheduling.dto.ShiftAssignment assignment : assignments) {
        if (assignment.getEmployeeId() != null) {
            employeeByShift.put(assignment.getShiftId(), assignment.getEmployeeId());
        }
    }
    if (employeeByShift.isEmpty()) return 0;

    List<SchedulePlanEntity> drafts = SchedulePlanEntity.<SchedulePlanEntity>find(
            "structureId = ?1 and status = 'DRAFT' and windowStart <= ?2 and windowEnd >= ?3",
            structureId, we.format(DB_FORMATTER), ws.format(DB_FORMATTER)).list();
    if (drafts.isEmpty()) return 0;

    String now = WorkflowSupport.now();
    int finalized = 0;
    for (SchedulePlanEntity plan : drafts) {
        List<SchedulePlanAssignmentEntity> rows = SchedulePlanAssignmentEntity
                .<SchedulePlanAssignmentEntity>list("planId", plan.id);
        if (rows.isEmpty()) continue;
        int touched = 0;
        for (SchedulePlanAssignmentEntity row : rows) {
            Integer employeeId = employeeByShift.get(row.shiftId);
            if (employeeId == null) continue;
            row.employeeId = employeeId;
            touched++;
        }
        if (touched == 0) continue;
        boolean complete = rows.stream().allMatch(row -> row.employeeId != null);
        if (!complete) continue;
        SchedulePlanEntity.supersedeOverlapping(structureId, plan, now);
        plan.status = "EXECUTED";
        plan.publishedBy = actor();
        plan.publishedAt = plan.updatedAt = now;
        auditTrail.record(structureId, "SCHEDULE_PLAN_FINALIZED", "SCHEDULE_PLAN", plan.id,
                Map.of("assignments", rows.size(), "source", "SOLVER_SAVE"));
        finalized++;
    }
    return finalized;
}

private String actor() {
    return identity == null || identity.isAnonymous() ? "system" : identity.getPrincipal().getName();
}

// ─── Email templates (subject + HTML body with placeholders) ───────────────

/** @brief Structure email template (empty if none has been saved). */
@GET
@Path("/email-template")
@Transactional
public Response getEmailTemplate(@QueryParam("structureId") @DefaultValue("0") int structureId) {
    if (structureId <= 0)
        return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("error", "BAD_REQUEST")).build();
    if (!structureExists(structureId)) return Response.status(Response.Status.NOT_FOUND).build();
    return Response.ok(demoDataRepository.getEmailTemplateOrm(structureId)).build();
}

/** @brief Saves (upserts) the structure email template. */
@PUT
@Path("/email-template")
@RolesAllowed("ADMIN")
@Consumes(MediaType.APPLICATION_JSON)
@Transactional
public Response saveEmailTemplate(@QueryParam("structureId") @DefaultValue("0") int structureId,
                                  org.acme.employeescheduling.dto.EmailTemplate tpl) {
    if (structureId <= 0 || tpl == null || tpl.getSubject() == null || tpl.getBody() == null
            || tpl.getSubject().length() > 255 || tpl.getBody().length() > 100_000)
        return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("error", "BAD_REQUEST")).build();
    if (!structureExists(structureId)) return Response.status(Response.Status.NOT_FOUND).build();
    String safeBody = RichHtmlSanitizer.sanitize(tpl.getBody());
    boolean ok = demoDataRepository.saveEmailTemplateOrm(structureId, tpl.getSubject(), safeBody);
    return ok ? Response.ok(demoDataRepository.getEmailTemplateOrm(structureId)).build()
              : Response.serverError().build();
}

// ─── PDF template (shared appearance, dynamic report content) ──────────────

@GET
@Path("/pdf-template")
@Transactional
public Response getPdfTemplate(@QueryParam("structureId") @DefaultValue("0") int structureId) {
    if (structureId <= 0)
        return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("error", "BAD_REQUEST")).build();
    if (!structureExists(structureId)) return Response.status(Response.Status.NOT_FOUND).build();
    return Response.ok(demoDataRepository.getPdfTemplateOrm(structureId)).build();
}

@PUT
@Path("/pdf-template")
@RolesAllowed("ADMIN")
@Consumes(MediaType.APPLICATION_JSON)
@Transactional
public Response savePdfTemplate(@QueryParam("structureId") @DefaultValue("0") int structureId,
                                PdfTemplate tpl) {
    if (structureId <= 0 || tpl == null || tpl.getHeaderText() == null || tpl.getFooterText() == null
            || tpl.getLogoDataUrl() == null || tpl.getPrimaryColor() == null
            || tpl.getHeaderText().length() > 500 || tpl.getFooterText().length() > 500
            || tpl.getLogoDataUrl().length() > 2_800_000
            || (!tpl.getLogoDataUrl().isBlank()
                && !tpl.getLogoDataUrl().matches("^data:image/(png|jpeg);base64,[A-Za-z0-9+/=\\r\\n]+$"))
            || !tpl.getPrimaryColor().matches("^#[0-9A-Fa-f]{6}$"))
        return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("error", "BAD_REQUEST")).build();
    if (!structureExists(structureId)) return Response.status(Response.Status.NOT_FOUND).build();
    boolean ok = demoDataRepository.savePdfTemplateOrm(structureId, tpl.getHeaderText(), tpl.getFooterText(),
        tpl.getLogoDataUrl(), tpl.getPrimaryColor().toUpperCase());
    return ok ? Response.ok(demoDataRepository.getPdfTemplateOrm(structureId)).build()
              : Response.serverError().build();
}

@DELETE
@Path("/pdf-template")
@RolesAllowed("ADMIN")
@Transactional
public Response deletePdfTemplate(@QueryParam("structureId") @DefaultValue("0") int structureId) {
    if (structureId <= 0)
        return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("error", "BAD_REQUEST")).build();
    if (!structureExists(structureId)) return Response.status(Response.Status.NOT_FOUND).build();
    return demoDataRepository.deletePdfTemplateOrm(structureId)
        ? Response.noContent().build()
        : Response.status(Response.Status.NOT_FOUND).build();
}







/**
 * @brief Retrieves all employees with their associated data.
 * @return a Response containing a list of Employee objects in JSON format
 */
@APIResponse(
    responseCode = "200",
description = "List of all employees.",
    content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = Employee.class))
)
@Operation(summary = "Retrieve a list of all employees.")
@GET
@Path("/employees")
public Response getEmployees(@QueryParam("structureId") @DefaultValue("0") int structureId) {
    // ORM (Panache): three bulk reads (employees, dates, skills) — no N+1, therefore no need for
    // the legacy cache (which remains for the solver payload).
    List<EmployeeEntity> entities = EmployeeEntity.list("structureId", Sort.by("id"), structureId);
    Map<Integer, Employee> byId = new LinkedHashMap<>();
    List<Employee> employees = new ArrayList<>();
    for (EmployeeEntity e : entities) {
        Employee dto = new Employee(e.id, e.code, e.firstName, e.lastName,
                new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        dto.setActive(e.active);
        dto.setEmail(e.email);
        copyOperationalFields(e, dto);
        byId.put(e.id, dto);
        employees.add(dto);
    }
    // Dates for every employee in the structure, grouped by type.
    List<EmployeeDateEntity> dates = em.createQuery(
            "select d from EmployeeDateEntity d, EmployeeEntity e " +
            "where e.id = d.employeeId and e.structureId = ?1 " +
            "order by d.employeeId, d.dateStart, d.dateTypeId, d.id", EmployeeDateEntity.class)
            .setParameter(1, structureId).getResultList();
    for (EmployeeDateEntity d : dates) {
        Employee employee = byId.get(d.employeeId);
        if (employee == null) continue;
        EmployeeDate date = d.toDto();
        switch (d.dateTypeId) {
            case EmployeeDateEntity.TYPE_DESIRED -> employee.getDesiredDates().add(date);
            case EmployeeDateEntity.TYPE_UNDESIRED -> employee.getUndesiredDates().add(date);
            case EmployeeDateEntity.TYPE_UNAVAILABLE -> employee.getUnavailableDates().add(date);
            default -> { }
        }
    }
    // Skills: STRUCTURE catalog for each employee, with used=true on associated skills (legacy
    // parity: copies do NOT carry the active flag — it stays at its default). With listAll(),
    // every employee would also carry skills from other companies, which would appear among the
    // checkboxes in their modal.
    List<SkillEntity> catalog = SkillEntity.list("structureId",
            Sort.by("skillOrder").and("id"), structureId);
    Map<Integer, java.util.Set<Integer>> skillIdsByEmployee = new HashMap<>();
    Map<Integer, List<AuditorQualification>> qualificationsByEmployee = new HashMap<>();
    List<EmployeeSkillEntity> links = em.createQuery(
            "select es from EmployeeSkillEntity es, EmployeeEntity e " +
            "where e.id = es.employeeId and e.structureId = ?1", EmployeeSkillEntity.class)
            .setParameter(1, structureId).getResultList();
    for (EmployeeSkillEntity es : links) {
        skillIdsByEmployee.computeIfAbsent(es.employeeId, k -> new java.util.HashSet<>()).add(es.skillId);
        qualificationsByEmployee.computeIfAbsent(es.employeeId, k -> new ArrayList<>()).add(toQualification(es));
    }
    for (Employee employee : employees) {
        java.util.Set<Integer> usedIds = skillIdsByEmployee.getOrDefault(employee.getId(), java.util.Set.of());
        List<Skill> skills = new ArrayList<>(catalog.size());
        for (SkillEntity s : catalog)
            skills.add(new Skill(s.id, s.name, s.skillOrder != null ? s.skillOrder : 0,
                    usedIds.contains(s.id), s.active));
        employee.setSkills(skills);
        employee.setQualifications(qualificationsByEmployee.getOrDefault(employee.getId(), List.of()));
    }
    return Response.ok(employees).build();
}



/**
 * @brief Retrieves a specific employee by ID.
 * @param employee_id the unique identifier of the employee
 * @return a Response containing the Employee in JSON, or HTTP 404 if not found
 */
@APIResponse(
	responseCode = "200",
description = "Employee retrieved successfully.",
    content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = Employee.class))
)
@Operation(summary = "Retrieve a specific employee by ID.")
@GET
@Path("/getemployee/{id}")
public Response getEmployee(@PathParam("id") int employee_id,
        @QueryParam("structureId") @DefaultValue("0") int structureId) {

    EmployeeEntity entity = EmployeeEntity.findById(employee_id);
    if (entity == null || structureId <= 0 || entity.structureId != structureId) {
        return Response.status(Response.Status.NOT_FOUND).build();
    }
    // Legacy parity: skills = ENTIRE CATALOG with used=true on associated skills (LEFT JOIN,
    // natural order); dates grouped by type and ordered by date and type.
    Employee employee = new Employee(entity.id, entity.code, entity.firstName, entity.lastName,
            employeeDates(employee_id, EmployeeDateEntity.TYPE_DESIRED),
            employeeDates(employee_id, EmployeeDateEntity.TYPE_UNDESIRED),
            employeeDates(employee_id, EmployeeDateEntity.TYPE_UNAVAILABLE),
            employeeSkillCatalog(employee_id));
    employee.setActive(entity.active);
    employee.setEmail(entity.email);
    copyOperationalFields(entity, employee);
    employee.setQualifications(EmployeeSkillEntity.<EmployeeSkillEntity>list("employeeId", employee_id)
            .stream().map(EmployeeScheduleDemoResource::toQualification).toList());
    return Response.ok(employee).build();
}

private static AuditorQualification toQualification(EmployeeSkillEntity entity) {
    AuditorQualification dto = new AuditorQualification();
    dto.setSkillId(entity.skillId);
    dto.setCertificateNumber(entity.certificateNumber);
    dto.setQualificationLevel(entity.qualificationLevel);
    dto.setValidFrom(entity.validFrom == null || entity.validFrom.isBlank() ? null : LocalDate.parse(entity.validFrom));
    dto.setValidUntil(entity.validUntil == null || entity.validUntil.isBlank() ? null : LocalDate.parse(entity.validUntil));
    dto.setStatus(entity.qualificationStatus);
    return dto;
}

private static void copyOperationalFields(EmployeeEntity entity, Employee dto) {
    dto.setOperationalStatus(entity.operationalStatus);
    dto.setBusyUntil(entity.busyUntil == null || entity.busyUntil.isBlank()
            ? null : EmployeeDateEntity.parseDbDateTime(entity.busyUntil));
    dto.setStatusUpdatedAt(entity.statusUpdatedAt == null || entity.statusUpdatedAt.isBlank()
            ? null : EmployeeDateEntity.parseDbDateTime(entity.statusUpdatedAt));
    dto.setCurrentLocationId(entity.currentLocationId);
    dto.setBufferBeforeMinutes(entity.bufferBeforeMinutes);
    dto.setBufferAfterMinutes(entity.bufferAfterMinutes);
    // 简历：本期只读回显（上传接口未开放），两列可空，前端据此显示"尚未上传"。
    dto.setResumeFileName(entity.resumeFileName);
    dto.setResumeUploadedAt(entity.resumeUploadedAt);
}

/** @brief Employee dates by type, ordered by date and type (as in legacy). */
private static List<EmployeeDate> employeeDates(int employeeId, int dateTypeId) {
    return EmployeeDateEntity.<EmployeeDateEntity>list(
            "employeeId = ?1 and dateTypeId = ?2 order by dateStart, dateTypeId", employeeId, dateTypeId)
            .stream().map(EmployeeDateEntity::toDto).toList();
}

/** @brief Complete skill catalog (natural order), with used=true on the employee's skills. */
private static List<Skill> employeeSkillCatalog(int employeeId) {
    java.util.Set<Integer> usedIds = EmployeeSkillEntity.<EmployeeSkillEntity>list("employeeId", employeeId)
            .stream().map(es -> es.skillId).collect(java.util.stream.Collectors.toSet());
    return SkillEntity.<SkillEntity>listAll().stream()
            .map(s -> new Skill(s.id, s.name, s.skillOrder != null ? s.skillOrder : 0,
                    usedIds.contains(s.id), s.active))
            .toList();
}





/**
 * @brief Adds a new employee to the system.
 * @param employee the Employee object to add
 * @return a Response with HTTP 201 on success
 */
@APIResponse(
	responseCode = "201",
    description = "Employee added successfully.",
    content = @Content(mediaType = MediaType.APPLICATION_JSON)
)
@Operation(summary = "Add a new employee.")
@POST
@Path("/addemployee")
@Consumes(MediaType.APPLICATION_JSON)
@Transactional
public Response addEmployee(Employee employee, @QueryParam("structureId") @DefaultValue("0") int structureId) {
    if (!structureExists(structureId)) return Response.status(Response.Status.NOT_FOUND).build();
    if (employee == null) return Response.status(Response.Status.BAD_REQUEST).build();
    Response invalid = validateEmployeeFields(employee);
    if (invalid != null) return invalid;
    if (!isValidEmail(employee.getEmail())) {
        return ApiErrors.badRequest("EMPLOYEE_EMAIL_INVALID");
    }
    if (!validSkillLists(employee.getSkills(), null)
            || !validQualifications(employee.getQualifications(), employee.getSkills())
            || !validOperationalState(employee)
            || (employee.getCurrentLocationId() != null
                && !locationBelongsToStructure(employee.getCurrentLocationId(), structureId))
            || !validDateIntervals(employee.getUnavailableDates())
            || !validDateIntervals(employee.getUndesiredDates())
            || !validDateIntervals(employee.getDesiredDates())) {
        return Response.status(Response.Status.BAD_REQUEST).build();
    }
    if (employee.getEmail() != null) employee.setEmail(employee.getEmail().trim());
    if (EmployeeEntity.count("code = ?1 and id != ?2", employee.getCode().trim(), 0) > 0) {
        return ApiErrors.conflict("EMPLOYEE_CODE_IN_USE");
    }
    // ORM (Panache): insert employee + skills + dates in ONE transaction. Legacy parity:
    // capitalized first/last names; creation-payload skills are resolved by NAME
    // (getSkillIdByName), discarding unknown ones; dates are written as date-only
    // (legacy setDate -> yyyy-MM-dd).
    EmployeeEntity entity = new EmployeeEntity();
    entity.code = employee.getCode();
    entity.firstName = capitalizeName(employee.getFirstName());
    entity.lastName = capitalizeName(employee.getLastName());
    entity.email = employee.getEmail() == null ? "" : employee.getEmail();
    entity.structureId = structureId;
    applyOperationalFields(employee, entity);
    entity.persist();

    if (employee.getSkills() != null) {
        // Only skills from the employee's structure: another company's skills could still arrive
        // from the client, but do not belong to the employee.
        java.util.Set<Integer> owned = ownedSkillIds(structureId);
        for (Skill skill : employee.getSkills()) {
            if (skill == null || skill.getId() <= 0 || !owned.contains(skill.getId())) continue;
            EmployeeSkillEntity link = new EmployeeSkillEntity();
            link.employeeId = entity.id;
            link.skillId = skill.getId();
            applyQualification(qualificationForSkill(employee.getQualifications(), skill.getId()), link);
            link.persist();
        }
    }
    insertDateIntervalRows(entity.id, employee.getUnavailableDates(), EmployeeDateEntity.TYPE_UNAVAILABLE);
    insertDateIntervalRows(entity.id, employee.getUndesiredDates(), EmployeeDateEntity.TYPE_UNDESIRED);
    insertDateIntervalRows(entity.id, employee.getDesiredDates(), EmployeeDateEntity.TYPE_DESIRED);

    demoDataRepository.invalidateEmployeesAfterCommit();
    return Response.status(Response.Status.CREATED).build();
}

/** @brief Uppercase first letter, lowercase remainder (legacy addEmployee parity). */
private static String capitalizeName(String s) {
    if (s == null || s.isEmpty()) return s;
    return s.substring(0, 1).toUpperCase() + s.substring(1).toLowerCase();
}

/** @brief Inserts date intervals as date-only yyyy-MM-dd (legacy setDate parity). */
private static void insertDateIntervalRows(int employeeId, List<EmployeeDate> intervals, int dateTypeId) {
    if (intervals == null || intervals.isEmpty()) return;
    for (EmployeeDate interval : intervals) {
        EmployeeDateEntity row = new EmployeeDateEntity();
        row.employeeId = employeeId;
        row.dateStart = interval.getDateStart().toLocalDate().toString();
        row.dateEnd = interval.getDateEnd().toLocalDate().toString();
        row.dateTypeId = dateTypeId;
        row.persist();
    }
}




/**
 * @brief Updates an existing employee by ID.
 * @param id the unique identifier of the employee to update
 * @param employee the updated Employee data
 * @return a Response with HTTP 200 on success, HTTP 400 for ID mismatch, or HTTP 404 if not found
 */
@APIResponse(
    responseCode = "200",
    description = "Employee updated successfully.",
    content = @Content(mediaType = MediaType.APPLICATION_JSON)
)
@Operation(summary = "Update an existing employee.")
@PUT
@Path("/updateemployee/{id}")
@Consumes(MediaType.APPLICATION_JSON)
@Transactional
public Response updateEmployee(@PathParam("id") int id, Employee employee,
        @QueryParam("structureId") @DefaultValue("0") int structureId) {

	if (employee == null || structureId <= 0 || employee.getId() != id) {
	    return Response.status(Response.Status.BAD_REQUEST).entity("ID mismatch").build();
	}

	Response invalid = validateEmployeeFields(employee);
	if (invalid != null) return invalid;

	if (!isValidEmail(employee.getEmail())) {
	    return ApiErrors.badRequest("EMPLOYEE_EMAIL_INVALID");
	}
	if (employee.getEmail() != null) employee.setEmail(employee.getEmail().trim());

	if (EmployeeEntity.count("code = ?1 and id != ?2", employee.getCode().trim(), id) > 0) {
	    return ApiErrors.conflict("EMPLOYEE_CODE_IN_USE");
	}

	// ORM (Panache): update fields + replace skills in ONE transaction. Legacy updateEmployee
	// parity: NO capitalization here (only in add); structure_id unchanged; update-payload skills
	// arrive by ID.
	EmployeeEntity entity = EmployeeEntity.findById(id);
	if (entity == null || entity.structureId != structureId) {
	    return Response.status(Response.Status.NOT_FOUND).build();
	}
	if (!validSkillLists(employee.getSkills(), null)) {
	    return Response.status(Response.Status.BAD_REQUEST).build();
	}
	if (!validQualifications(employee.getQualifications(), employee.getSkills())
	        || !validOperationalState(employee)
	        || (employee.getCurrentLocationId() != null
	            && !locationBelongsToStructure(employee.getCurrentLocationId(), structureId))) {
	    return Response.status(Response.Status.BAD_REQUEST).build();
	}
	entity.code = employee.getCode();
	entity.firstName = employee.getFirstName();
	entity.lastName = employee.getLastName();
	entity.email = employee.getEmail() == null ? "" : employee.getEmail();
	entity.active = employee.isActive();
	applyOperationalFields(employee, entity);

	EmployeeSkillEntity.delete("employeeId", id);
	if (employee.getSkills() != null) {
	    // Structure read from the employee itself: filter out other companies' skills.
	    java.util.Set<Integer> owned = ownedSkillIds(entity.structureId);
	    for (Skill skill : employee.getSkills()) {
	        if (skill == null || skill.getId() <= 0 || !owned.contains(skill.getId())) continue;
	        EmployeeSkillEntity link = new EmployeeSkillEntity();
	        link.employeeId = id;
	        link.skillId = skill.getId();
	        applyQualification(qualificationForSkill(employee.getQualifications(), skill.getId()), link);
	        link.persist();
	    }
	}

	// Note: preferred/undesired/unavailable dates are NOT touched here. They are managed by the
	// dedicated "Employee Date Preferences" page through its own endpoints; the employee update
	// payload (name/email/skills/active) does not carry them, so rewriting them here would delete
	// them on every save.

	// Disabled employee: release future shifts (and their pin), otherwise they remain assigned in
	// the DB but invisible in Shift Management. Past shifts remain intact: they are history.
	if (!employee.isActive()) {
	    long freed = unassignEmployeeShifts(id, LocalDateTime.now().format(DB_FORMATTER));
	    if (freed > 0)
	        logger.info("Operatore " + id + " disattivato: liberati " + freed + " turni futuri.");
	}

	demoDataRepository.invalidateEmployeesAfterCommit();
	markPlanAssignmentsForRevalidation(id, "EMPLOYEE_PROFILE_CHANGED");
	return Response.ok().build();

}

/** Lightweight live-status update for dispatch integrations; no profile fields are replaced. */
@PUT
@Path("/employees/{id}/status")
@Consumes(MediaType.APPLICATION_JSON)
@Transactional
public Response updateEmployeeStatus(@PathParam("id") int id, EmployeeStatusUpdate update,
        @QueryParam("structureId") @DefaultValue("0") int structureId) {
    if (update == null || !employeeBelongsToStructure(id, structureId)) {
        return Response.status(Response.Status.NOT_FOUND).build();
    }
    String status = update.getOperationalStatus() == null ? "AVAILABLE"
            : update.getOperationalStatus().toUpperCase();
    if (!Set.of("AVAILABLE", "ASSIGNED", "TRAVELLING", "UNAVAILABLE").contains(status)
            || (update.getCurrentLocationId() != null
                && !locationBelongsToStructure(update.getCurrentLocationId(), structureId))
            || (update.getBufferBeforeMinutes() != null
                && (update.getBufferBeforeMinutes() < 0 || update.getBufferBeforeMinutes() > 1440))
            || (update.getBufferAfterMinutes() != null
                && (update.getBufferAfterMinutes() < 0 || update.getBufferAfterMinutes() > 1440))) {
        return Response.status(Response.Status.BAD_REQUEST).build();
    }
    EmployeeEntity entity = EmployeeEntity.findById(id);
    entity.operationalStatus = status;
    entity.busyUntil = update.getBusyUntil() == null ? null : update.getBusyUntil().format(DB_FORMATTER);
    entity.currentLocationId = update.getCurrentLocationId();
    if (update.getBufferBeforeMinutes() != null) entity.bufferBeforeMinutes = update.getBufferBeforeMinutes();
    if (update.getBufferAfterMinutes() != null) entity.bufferAfterMinutes = update.getBufferAfterMinutes();
    entity.statusUpdatedAt = LocalDateTime.now().format(DB_FORMATTER);
    demoDataRepository.invalidateEmployeesAfterCommit();
    markPlanAssignmentsForRevalidation(id, "EMPLOYEE_STATUS_CHANGED");
    Map<String, Object> event = new LinkedHashMap<>();
    event.put("employeeId", id);
    event.put("structureId", structureId);
    event.put("operationalStatus", status);
    event.put("busyUntil", entity.busyUntil);
    event.put("currentLocationId", entity.currentLocationId);
    event.put("statusUpdatedAt", entity.statusUpdatedAt);
    auditTrail.record(structureId, "EMPLOYEE_STATUS_UPDATED", "EMPLOYEE", id, event);
    dispatchEvents.publish("employee.status.changed", event);
    return Response.ok(Map.of("statusUpdatedAt", entity.statusUpdatedAt)).build();
}

/** Makes affected non-final plans visibly stale as soon as personnel data changes. */
private static void markPlanAssignmentsForRevalidation(int employeeId, String riskCode) {
    for (SchedulePlanAssignmentEntity assignment : SchedulePlanAssignmentEntity.<SchedulePlanAssignmentEntity>list(
            "employeeId", employeeId)) {
        SchedulePlanEntity plan = SchedulePlanEntity.findById(assignment.planId);
        // 只有草案还在被编辑，人员变化才有必要标风险；已定稿/已被替代的方案是历史快照，不该再变。
        if (plan != null && "DRAFT".equals(plan.status)) {
            assignment.riskCode = riskCode;
            plan.updatedAt = WorkflowSupport.now();
        }
    }
}

private static AuditorQualification qualificationForSkill(List<AuditorQualification> qualifications, int skillId) {
    if (qualifications == null) return null;
    return qualifications.stream().filter(q -> q != null && q.getSkillId() == skillId).findFirst().orElse(null);
}

private static void applyQualification(AuditorQualification qualification, EmployeeSkillEntity entity) {
    if (qualification == null) {
        entity.certificateNumber = "UNVERIFIED-" + entity.employeeId + "-" + entity.skillId;
        entity.qualificationStatus = "ACTIVE";
        return;
    }
    entity.certificateNumber = qualification.getCertificateNumber().trim();
    entity.qualificationLevel = qualification.getQualificationLevel().trim();
    entity.validFrom = qualification.getValidFrom() == null ? null : qualification.getValidFrom().toString();
    entity.validUntil = qualification.getValidUntil() == null ? null : qualification.getValidUntil().toString();
    entity.qualificationStatus = qualification.getStatus().toUpperCase();
}

private static void applyOperationalFields(Employee source, EmployeeEntity target) {
    target.operationalStatus = source.getOperationalStatus() == null
            ? "AVAILABLE" : source.getOperationalStatus().toUpperCase();
    target.busyUntil = source.getBusyUntil() == null ? null : source.getBusyUntil().format(DB_FORMATTER);
    target.statusUpdatedAt = LocalDateTime.now().format(DB_FORMATTER);
    target.currentLocationId = source.getCurrentLocationId();
    target.bufferBeforeMinutes = source.getBufferBeforeMinutes();
    target.bufferAfterMinutes = source.getBufferAfterMinutes();
}






/**
 * @brief Deletes an employee by ID.
 * @details Removes the employee and all associated data (skills, dates).
 * @param id the unique identifier of the employee to delete
 * @return a Response with HTTP 204 on success, or HTTP 404 if not found
 */
@APIResponse(
	responseCode = "204",
    description = "Employee deleted successfully.",
    content = @Content(mediaType = MediaType.APPLICATION_JSON)
)
@Operation(summary = "Delete an existing employee by ID.")
@DELETE
@Path("/employees/{id}")
@Transactional
public Response deleteEmployee(@PathParam("id") int id,
        @QueryParam("structureId") @DefaultValue("0") int structureId) {
    if (!employeeBelongsToStructure(id, structureId)) {
        return Response.status(Response.Status.NOT_FOUND).build();
    }
    // 有未结束班次（进行中或未来）的人不能删：删除会把班次 employee_id 置空，
    // 那些名额会静默变成"无人"，而调度员通常不会立刻察觉。
    // 只拦在途班次——历史班次不该永久绑定一个已经离职的人。
    String nowText = LocalDateTime.now().format(DB_FORMATTER);
    if (ShiftEntity.count("employeeId = ?1 and endTime >= ?2", id, nowText) > 0) {
        return ApiErrors.conflict("EMPLOYEE_IN_USE");
    }
    // ORM (Panache): complete cascade in ONE transaction (legacy used separate connections with
    // dependent rows deleted outside the transaction). SQLite does not enforce FKs: skills and
    // dates must be removed explicitly; shifts are detached (an orphan employee_id is
    // not resolvable by any view).
    EmployeeSkillEntity.delete("employeeId", id);
    EmployeeDateEntity.delete("employeeId", id);
    EmailLogEntity.delete("employeeId", id);
    unassignEmployeeShifts(id, null);
    boolean isDeleted = EmployeeEntity.deleteById(id);
    if (isDeleted) {
        // 附件目录没有外键，员工没了就再没有入口能访问到，必须一并清掉。
        resumeStorage.purgeEmployeeAfterCommit(id);
        demoDataRepository.invalidateEmployeesAfterCommit();
        return Response.status(Response.Status.NO_CONTENT).build();
    } else {
        return Response.status(Response.Status.NOT_FOUND).build();
    }
}

// ---------------------------------------------------------------------------
// 简历附件：上传 / 下载 / 删除
// ---------------------------------------------------------------------------

/**
 * @brief 上传或替换审核员的简历（PDF，≤10 MB）。
 *
 * @details 用原始字节体而不是 multipart：只传一个文件，multipart 的边界解析纯属多余，
 *          前端 {@code fetch(url, { body: file })} 也更直接（与 Excel 导入的 byte[] 约定一致）。
 *          替换时先写新文件再删旧文件——顺序反过来会在写入失败时丢附件。
 */
@POST
@Path("/employees/{id}/resume")
@Consumes({ "application/pdf", MediaType.APPLICATION_OCTET_STREAM })
@Transactional
public Response uploadResume(@PathParam("id") int id,
        @QueryParam("structureId") @DefaultValue("0") int structureId,
        @QueryParam("fileName") String fileName, byte[] bytes) {
    if (!employeeBelongsToStructure(id, structureId)) {
        return Response.status(Response.Status.NOT_FOUND).build();
    }
    if (bytes == null || bytes.length == 0) {
        return ApiErrors.badRequest("RESUME_EMPTY");
    }
    if (bytes.length > resumeStorage.maxBytes()) {
        return errorWithStatus(Response.Status.REQUEST_ENTITY_TOO_LARGE, "RESUME_TOO_LARGE");
    }
    // 扩展名与 Content-Type 都由客户端决定，不可信；只有文件头能自证是 PDF。
    if (!resumeStorage.isPdf(bytes)) {
        return ApiErrors.badRequest("RESUME_TYPE_INVALID");
    }
    EmployeeEntity entity = EmployeeEntity.findById(id);
    if (entity == null) {
        return Response.status(Response.Status.NOT_FOUND).build();
    }

    String storedName;
    try {
        storedName = resumeStorage.store(id, bytes);
    } catch (java.io.IOException e) {
        return ApiErrors.serverError("RESUME_STORE_FAILED");
    }

    String previous = entity.resumeStoredName;
    try {
        entity.resumeFileName = ResumeStorage.sanitizeDisplayName(fileName);
        entity.resumeStoredName = storedName;
        entity.resumeUploadedAt = resumeStorage.nowStamp();
        if (previous != null && !previous.equals(storedName)) {
            resumeStorage.delete(id, previous);
        }

        auditTrail.record(structureId, "EMPLOYEE_RESUME_UPLOADED", "EMPLOYEE", id,
                Map.of("fileName", entity.resumeFileName, "bytes", bytes.length));
        dispatchEvents.publish("employee",
                Map.of("structureId", structureId, "employeeId", id, "action", "resume-uploaded"));
        return Response.ok(resumePayload(entity)).build();
    } catch (RuntimeException e) {
        // 落盘发生在事务内，但事务回滚不会回滚磁盘。这里主动收掉刚写的文件，
        // 否则每次失败都会留下一个没人引用的 PDF（实测过一次：旧版本报 500 后磁盘多出孤儿文件）。
        resumeStorage.delete(id, storedName);
        throw e;
    }
}

/**
 * @brief 下载简历附件。
 *
 * @details 中文文件名必须走 RFC 5987 的 {@code filename*}，否则浏览器会按 latin-1 解码成乱码；
 *          {@code filename} 保留一个 ASCII 兜底，供老客户端使用。
 */
@GET
@Path("/employees/{id}/resume")
@Produces(ResumeStorage.CONTENT_TYPE)
public Response downloadResume(@PathParam("id") int id,
        @QueryParam("structureId") @DefaultValue("0") int structureId) {
    if (!employeeBelongsToStructure(id, structureId)) {
        return Response.status(Response.Status.NOT_FOUND).build();
    }
    EmployeeEntity entity = EmployeeEntity.findById(id);
    if (entity == null) {
        return Response.status(Response.Status.NOT_FOUND).build();
    }
    // 注意：本类有 JAX-RS 的 @Path，不能 import java.nio.file.Path（同名冲突），这里用全限定名。
    java.nio.file.Path file = resumeStorage.resolve(id, entity.resumeStoredName);
    if (file == null) {
        // 记录有名字但文件不在了（如手工清理过目录）：给出可识别的错误码，让前端提示重新上传。
        return ApiErrors.notFound("RESUME_NOT_FOUND");
    }
    byte[] bytes;
    try {
        bytes = Files.readAllBytes(file);
    } catch (java.io.IOException e) {
        return ApiErrors.serverError("RESUME_READ_FAILED");
    }
    String display = ResumeStorage.sanitizeDisplayName(entity.resumeFileName);
    String ascii = display.replaceAll("[^\\x20-\\x7e]", "_").replace("\"", "_");
    String encoded = URLEncoder.encode(display, StandardCharsets.UTF_8).replace("+", "%20");
    return Response.ok(bytes)
            .header("Content-Disposition",
                    "attachment; filename=\"" + ascii + "\"; filename*=UTF-8''" + encoded)
            .header("Content-Length", bytes.length)
            .build();
}

/**
 * @brief 删除简历附件并清空三个字段。
 *
 * @details 附件不存在也返回成功（幂等）：目的是让记录回到"没有简历"，而不是让用户卡住。
 */
@DELETE
@Path("/employees/{id}/resume")
@Transactional
public Response deleteResume(@PathParam("id") int id,
        @QueryParam("structureId") @DefaultValue("0") int structureId) {
    if (!employeeBelongsToStructure(id, structureId)) {
        return Response.status(Response.Status.NOT_FOUND).build();
    }
    EmployeeEntity entity = EmployeeEntity.findById(id);
    if (entity == null) {
        return Response.status(Response.Status.NOT_FOUND).build();
    }
    boolean removed = resumeStorage.delete(id, entity.resumeStoredName);
    entity.resumeFileName = null;
    entity.resumeStoredName = null;
    entity.resumeUploadedAt = null;
    auditTrail.record(structureId, "EMPLOYEE_RESUME_DELETED", "EMPLOYEE", id,
            Map.of("fileRemoved", removed));
    dispatchEvents.publish("employee",
            Map.of("structureId", structureId, "employeeId", id, "action", "resume-deleted"));
    return Response.ok(Map.of("cleared", true, "fileRemoved", removed)).build();
}

/** @brief 简历三字段的响应体，前端用它就地更新弹窗而不必重新拉整条员工。 */
private Map<String, Object> resumePayload(EmployeeEntity entity) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("resumeFileName", entity.resumeFileName);
    body.put("resumeUploadedAt", entity.resumeUploadedAt);
    body.put("sizeLimitedTo", resumeStorage.maxBytes());
    return body;
}

/** @brief 带状态码的错误体，格式与 {@link ApiErrors} 一致（{@code {"error": CODE}}）。 */
private static Response errorWithStatus(Response.Status status, String code) {
    return Response.status(status)
            .type(MediaType.APPLICATION_JSON_TYPE)
            .entity(Map.of("error", code))
            .build();
}












/**
 * @brief Retrieves all dates for a specific employee.
 * @param employee_id the unique identifier of the employee
 * @return a Response containing a list of EmployeeDate objects, or HTTP 404 if none found
 */
@APIResponse(
	responseCode = "200",
    description = "List of all dates for the given employee.",
    content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = EmployeeDate.class))
)
@Operation(summary = "Retrieve all dates for a specific employee.")
@GET
@Path("/getemployeedates/{id}/")
@Produces(MediaType.APPLICATION_JSON)
public Response getEmployeeDates(@PathParam("id") int employee_id,
        @QueryParam("structureId") @DefaultValue("0") int structureId) {
    if (!employeeBelongsToStructure(employee_id, structureId)) {
        return Response.status(Response.Status.NOT_FOUND).build();
    }
    // ORM (Panache): every employee date (legacy type=0 parity), ordered by date and type.
    List<EmployeeDate> employeeDates = EmployeeDateEntity.<EmployeeDateEntity>list(
            "employeeId = ?1 order by dateStart, dateTypeId", employee_id)
            .stream().map(EmployeeDateEntity::toDto).toList();
    return Response.ok(employeeDates).build();
}





/**
 * @brief Retrieves a specific employee date entry by its date ID.
 * @param date_id the unique identifier of the employee date record
 * @return a Response containing the EmployeeDate data, or HTTP 404 if not found
 */
@APIResponse(
	responseCode = "200",
    description = "Specific date of Employ.",
    content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = EmployeeDate.class))
)
@Operation(summary = "Retrieve all dates for a specific employee.")
@GET
@Path("/editemployeedate/{id}")
@Produces(MediaType.APPLICATION_JSON)
public Response getEmployeeDateByDateId(@PathParam("id") int date_id,
        @QueryParam("structureId") @DefaultValue("0") int structureId) {
    // ORM (Panache): legacy parity, which returned the single row as a LIST.
    EmployeeDateEntity entity = EmployeeDateEntity.findById(date_id);
    if (entity == null || !employeeDateBelongsToStructure(date_id, structureId)) {
        return Response.status(Response.Status.NOT_FOUND).entity("No dates found for the given date_id ID.").build();
    }
    return Response.ok(List.of(entity.toDto())).build();
}










/**
 * @brief Retrieves required and optional skills available for shift assignment.
 * @return a Response containing a map with "requiredSkills" and "optionalSkills" lists
 */
@GET
@Path("/get_skills_for_shift")
@Produces(MediaType.APPLICATION_JSON)
public Response getSkillsForShift(@QueryParam("structureId") @DefaultValue("0") int structureId) {
    // ORM (Panache): same duplicated catalog as /skills-for-location.
    Map<String, List<Skill>> skillsForShift = new HashMap<>();
    List<Skill> catalog = listSkillsForStructure(structureId);
    skillsForShift.put("requiredSkills", catalog);
    skillsForShift.put("optionalSkills", catalog);
    return Response.ok(skillsForShift).build();
}


/**
 * @brief Updates the skills assigned to a shift for a given skill type.
 * @param shift_id the shift whose skills should be updated
 * @param skill_type_id 1 = required, 2 = optional
 * @param skills the list of skills to assign (replaces existing ones of the same type)
 * @return HTTP 200 on success
 */
@PUT
@Path("/update-shift-skills/{shift_id}/{skill_type_id}")
@Consumes(MediaType.APPLICATION_JSON)
@Transactional
public Response updateShiftSkillsFromModal(
        @PathParam("shift_id") int shift_id,
        @PathParam("skill_type_id") int skill_type_id,
        List<Skill> skills,
        @QueryParam("structureId") @DefaultValue("0") int structureId) {
    if (!shiftBelongsToStructure(shift_id, structureId))
        return Response.status(Response.Status.NOT_FOUND).build();
    if ((skill_type_id != LocationSkillEntity.TYPE_REQUIRED && skill_type_id != LocationSkillEntity.TYPE_OPTIONAL)
            || validatedSkillIds(skills) == null)
        return Response.status(Response.Status.BAD_REQUEST).build();
    // ORM (Panache): replace skills of the specified type (legacy parity, always 200).
    replaceShiftSkills(shift_id, skills, skill_type_id);
    return Response.ok().build();
}


/** Updates managed entities so Hibernate applies the boolean-to-INTEGER mapping. */
private static long unassignEmployeeShifts(int employeeId, String minimumStart) {
    List<ShiftEntity> shifts = minimumStart == null
            ? ShiftEntity.list("employeeId", employeeId)
            : ShiftEntity.list("employeeId = ?1 and startTime >= ?2", employeeId, minimumStart);
    for (ShiftEntity shift : shifts) {
        shift.employeeId = null;
        shift.pinned = false;
    }
    return shifts.size();
}




/**
 * @brief Deletes a shift by its ID.
 * @param shift_id the unique identifier of the shift to delete
 * @return a Response with HTTP 204 on success, HTTP 400 for invalid ID, HTTP 404 if not found, or HTTP 500 on error
 */
@DELETE
@Path("/delete_shift/{shift_id}")
@Transactional
public Response deleteShiftById(@PathParam("shift_id") int shift_id,
        @QueryParam("structureId") @DefaultValue("0") int structureId) {

    // Validate input
    if (shift_id <= 0) {
        return Response.status(Response.Status.BAD_REQUEST).entity("Invalid date ID provided.").build();
    }
    if (!shiftBelongsToStructure(shift_id, structureId)) {
        return Response.status(Response.Status.NOT_FOUND).entity("ID not found.").build();
    }

    // ORM (Panache). Beyond legacy: cascade to shift_skills in the same transaction (legacy left
    // orphan rows — the DB FK points to shifts_backup and does not trigger; the orphans were
    // unreachable anyway).
    ShiftSkillEntity.delete("shiftId", shift_id);
    boolean isDeleted = ShiftEntity.deleteById(shift_id);
    if (isDeleted) {
        return Response.status(Response.Status.NO_CONTENT).build();
    } else {
        return Response.status(Response.Status.NOT_FOUND).entity("ID not found.").build();
    }
}







/**
 * @brief Deletes a skill by its ID.
 *
 * @details ADMIN only, for the same reason as {@code save_skills}: deleting a skill cascades
 *          over its associations with locations, employees, shifts and templates, and is not
 *          a head nurse's operation.
 *
 * @param id the unique identifier of the skill to delete
 * @return a Response with HTTP 204 on success, or HTTP 404 if not found
 */
@DELETE
@RolesAllowed("ADMIN")
@Path("/skills/{id}")
@Transactional
    public Response deleteSkill(@PathParam("id") int id,
            @QueryParam("structureId") @DefaultValue("0") int structureId) {
        if (structureId <= 0 || !structureExists(structureId)) {
            return Response.status(Response.Status.BAD_REQUEST).build();
        }
    // A skill belongs to one structure: a structure that does not own it cannot touch it.
    // Without this check, another company's skill could be deleted by passing its identifier.
    if (!skillBelongsToStructure(id, structureId)) {
        return Response.status(Response.Status.NOT_FOUND).build();
    }
    // Manual cascade over associations: SQLite FKs are not active here, and deleting a skill
    // would leave orphan rows. Everything runs in ONE transaction.
    EmployeeSkillEntity.delete("skillId", id);
    ShiftSkillEntity.delete("skillId", id);
    ShiftTemplateSkillEntity.delete("skillId", id);
    LocationSkillEntity.delete("skillId", id);
    LocalizzazioneEntity.delete("entityType = ?1 and entityId = ?2", "skills", id);
    boolean isDeleted = SkillEntity.deleteById(id);
    // Same reason as saveSkills: the skill catalog lives inside the employee cache.
    demoDataRepository.invalidateEmployeesAfterCommit();
    demoDataRepository.invalidateTranslationsAfterCommit();
    if (isDeleted) {
        return Response.status(Response.Status.NO_CONTENT).build();
    }
    return Response.status(Response.Status.NOT_FOUND).build();

    
}
	
	








/**
 * @brief Deletes an employee date entry by its ID.
 * @param date_id the unique identifier of the date to delete
 * @return a Response with HTTP 200 on success, HTTP 400 for invalid ID, HTTP 404 if not found, or HTTP 500 on error
 */
@DELETE
@Path("/delete_date/{date_id}")
@Transactional
public Response deleteDateById(@PathParam("date_id") int date_id,
        @QueryParam("structureId") @DefaultValue("0") int structureId) {

    // Validate input
    if (date_id <= 0) {
        return Response.status(Response.Status.BAD_REQUEST).entity("Invalid date ID provided.").build();
    }
    if (!employeeDateBelongsToStructure(date_id, structureId)) {
        return Response.status(Response.Status.NOT_FOUND).entity("Date ID not found.").build();
    }

    // ORM (Panache): delete + employee-cache invalidation (dates live inside cached Employee
    // objects in the solver payload — same rule as legacy).
    boolean isDeleted = EmployeeDateEntity.deleteById(date_id);
    if (isDeleted) {
        demoDataRepository.invalidateEmployeesAfterCommit();
        return Response.ok(Map.of("message", "OK")).build();
    } else {
        return Response.status(Response.Status.NOT_FOUND).entity("Date ID not found.").build();
    }
}





/**
 * @brief Updates an existing employee date entry.
 * @param date_id the unique identifier of the date to update
 * @param employeeDate the updated EmployeeDate data
 * @return a Response with HTTP 200 on success, HTTP 400 for ID mismatch, or HTTP 404 if not found
 */
@APIResponse(
	    responseCode = "200",
	    description = "Employee date updated successfully.",
	    content = @Content(mediaType = MediaType.APPLICATION_JSON)
)
@Operation(summary = "Update an existing employee date.")
@PUT
@Path("/update_employee_dates/{id}")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
@Transactional
public Response updateEmployeeDates(@PathParam("id") int date_id, EmployeeDate employeeDate,
        @QueryParam("structureId") @DefaultValue("0") int structureId) {

    // Verify that the body ID matches the path ID
    if (employeeDate == null || employeeDate.getId() != date_id) {
        return Response.status(Response.Status.BAD_REQUEST).entity("ID mismatch between path and body").build();
    }

    // ORM (Panache). Legacy parity: null dates -> false -> 404 (same visible response); write in
    // full datetime format (dbFormatter).
    EmployeeDateEntity entity = EmployeeDateEntity.findById(date_id);
    if (entity == null || !employeeDateBelongsToStructure(date_id, structureId)) {
        return Response.status(Response.Status.NOT_FOUND).entity(Map.of("message", "No date found with the given ID")).build();
    }
    if (employeeDate.getEmployeeId() != entity.employeeId || !validDateInterval(employeeDate)) {
        return Response.status(Response.Status.BAD_REQUEST).build();
    }
    entity.dateStart = employeeDate.getDateStart().format(DB_FORMATTER);
    entity.dateEnd = employeeDate.getDateEnd().format(DB_FORMATTER);
    entity.dateTypeId = employeeDate.getDateTypeId();
    demoDataRepository.invalidateEmployeesAfterCommit();
    return Response.ok().entity(Map.of("message", "Employee date updated successfully")).build();
}








/**
 * @brief Adds a new date entry for a specific employee.
 * @details Validates the date data including required fields, date ordering,
 *          and date type range before persisting.
 * @param employee_id the unique identifier of the employee
 * @param employeeDate the EmployeeDate object containing date details
 * @return a Response with HTTP 201 and the new date ID on success, or HTTP 400/500 on error
 */
@APIResponse(
    responseCode = "201",
    description = "Employee date created successfully.",
    content = @Content(mediaType = MediaType.APPLICATION_JSON)
)
@Operation(summary = "Add a new employee date.")
@POST
@Path("/add_employee_dates/{employee_id}")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
@Transactional
public Response addEmployeeDate(@PathParam("employee_id") int employee_id, EmployeeDate employeeDate,
        @QueryParam("structureId") @DefaultValue("0") int structureId) {

    // Validate required fields
    if (employeeDate == null || employeeDate.getEmployeeId() != employee_id || !validDateInterval(employeeDate)) {
        return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("message", "Tutti i campi sono obbligatori.")).build();
    }
    if (!employeeBelongsToStructure(employee_id, structureId))
        return Response.status(Response.Status.NOT_FOUND).build();

    // ORM (Panache): insert with employee_id from the PATH (as in legacy; the body carries
    // employeeId only for validation above), full datetime.
    EmployeeDateEntity entity = new EmployeeDateEntity();
    entity.employeeId = employee_id;
    entity.dateStart = employeeDate.getDateStart().format(DB_FORMATTER);
    entity.dateEnd = employeeDate.getDateEnd().format(DB_FORMATTER);
    entity.dateTypeId = employeeDate.getDateTypeId();
    entity.persist();
    demoDataRepository.invalidateEmployeesAfterCommit();
    return Response.status(Response.Status.CREATED).entity(Map.of("id", entity.id)).build();

}

/**
 * @brief Atomically replaces all dates for an employee in a single transaction.
 *
 * @details Deletes existing dates and inserts those provided. If the payload is empty or null,
 *          deletes every date. Everything happens in a single JPA transaction: any validation
 *          error stops the entire operation without partial changes.
 */
@RolesAllowed({"ADMIN", "CAPOSALA", "DISPATCHER"})
@POST
@Path("/batch_save_employee_dates/{employee_id}")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
@Transactional
public Response batchSaveEmployeeDates(@PathParam("employee_id") int employee_id,
        List<EmployeeDate> dates,
        @QueryParam("structureId") @DefaultValue("0") int structureId) {
    if (!employeeBelongsToStructure(employee_id, structureId))
        return Response.status(Response.Status.NOT_FOUND).build();
    if (dates != null) {
        for (EmployeeDate d : dates) {
            if (!validDateInterval(d) || d.getEmployeeId() != employee_id)
                return ApiErrors.badRequest("EMPLOYEE_DATE_INVALID");
        }
    }
    EmployeeDateEntity.delete("employeeId", employee_id);
    if (dates != null) {
        for (EmployeeDate d : dates) {
            EmployeeDateEntity entity = new EmployeeDateEntity();
            entity.employeeId = employee_id;
            entity.dateStart = d.getDateStart().format(DB_FORMATTER);
            entity.dateEnd = d.getDateEnd().format(DB_FORMATTER);
            entity.dateTypeId = d.getDateTypeId();
            entity.persist();
        }
    }
    demoDataRepository.invalidateEmployeesAfterCommit();
    return Response.ok(Map.of("saved", dates != null ? dates.size() : 0)).build();
}










/**
 * @brief Updates the dates of an existing shift.
 * @param shift_id the unique identifier of the shift to update
 * @param shift the Shift object containing updated start and end times
 * @return a Response with HTTP 200 on success, HTTP 400 for ID mismatch, or HTTP 404 if not found
 */
@APIResponse(
	    responseCode = "200",
	    description = "Shift date updated successfully.",
	    content = @Content(mediaType = MediaType.APPLICATION_JSON)
)
@Operation(summary = "Update an existing Shift date.")
@PUT
@Path("/update_shift_dates/{shift_id}")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
@Transactional
public Response updateShiftDates(@PathParam("shift_id") int shift_id, Shift shift,
        @QueryParam("structureId") @DefaultValue("0") int structureId) {

    // Verify that the body ID matches the path ID
    if (shift == null || shift.getId() != shift_id) {
        return Response.status(Response.Status.BAD_REQUEST).entity("ID mismatch between path and body").build();
    }

    // ORM (Panache). Legacy updateShiftDate parity: null dates -> 404 (false), update start/end
    // only + replace skills (both types) in the same transaction.
    ShiftEntity entity = ShiftEntity.findById(shift_id);
    if (entity == null || !shiftBelongsToStructure(shift_id, structureId)) {
        return Response.status(Response.Status.NOT_FOUND).entity(Map.of("message", "No date found with the given ID")).build();
    }
    if (shift.getStart() == null || shift.getEnd() == null || !shift.getEnd().isAfter(shift.getStart())
            || !shift.getStart().toLocalDate().equals(shift.getEnd().toLocalDate())
            || !validSkillLists(shift.getRequiredSkill(), shift.getOptionalSkill()))
        return Response.status(Response.Status.BAD_REQUEST).build();
    entity.startTime = shift.getStart().format(DB_FORMATTER);
    entity.endTime = shift.getEnd().format(DB_FORMATTER);
    replaceShiftSkills(shift_id, shift.getRequiredSkill(), 1);
    replaceShiftSkills(shift_id, shift.getOptionalSkill(), 2);
    return Response.ok().entity(Map.of("message", "Shift date updated successfully")).build();

}





@APIResponse(
	    responseCode = "201",
	    description = "Shift date added successfully.",
	    content = @Content(mediaType = MediaType.APPLICATION_JSON)
	)
	/**
	 * @brief Adds a new shift date for a specific location.
	 * @details Validates start and end times before delegating insertion to the repository.
	 * @param location_id the unique identifier of the location
	 * @param shift the Shift object containing start and end times
	 * @return a Response with HTTP 201 and the new shift ID on success, or HTTP 400/500 on error
	 */
	@Operation(summary = "Add a new Shift date.")
	@POST
	@Path("/add_shift_dates/{location_id}")
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	@Transactional
	public Response addShiftDates(@PathParam("location_id") int location_id, Shift shift,
	        @QueryParam("structureId") @DefaultValue("0") int structureId) {

	    // Validate received data
	    if (shift == null || !locationBelongsToStructure(location_id, structureId)
	            || shift.getStart() == null || shift.getEnd() == null) {
	        return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("message", "Start date and End date are required")).build();
	    }

	    if (!shift.getEnd().isAfter(shift.getStart())) {
	        return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("message", "End time must be after start time.")).build();
	    }

	    if (!shift.getStart().toLocalDate().equals(shift.getEnd().toLocalDate())) {
	        return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("message", "Il turno non può essere a cavallo di due giorni.")).build();
	    }

	    // ORM (Panache): insert shift only (without skills), location from the PATH.
	    ShiftEntity entity = new ShiftEntity();
	    entity.locationId = location_id;
	    entity.startTime = shift.getStart().format(DB_FORMATTER);
	    entity.endTime = shift.getEnd().format(DB_FORMATTER);
	    entity.persist();
	    return Response.status(Response.Status.CREATED).entity(Map.of("id", entity.id)).build();
	}









	/**
	 * @brief Saves solver assignments for the current week and marks shifts as pinned.
	 * @param shifts the list of solved Shift objects whose employee assignments should be persisted
	 * @return a Response with HTTP 200 on success
	 */
	@POST
	@Path("/save_week_assignments")
	@Consumes(MediaType.APPLICATION_JSON)
	public Response saveWeekAssignments(List<Shift> shifts,
	                                    @QueryParam("structureId") @DefaultValue("0") int structureId) {
	    return demoDataRepository.saveWeekAssignmentsOrm(shifts, structureId)
	            ? Response.ok().build() : Response.status(Response.Status.BAD_REQUEST).build();
	}


	/**
	 * @brief Clears pinning for all shifts in the specified date range.
	 * @param start inclusive start of the week (ISO datetime string)
	 * @param end exclusive end of the week (ISO datetime string)
	 * @return a Response with HTTP 200 on success
	 */
	@PUT
	@Path("/unpin_week")
	public Response unpinWeek(@QueryParam("start") String start,
	                           @QueryParam("end") String end,
	                           @QueryParam("structureId") @DefaultValue("0") int structureId) {
	    LocalDateTime ws = parseWindowBound(start);
	    LocalDateTime we = parseWindowBound(end);
	    if (structureId <= 0 || ws == null || we == null || !ws.isBefore(we))
	        return Response.status(Response.Status.BAD_REQUEST).build();
	    demoDataRepository.unpinWeekOrm(ws.format(DB_FORMATTER), we.format(DB_FORMATTER), structureId);
	    return Response.ok().build();
	}

	/**
	 * @brief Skill-name translation written atomically.
	 * @details See {@link DemoDataRepository#upsertDataTranslation}: the old "find then insert"
	 *          made the entire skill save fail when two head nurses saved the same catalog at the
	 *          same time.
	 */
	private void upsertSkillTranslation(int skillId, int languageId, String value) {
	    demoDataRepository.upsertDataTranslation("skills", skillId, "name", languageId, value);
	}

	// ─── 通用校验辅助 ──────────────────────────────────────────────────────────
	// 这组是"记录是否存在且属于当前部门"和"入参是否合法"的小工具，多个端点共用。
	// 2026-10-29 删除模板功能时整段裁切把它们一起带走了，这里按编译产物逐字节还原，
	// 行为与删除前一致（SQL、边界、返回码都没变）。

	/** @brief 部门是否存在（避免对不存在的 structureId 建数据）。 */
	private static boolean structureExists(int structureId) {
	    return structureId > 0 && StructureEntity.count("id", structureId) > 0;
	}

	/** @brief 地点存在且属于该部门。 */
	private static boolean locationBelongsToStructure(int locationId, int structureId) {
	    return locationId > 0 && structureId > 0
	            && LocationEntity.count("id = ?1 and structureId = ?2", locationId, structureId) > 0;
	}

	/** @brief 员工存在且属于该部门。 */
	private static boolean employeeBelongsToStructure(int employeeId, int structureId) {
	    return employeeId > 0 && structureId > 0
	            && EmployeeEntity.count("id = ?1 and structureId = ?2", employeeId, structureId) > 0;
	}

	/** @brief 班次存在，且它所在的地点属于该部门（班次本身不存 structureId）。 */
	private static boolean shiftBelongsToStructure(int shiftId, int structureId) {
	    if (shiftId <= 0 || structureId <= 0) return false;
	    ShiftEntity shift = ShiftEntity.findById(shiftId);
	    return shift != null && locationBelongsToStructure(shift.locationId, structureId);
	}

	/** @brief 员工日期记录存在，且其所属员工属于该部门。 */
	private static boolean employeeDateBelongsToStructure(int employeeDateId, int structureId) {
	    if (employeeDateId <= 0 || structureId <= 0) return false;
	    EmployeeDateEntity entity = EmployeeDateEntity.findById(employeeDateId);
	    return entity != null && employeeBelongsToStructure(entity.employeeId, structureId);
	}

	/**
	 * @brief 技能列表 → 去重后的 id 集合；出现非法项（空、id<=0、重复、库里不存在）返回 {@code null}。
	 * @details 按 900 一批查库，避开 SQLite 的绑定变量个数上限。
	 */
	private static Set<Integer> validatedSkillIds(List<Skill> skills) {
	    if (skills == null || skills.isEmpty()) return Set.of();
	    Set<Integer> ids = new HashSet<>();
	    for (Skill skill : skills) {
	        if (skill == null || skill.getId() <= 0) return null;
	        if (!ids.add(skill.getId())) return null;
	    }
	    List<Integer> all = new ArrayList<>(ids);
	    long found = 0;
	    for (int i = 0; i < all.size(); i += 900) {
	        found += SkillEntity.count("id in ?1", all.subList(i, Math.min(i + 900, all.size())));
	    }
	    return found == ids.size() ? ids : null;
	}

	/** @brief 必需/可选技能列表各自合法，且两者没有交集。 */
	private static boolean validSkillLists(List<Skill> required, List<Skill> optional) {
	    Set<Integer> requiredIds = validatedSkillIds(required);
	    Set<Integer> optionalIds = validatedSkillIds(optional);
	    if (requiredIds == null || optionalIds == null) return false;
	    return requiredIds.stream().noneMatch(optionalIds::contains);
	}

	/** @brief 经纬度要么都不填，要么都在合法范围内。 */
	private static boolean validCoordinates(Location location) {
	    if (location.getLatitude() == null && location.getLongitude() == null) return true;
	    return location.getLatitude() != null && location.getLongitude() != null
	            && location.getLatitude() >= -90 && location.getLatitude() <= 90
	            && location.getLongitude() >= -180 && location.getLongitude() <= 180;
	}

	/** @brief 日期区间合法：起止齐全、结束晚于开始、类型在 1~3。 */
	private static boolean validDateInterval(EmployeeDate date) {
	    return date != null && date.getDateStart() != null && date.getDateEnd() != null
	            && date.getDateEnd().isAfter(date.getDateStart())
	            && date.getDateTypeId() >= 1 && date.getDateTypeId() <= 3;
	}

	/** @brief 一组日期区间全部合法（null 视为合法）。 */
	private static boolean validDateIntervals(List<EmployeeDate> dates) {
	    return dates == null || dates.stream().allMatch(EmployeeScheduleDemoResource::validDateInterval);
	}

	/** @brief 运行状态合法：缓冲 0~1440 分钟，状态在四个枚举值内（null 视为 AVAILABLE）。 */
	private static boolean validOperationalState(Employee employee) {
	    if (employee.getBufferBeforeMinutes() < 0 || employee.getBufferBeforeMinutes() > 1440
	            || employee.getBufferAfterMinutes() < 0 || employee.getBufferAfterMinutes() > 1440) return false;
	    String status = employee.getOperationalStatus() == null
	            ? "AVAILABLE" : employee.getOperationalStatus().toUpperCase();
	    return Set.of("AVAILABLE", "ASSIGNED", "TRAVELLING", "UNAVAILABLE").contains(status);
	}

	/**
	 * @brief 资格列表合法：技能必须出现在本次提交的技能表里、同技能不能重复、
	 *        证书号/等级不能空、状态是四值之一、有效期起止不能倒挂。
	 */
	private static boolean validQualifications(List<AuditorQualification> qualifications, List<Skill> skills) {
	    if (qualifications == null || qualifications.isEmpty()) return true;
	    Set<Integer> allowed = new HashSet<>();
	    if (skills != null) {
	        for (Skill skill : skills) {
	            if (skill != null) allowed.add(skill.getId());
	        }
	    }
	    Set<Integer> seen = new HashSet<>();
	    Set<String> statuses = Set.of("ACTIVE", "EXPIRING", "EXPIRED", "SUSPENDED");
	    for (AuditorQualification qualification : qualifications) {
	        if (qualification == null || qualification.getSkillId() <= 0) return false;
	        if (!allowed.contains(qualification.getSkillId())) return false;
	        if (!seen.add(qualification.getSkillId())) return false;
	        if (isMissing(qualification.getCertificateNumber())) return false;
	        if (isMissing(qualification.getQualificationLevel())) return false;
	        if (qualification.getStatus() == null
	                || !statuses.contains(qualification.getStatus().toUpperCase())) return false;
	        if (qualification.getValidFrom() != null && qualification.getValidUntil() != null
	                && qualification.getValidUntil().isBefore(qualification.getValidFrom())) return false;
	    }
	    return true;
	}

	/** @brief 写库前的安全备份；失败时返回 503 响应，成功返回 null（调用方直接放行）。 */
	private Response safetyBackupFailure(String label) {
	    SafetyBackupOutcome outcome = backupService.safetyBackup(label);
	    if (outcome.isOk()) return null;
	    return Response.status(Response.Status.SERVICE_UNAVAILABLE)
	            .entity(Map.of("error", outcome.errorCode())).build();
	}

}
