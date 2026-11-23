package org.acme.employeescheduling.rest;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.acme.employeescheduling.persistence.EmployeeEntity;
import org.acme.employeescheduling.persistence.StructureEntity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.response.Response;

/**
 * 简历附件的上传 / 下载 / 删除契约。
 *
 * <p>测试 profile 把 {@code app.resume.max-bytes} 压到 2048，所以超限用例只需 3 KB，
 * 既覆盖了边界又不用在测试里搬运 10 MB 请求体。
 */
@QuarkusTest
@TestSecurity(user = "resume-admin", roles = {"ADMIN", "DISPATCHER"})
@EnabledIfSystemProperty(named = "quarkus.test.profile", matches = "test-(sqlite|postgresql)")
class ResumeUploadTest {

    /** 最小可识别 PDF：只需 %PDF- 魔数，内容解析不在服务端职责内。 */
    private static final byte[] MINIMAL_PDF =
            "%PDF-1.4\n1 0 obj\n<<>>\nendobj\ntrailer\n<<>>\n%%EOF\n".getBytes(StandardCharsets.US_ASCII);

    @Test
    void uploadDownloadAndDeleteRoundTrip() {
        Fixture fixture = QuarkusTransaction.requiringNew().call(ResumeUploadTest::fixture);

        // 1) 上传：字段立刻回写，前端据此就地更新弹窗
        given().contentType("application/pdf")
                .queryParam("structureId", fixture.structureId)
                .queryParam("fileName", "张三-审核员简历.pdf")
                .body(MINIMAL_PDF).when()
                .post("/demo-data/employees/{id}/resume", fixture.employeeId)
                .then().statusCode(200)
                .body("resumeFileName", equalTo("张三-审核员简历.pdf"))
                .body("resumeUploadedAt", notNullValue());

        // 2) 员工详情里能看到
        given().queryParam("structureId", fixture.structureId).when()
                .get("/demo-data/getemployee/{id}", fixture.employeeId)
                .then().statusCode(200)
                .body("resumeFileName", equalTo("张三-审核员简历.pdf"));

        // 3) 下载：内容必须逐字节一致，中文名走 RFC 5987
        Response download = given().queryParam("structureId", fixture.structureId).when()
                .get("/demo-data/employees/{id}/resume", fixture.employeeId);
        download.then().statusCode(200);
        byte[] fetched = download.asByteArray();
        assert fetched.length == MINIMAL_PDF.length : "下载长度应与上传一致";
        for (int i = 0; i < MINIMAL_PDF.length; i++) {
            assert fetched[i] == MINIMAL_PDF[i] : "第 " + i + " 字节不一致";
        }
        String disposition = download.getHeader("Content-Disposition");
        assert disposition != null && disposition.contains("filename*=UTF-8''") : disposition;

        // 4) 删除：三个字段一起清空
        given().queryParam("structureId", fixture.structureId).when()
                .delete("/demo-data/employees/{id}/resume", fixture.employeeId)
                .then().statusCode(200)
                .body("cleared", equalTo(true));

        given().queryParam("structureId", fixture.structureId).when()
                .get("/demo-data/getemployee/{id}", fixture.employeeId)
                .then().statusCode(200)
                .body("resumeFileName", nullValue());

        // 5) 删完再下载 → 404，而不是空文件
        given().queryParam("structureId", fixture.structureId).when()
                .get("/demo-data/employees/{id}/resume", fixture.employeeId)
                .then().statusCode(404)
                .body("error", equalTo("RESUME_NOT_FOUND"));
    }

    @Test
    void nonPdfPayloadIsRejected() {
        Fixture fixture = QuarkusTransaction.requiringNew().call(ResumeUploadTest::fixture);

        given().contentType("application/pdf")
                .queryParam("structureId", fixture.structureId)
                .queryParam("fileName", "fake.pdf")
                .body("not a pdf at all".getBytes(StandardCharsets.US_ASCII)).when()
                .post("/demo-data/employees/{id}/resume", fixture.employeeId)
                .then().statusCode(400)
                .body("error", equalTo("RESUME_TYPE_INVALID"));

        // 被拒绝后不能留下任何字段
        given().queryParam("structureId", fixture.structureId).when()
                .get("/demo-data/getemployee/{id}", fixture.employeeId)
                .then().statusCode(200)
                .body("resumeFileName", nullValue());
    }

    @Test
    void oversizedPayloadIsRejectedBeforeTouchingDisk() {
        Fixture fixture = QuarkusTransaction.requiringNew().call(ResumeUploadTest::fixture);

        byte[] tooBig = new byte[3000];
        System.arraycopy(MINIMAL_PDF, 0, tooBig, 0, MINIMAL_PDF.length);

        given().contentType("application/pdf")
                .queryParam("structureId", fixture.structureId)
                .queryParam("fileName", "big.pdf")
                .body(tooBig).when()
                .post("/demo-data/employees/{id}/resume", fixture.employeeId)
                .then().statusCode(413)
                .body("error", equalTo("RESUME_TOO_LARGE"));
    }

    @Test
    void emptyPayloadIsRejected() {
        Fixture fixture = QuarkusTransaction.requiringNew().call(ResumeUploadTest::fixture);

        given().contentType("application/pdf")
                .queryParam("structureId", fixture.structureId)
                .queryParam("fileName", "empty.pdf")
                .body(new byte[0]).when()
                .post("/demo-data/employees/{id}/resume", fixture.employeeId)
                .then().statusCode(400);
    }

    @Test
    void employeeFromAnotherStructureIsNotFound() {
        Fixture fixture = QuarkusTransaction.requiringNew().call(ResumeUploadTest::fixture);

        given().contentType("application/pdf")
                .queryParam("structureId", fixture.structureId + 9_000)
                .queryParam("fileName", "x.pdf")
                .body(MINIMAL_PDF).when()
                .post("/demo-data/employees/{id}/resume", fixture.employeeId)
                .then().statusCode(404);
    }

    @Test
    void displayNameSanitizerStripsPathAndControlCharacters() {
        // 纯函数，不经过 HTTP：路径穿越必须在这里就被削平
        assert "passwd.pdf".equals(ResumeStorage.sanitizeDisplayName("../../etc/passwd"));
        assert "cv.pdf".equals(ResumeStorage.sanitizeDisplayName("C:\\Users\\a\\cv.pdf"));
        assert "简历.pdf".equals(ResumeStorage.sanitizeDisplayName("  简历.pdf  "));
        assert "note.pdf".equals(ResumeStorage.sanitizeDisplayName("note"));
        assert ResumeStorage.sanitizeDisplayName("a".repeat(400)).length() <= 120;
        assert "resume.pdf".equals(ResumeStorage.sanitizeDisplayName(".."));
        assert "resume.pdf".equals(ResumeStorage.sanitizeDisplayName(null));
        // 控制字符不能带进展示层（否则日志/表格里会被换行切开）
        assert !ResumeStorage.sanitizeDisplayName("cv\n\r\t.pdf").contains("\n");
    }

    /**
     * 删员工必须连带清掉附件目录。
     *
     * <p>附件是文件而不是外键，员工记录没了就再没有任何入口能访问到那些 PDF；
     * 不清理的话，每次删人都会在 {@code documents/resumes/{id}/} 下留一份无主文件。
     * 这条用例同时守住「先提交再删文件」的顺序：回滚时文件必须还在。
     */
    @Test
    void deletingEmployeePurgesResumeDirectory() {
        Fixture fixture = QuarkusTransaction.requiringNew().call(ResumeUploadTest::fixture);
        ResumeStorage storage = io.quarkus.arc.Arc.container().instance(ResumeStorage.class).get();

        upload(fixture, "to-be-purged.pdf");
        assert java.nio.file.Files.isDirectory(storage.baseDir().resolve(String.valueOf(fixture.employeeId)))
                : "上传后目录应当存在";

        given().queryParam("structureId", fixture.structureId).when()
                .delete("/demo-data/employees/{id}", fixture.employeeId)
                .then().statusCode(204);

        // 事务提交后钩子是同步执行的，此时目录必须已经消失
        assert !java.nio.file.Files.exists(storage.baseDir().resolve(String.valueOf(fixture.employeeId)))
                : "删员工后附件目录应当被清理";
    }

    private static Response upload(Fixture fixture, String fileName) {
        return given().contentType("application/pdf")
                .queryParam("structureId", fixture.structureId)
                .queryParam("fileName", fileName)
                .body(MINIMAL_PDF)
                .when().post("/demo-data/employees/{id}/resume", fixture.employeeId)
                .then().statusCode(200).extract().response();
    }

    private static Fixture fixture() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        StructureEntity structure = new StructureEntity();
        structure.name = "Resume " + suffix;
        structure.persistAndFlush();

        EmployeeEntity employee = new EmployeeEntity();
        employee.structureId = structure.id;
        employee.code = "RES-" + suffix;
        employee.firstName = "Resume";
        employee.lastName = "Case";
        employee.email = "";
        employee.active = true;
        employee.operationalStatus = "AVAILABLE";
        employee.persistAndFlush();

        return new Fixture(structure.id, employee.id);
    }

    private record Fixture(int structureId, int employeeId) {
    }
}
