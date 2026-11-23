package org.acme.employeescheduling.rest;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.util.List;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

class ImportWorkbookTemplateTest {
    @Test
    void templateContainsTheSevenStableImportSheets() throws Exception {
        byte[] bytes = new ImportWorkbookService().template();
        assertTrue(bytes.length > 1_000);
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            assertEquals(7, workbook.getNumberOfSheets());
            assertArrayEquals(List.of("审核员", "审核员资格", "时间约束", "地点", "订单", "订单阶段", "已排任务").toArray(),
                    java.util.stream.IntStream.range(0, workbook.getNumberOfSheets())
                            .mapToObj(workbook::getSheetName).toArray());
            assertEquals("employee_code", workbook.getSheet("审核员").getRow(0).getCell(1).getStringCellValue());
            assertEquals("order_phase_id", workbook.getSheet("订单阶段").getRow(0).getCell(0).getStringCellValue());
        }
    }
}
