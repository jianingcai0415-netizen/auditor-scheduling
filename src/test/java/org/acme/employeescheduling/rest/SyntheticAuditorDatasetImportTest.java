package org.acme.employeescheduling.rest;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import org.acme.employeescheduling.persistence.StructureEntity;
import org.acme.employeescheduling.persistence.EmployeeEntity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;

/** Optional smoke test for the externally generated 500-auditor workbook. */
@QuarkusTest
@TestSecurity(user = "dataset-admin", roles = {"ADMIN", "DISPATCHER"})
@EnabledIfSystemProperty(named = "auditor.dataset.path", matches = ".+")
class SyntheticAuditorDatasetImportTest {
    @Inject DemoDataRepository repository;

    @Test
    void fiveHundredPersonWorkbookPassesTheProductionPreview() throws Exception {
        int structureId = QuarkusTransaction.requiringNew().call(() -> {
            StructureEntity entity = new StructureEntity();
            entity.name = "Dataset " + UUID.randomUUID(); entity.persistAndFlush();
            return entity.id;
        });
        // Warm the solver-facing employee cache before import. Confirm must invalidate this entry
        // after commit, otherwise a subsequent solve still sees the old empty workforce.
        assertEquals(0, repository.getEmployees(structureId).size());
        byte[] workbook = Files.readAllBytes(Path.of(System.getProperty("auditor.dataset.path")));
        var response = given().contentType("application/octet-stream").body(workbook)
                .queryParam("structureId", structureId).queryParam("fileName", "synthetic-500.xlsx")
                .when().post("/imports/preview").then().statusCode(200)
                .body("summary.auditors", equalTo(500))
                .body("errorCount", equalTo(0))
                .body("confirmable", equalTo(true)).extract();
        String token = response.jsonPath().getString("token");
        given().contentType("application/json").body("{}").queryParam("structureId", structureId)
                .when().post("/imports/{token}/confirm", token).then().statusCode(200)
                .body("status", equalTo("CONFIRMED"));
        assertEquals(500L, QuarkusTransaction.requiringNew().call(
                () -> EmployeeEntity.count("structureId", structureId)));
        assertEquals(500, repository.getEmployees(structureId).size());
    }
}
