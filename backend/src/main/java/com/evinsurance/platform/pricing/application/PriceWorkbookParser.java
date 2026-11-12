package com.evinsurance.platform.pricing.application;

import com.evinsurance.platform.document.application.FileService;
import com.evinsurance.platform.foundation.api.ApiException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFCell;
import org.springframework.web.multipart.MultipartFile;

final class PriceWorkbookParser {
    static final String SHEET = "导入文档";
    private static final int MAX_BYTES = 10 * 1024 * 1024;
    private static final String INVALID_CELL = "〈公式或错误值〉";
    record Row(int number, String brand, String model, String part, String unit, String region, String supplier, String cp) {}
    record Workbook(String fileName, String hash, List<Row> rows) {}

    private PriceWorkbookParser() {}

    static Workbook read(MultipartFile file) {
        if (file == null || file.isEmpty() || file.getSize() > MAX_BYTES)
            throw ApiException.invalid("请选择不超过 10 MiB 的 XLSX 文件");
        var name = Objects.toString(file.getOriginalFilename(), "").replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1);
        if (!name.toLowerCase(Locale.ROOT).endsWith(".xlsx") || name.length() > 120)
            throw ApiException.invalid("请使用 XLSX 文件");

        try (var input = file.getInputStream()) {
            var bytes = input.readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) throw ApiException.invalid("文件超过 10 MiB");
            try (var book = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
                var sheet = book.getSheet(SHEET);
                if (sheet == null || sheet.getLastRowNum() > 20003)
                    throw ApiException.invalid("工作簿需包含“导入文档”工作表，且行号在支持范围内");
                var format = new DataFormatter(Locale.ROOT);
                requireHeader(sheet, format, 1, 9, "电动车品牌");
                requireHeader(sheet, format, 1, 10, "电动车车型");
                requireHeader(sheet, format, 2, 13, "受损部位");
                requireHeader(sheet, format, 2, 14, "单位");
                requireHeader(sheet, format, 2, 15, "配件商报价金额");
                requireHeader(sheet, format, 2, 16, "CP报价金额");
                int regionColumn = regionColumn(sheet, format);
                var rows = new ArrayList<Row>();
                for (int i = 4; i <= sheet.getLastRowNum(); i++) {
                    var row = sheet.getRow(i);
                    if (row == null) continue;
                    var part = value(row.getCell(13), format);
                    var unit = value(row.getCell(14), format);
                    var supplier = amountValue(row.getCell(15), format);
                    var cp = amountValue(row.getCell(16), format);
                    if (part.isEmpty() && unit.isEmpty() && supplier.isEmpty() && cp.isEmpty()) continue;
                    rows.add(new Row(i + 1, value(row.getCell(9), format), value(row.getCell(10), format), part, unit,
                        regionColumn < 0 ? "" : value(row.getCell(regionColumn), format), supplier, cp));
                    if (rows.size() > 2000) throw ApiException.invalid("一次最多预览 2000 条配件明细");
                }
                if (rows.isEmpty()) throw ApiException.invalid("未找到配件明细");
                return new Workbook(name, FileService.hash(bytes), List.copyOf(rows));
            }
        } catch (IOException | org.apache.poi.openxml4j.exceptions.NotOfficeXmlFileException | org.apache.poi.ooxml.POIXMLException error) {
            throw ApiException.invalid("无法读取 XLSX 工作簿");
        }
    }

    static String issue(Row row) {
        if (Set.of("", "/", "-", "—").contains(row.unit())) return "缺少明确单位";
        if (Set.of("全车", "整车").contains(row.part()) || row.part().contains("工时费")
            || row.part().endsWith("服务费") || row.part().endsWith("拖车费")) return "非配件项目";
        if (row.brand().isEmpty() || row.part().isEmpty()) return "缺少配件名称或电动车品牌";
        var fields = List.of(row.brand(), row.model(), row.part(), row.region(), row.unit());
        if (fields.contains(INVALID_CELL)) return "价格资料字段含公式或错误";
        if (row.unit().length() > 24 || fields.stream().anyMatch(field -> field.length() > 120)) return "价格资料字段过长";
        return "";
    }

    private static int regionColumn(Sheet sheet, DataFormatter format) {
        int city = -1;
        for (int i = 1; i <= 3; i++) {
            var row = sheet.getRow(i);
            if (row == null) continue;
            for (var cell : row) {
                String title = header(cell, format);
                if ("地区".equals(title)) return cell.getColumnIndex();
                if ("市".equals(title)) city = cell.getColumnIndex();
            }
        }
        return city;
    }

    private static void requireHeader(Sheet sheet, DataFormatter format, int row, int col, String expected) {
        if (sheet.getRow(row) == null || !header(sheet.getRow(row).getCell(col), format).equalsIgnoreCase(expected))
            throw ApiException.invalid("“导入文档”表头不匹配，缺少 " + expected);
    }

    private static String header(Cell cell, DataFormatter format) {
        return value(cell, format).replace("*", "").replace("^", "").strip();
    }

    private static String value(Cell cell, DataFormatter format) {
        if (cell == null) return "";
        if (cell.getCellType() == CellType.FORMULA || cell.getCellType() == CellType.ERROR) return INVALID_CELL;
        return format.formatCellValue(cell).strip();
    }

    private static String amountValue(Cell cell, DataFormatter format) {
        if (cell instanceof XSSFCell number && cell.getCellType() == CellType.NUMERIC
            && !DateUtil.isCellDateFormatted(cell) && number.getRawValue() != null) {
            var value = new BigDecimal(number.getRawValue()).stripTrailingZeros();
            return Math.abs((long)value.scale()) <= 300 ? value.toPlainString() : value.toString();
        }
        return value(cell, format);
    }
}
