package com.evinsurance.platform.pricing.application;

import com.evinsurance.platform.document.application.FileService;
import com.evinsurance.platform.foundation.api.ApiException;
import com.evinsurance.platform.foundation.api.PageResponse;
import com.evinsurance.platform.pricing.application.HistoricalPriceImportService.Quote;
import com.evinsurance.platform.pricing.application.HistoricalPriceImportService.WorkbookQuote;
import com.evinsurance.platform.pricing.domain.PricingAccess;
import com.evinsurance.platform.pricing.infrastructure.SampleMapper;
import com.evinsurance.platform.pricing.infrastructure.SampleMapper.SourceQuote;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class WorkbookPriceImportService {
    private static final String SUPPLIER = "SUPPLIER_QUOTED";
    private static final String CP = "CP_QUOTED";
    public record Options(String excludeRows, Integer correctionRow, String correctionNature, String correctionAmount) {}
    public record Price(String original, String amount, String status, String note) {}
    public record Row(int rowNumber, String brand, String model, String part, String unit, String region,
                      Price supplier, Price cp, String note) {}
    public record Preview(String fileName, String fingerprint, int ready, int duplicates, int invalid, int skippedRows,
                          PageResponse<Row> rows) {}
    public record Result(int imported, int duplicates, int invalid, int skippedRows) {}
    private record Prepared(String fileName, String hash, String fingerprint, List<Row> rows,
                            List<WorkbookQuote> quotes, int duplicates, int invalid, int skipped) {}
    private final SampleMapper mapper;
    private final HistoricalPriceImportService writer;

    public WorkbookPriceImportService(SampleMapper mapper, HistoricalPriceImportService writer) {
        this.mapper = mapper;
        this.writer = writer;
    }

    public Preview preview(MultipartFile file, Options options, int page, int size) {
        int offset = PageResponse.offset(page, size);
        var prepared = prepare(file, options, false, null);
        int start = Math.min(offset, prepared.rows().size());
        var rows = new PageResponse<>(page, size, prepared.rows().size(),
            prepared.rows().subList(start, Math.min(start + size, prepared.rows().size())));
        return new Preview(prepared.fileName(), prepared.fingerprint(), prepared.quotes().size(),
            prepared.duplicates(), prepared.invalid(), prepared.skipped(), rows);
    }

    @Transactional
    public Result confirm(MultipartFile file, Options options, String fingerprint) {
        var prepared = prepare(file, options, true, fingerprint);
        int imported = writer.importWorkbook(prepared.hash(), prepared.fileName(), prepared.quotes());
        return new Result(imported, prepared.duplicates(), prepared.invalid(), prepared.skipped());
    }

    private Prepared prepare(MultipartFile file, Options options, boolean confirming, String expectedFingerprint) {
        var actor = PricingAccess.write();
        var workbook = PriceWorkbookParser.read(file);
        var excluded = excludedRows(options.excludeRows());
        var corrected = correction(options, excluded, workbook.rows());
        String fingerprint = FileService.hash((actor.id() + ":" + workbook.hash() + ":" + excluded + ":" +
            options.correctionRow() + ":" + options.correctionNature() + ":" + corrected).getBytes(StandardCharsets.UTF_8));
        if (confirming) {
            if (!fingerprint.equals(expectedFingerprint)) throw ApiException.conflict("PRICE_PREVIEW_CHANGED", "文件或导入选项已变更，请重新预览");
            mapper.lockSource(HistoricalPriceImportService.CATALOGUE_LOCK);
        }
        var existing = new HashSet<>(mapper.existingWorkbookQuotes(workbook.hash(), true));
        var preview = new ArrayList<Row>();
        var quotes = new ArrayList<WorkbookQuote>();
        int duplicates = 0, invalid = 0, skipped = 0;
        for (var row : workbook.rows()) {
            String note = excluded.contains(row.number()) ? "已指定排除" : PriceWorkbookParser.issue(row);
            var supplier = price(row, SUPPLIER, row.supplier(), note, options, corrected, existing, quotes);
            var cp = price(row, CP, row.cp(), note, options, corrected, existing, quotes);
            for (var quote : List.of(supplier, cp)) {
                if ("DUPLICATE".equals(quote.status())) duplicates++;
                if ("INVALID".equals(quote.status())) invalid++;
            }
            if (!note.isEmpty()) skipped++;
            preview.add(new Row(row.number(), row.brand(), row.model().isEmpty() ? "车型未注明" : row.model(), row.part(),
                row.unit(), row.region().isEmpty() ? "全国" : row.region(), supplier, cp, note));
        }
        return new Prepared(workbook.fileName(), workbook.hash(), fingerprint, preview, quotes, duplicates, invalid, skipped);
    }

    private Price price(PriceWorkbookParser.Row row, String nature, String original, String rowNote, Options options,
                        BigDecimal corrected, Set<SourceQuote> existing, List<WorkbookQuote> quotes) {
        if (!rowNote.isEmpty()) return new Price(original, null, "SKIPPED", null);
        if (existing.contains(new SourceQuote(row.number(), nature))) return new Price(original, null, "DUPLICATE", "该来源行已有记录");
        boolean changed = options.correctionRow() != null && options.correctionRow() == row.number() && nature.equals(options.correctionNature());
        var amount = changed ? corrected : amount(original);
        if (amount == null) return new Price(original, null, "INVALID", original.isEmpty() ? "缺少报价" : "须为最多两位小数的正金额");
        quotes.add(new WorkbookQuote(new Quote(row.number(), row.part(), row.unit(), nature, original, amount), row.brand(), row.model(), row.region()));
        return new Price(original, amount.toPlainString(), "READY", changed ? "已指定更正" : null);
    }

    private BigDecimal amount(String raw) {
        if (raw == null || !raw.strip().matches("[0-9]{1,16}(\\.[0-9]{1,2})?")) return null;
        var value = new BigDecimal(raw.strip());
        return value.signum() > 0 ? value : null;
    }

    private BigDecimal correction(Options options, Set<Integer> excluded, List<PriceWorkbookParser.Row> rows) {
        var row = options.correctionRow();
        if (row == null && (options.correctionAmount() == null || options.correctionAmount().isBlank())) return null;
        var amount = amount(options.correctionAmount());
        if (row == null || amount == null || !Set.of(SUPPLIER, CP).contains(options.correctionNature() == null ? "" : options.correctionNature())
            || excluded.contains(row) || rows.stream().noneMatch(item -> item.number() == row && PriceWorkbookParser.issue(item).isEmpty()))
            throw ApiException.invalid("更正须指定可导入的 Excel 行、报价类型和有效金额");
        return amount;
    }

    private Set<Integer> excludedRows(String input) {
        var rows = new TreeSet<Integer>();
        if (input == null || input.isBlank()) return rows;
        if (input.length() > 4000) throw ApiException.invalid("排除行号过长");
        for (String token : input.strip().split("[,，\\s]+")) {
            if (!token.matches("[0-9]{1,5}([-–—][0-9]{1,5})?")) throw ApiException.invalid("排除行使用 Excel 行号或区间，如 62-68");
            var range = token.split("[-–—]");
            int start = Integer.parseInt(range[0]), end = range.length == 2 ? Integer.parseInt(range[1]) : start;
            if (start < 5 || end < start || end > 20004 || end - start > 1999)
                throw ApiException.invalid("排除行须为有效 Excel 明细行号");
            for (int row = start; row <= end; row++) rows.add(row);
            if (rows.size() > 2000) throw ApiException.invalid("最多排除 2000 行");
        }
        return rows;
    }
}
