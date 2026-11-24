package com.evinsurance.platform.pricing.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

class PriceWorkbookParserTest {
    @Test
    void regionColumnsFallBackPerRowInPriorityOrder() throws Exception {
        try (var book = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            var sheet = book.createSheet(PriceWorkbookParser.SHEET);
            var parent = sheet.createRow(1);
            parent.createCell(2).setCellValue("机构*");
            parent.createCell(9).setCellValue("电动车品牌*");
            parent.createCell(10).setCellValue("电动车车型");
            var detail = sheet.createRow(2);
            detail.createCell(13).setCellValue("受损部位");
            detail.createCell(14).setCellValue("单位");
            detail.createCell(15).setCellValue("配件商报价金额");
            detail.createCell(16).setCellValue("CP报价金额");
            var location = sheet.createRow(3);
            location.createCell(19).setCellValue("市");
            location.createCell(20).setCellValue("地区");
            for (int i = 0; i < 4; i++) {
                var row = sheet.createRow(4 + i);
                row.createCell(13).setCellValue("合成配件");
                if (i < 3) row.createCell(2).setCellValue(" 东莞 ");
                if (i < 2) row.createCell(19).setCellValue("深圳");
                if (i == 0) row.createCell(20).setCellValue("广州");
            }
            book.write(output);
            var file = new MockMultipartFile("file", "synthetic.xlsx", null, output.toByteArray());
            assertThat(PriceWorkbookParser.read(file).rows()).extracting(PriceWorkbookParser.Row::region)
                .containsExactly("广州", "深圳", "东莞", "");
        }
    }
}
