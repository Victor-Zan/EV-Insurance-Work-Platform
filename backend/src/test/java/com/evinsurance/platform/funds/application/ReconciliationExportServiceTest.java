package com.evinsurance.platform.funds.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.evinsurance.platform.funds.infrastructure.ReconciliationMapper.Row;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

class ReconciliationExportServiceTest {
    @Test void exportsExactDecimalAmountsAndNeutralizesFormulaText() throws Exception {
        var item=new Row(UUID.randomUUID(),"WO-1","=HYPERLINK(1)","保险公司","测试网点","COMPLETED",
            Instant.parse("2026-10-11T00:00:00Z"),new BigDecimal("100.01"),new BigDecimal("40.00"),
            new BigDecimal("80.00"),new BigDecimal("100.01"));
        byte[] bytes=ReconciliationExportService.workbook(List.of(item));
        try(var book=new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            var sheet=book.getSheet("工单资金对账");
            var row=sheet.getRow(1);
            assertThat(row.getCell(1).getStringCellValue()).isEqualTo("'=HYPERLINK(1)");
            assertThat(row.getCell(6).getStringCellValue()).isEqualTo("100.01");
            assertThat(row.getCell(8).getStringCellValue()).isEqualTo("60.01");
            assertThat(row.getCell(11).getStringCellValue()).isEqualTo("-20.01");
            assertThat(row.getCell(11).getCellType()).isEqualTo(org.apache.poi.ss.usermodel.CellType.STRING);
            assertThat(sheet.getPaneInformation().isFreezePane()).isTrue();
        }
    }
}
