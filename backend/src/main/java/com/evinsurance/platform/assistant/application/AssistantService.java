package com.evinsurance.platform.assistant.application;

import com.evinsurance.platform.assistant.infrastructure.AssistantOrderReader;
import com.evinsurance.platform.assistant.infrastructure.AssistantOrderReader.StatusCount;
import com.evinsurance.platform.assistant.infrastructure.BigModelClient;
import com.evinsurance.platform.assistant.infrastructure.BigModelClient.Turn;
import com.evinsurance.platform.foundation.api.ApiException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class AssistantService {
    private final AssistantOrderReader orders;
    private final BigModelClient model;
    private final AssistantGuide guide;
    private final AssistantBusiness business;
    private final AssistantPrivacy privacy;

    public AssistantService(AssistantOrderReader orders, BigModelClient model, AssistantGuide guide, AssistantBusiness business, AssistantPrivacy privacy) {
        this.orders = orders;
        this.model = model;
        this.guide = guide;
        this.business = business;
        this.privacy = privacy;
    }

    public boolean available() { return model.available(); }
    public String modelName() { return model.modelName(); }

    public Reply answer(String input, List<Turn> history, Consumer<String> onModelStart) {
        if (privacy.blocks(input, history)) return new Reply(
            "本次对话含敏感信息，无法发送。请移除相关内容；若信息在前文，请新建对话。", false, null, null, null, null, true);
        if (!available()) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "ASSISTANT_DISABLED", "平台助手尚未配置");
        String name = modelName();
        onModelStart.accept(name);
        Material material = null;
        try {
            var first = model.start(input, history);
            var tool = first.lookup();
            boolean explicitSample = hasFinancialSample(input);
            // GLM can answer a platform question without requesting its source. Check the first
            // answer against available platform material before displaying guessed steps or figures.
            if (tool == null && !explicitSample && !needsPlatformSource(input)) {
                return new Reply(first.answer(), true, null, null, null, name, false);
            }
            String requested = tool == null ? input : tool.question();
            String query = history.isEmpty() || explicitSample ? input : requested;
            material = lookup(query);
            if (material == null && !query.equals(requested)) material = lookup(requested);
            if (material == null) material = Material.notice("未找到与问题对应的平台资料，请明确工单、时间范围或操作入口。");
            if (material.payload() == null) return material.reply(material.headline(), false, name);
            if (!requested.equals(input) && privacy.blocks(requested, List.of())) {
                return material.reply("平台查询参数含敏感信息，无法继续发送。", false, name);
            }
            String answer = model.finish(input, history, tool, material.payload());
            if (material.headline() != null) answer = material.headline() + "\n\n" + answer;
            return material.reply(answer, true, name);
        } catch (ApiException error) {
            if (material == null && (history.isEmpty() || hasFinancialSample(input))) material = lookup(input);
            if (material == null) return new Reply(error.getMessage(), false, null, null, null, name, false);
            String hint = material.reference() != null || material.facts() != null ? "。请查看下方平台资料。" : "";
            String summary = material.headline() == null ? "" : material.headline() + "\n\n";
            return material.reply(summary + error.getMessage() + hint, false, name);
        }
    }

    private static boolean hasFinancialSample(String input) {
        return RecentOrderScope.hasExplicitRange(input)
            && (AssistantBusiness.performanceQuestion(input) || AssistantBusiness.cashQuestion(input));
    }

    private boolean needsPlatformSource(String input) {
        if (AssistantQuestion.parse(input) != null || AssistantQuestion.singleOrderNumber(input) != null) return true;
        if (input.matches("(?s).*(本月|上月|今天|今日|最近|近[一二三四五六七八九十0-9]|[0-9]{4}年).*")
            && (AssistantBusiness.performanceQuestion(input) || AssistantBusiness.cashQuestion(input))) return true;
        return !guide.find(input).isEmpty();
    }

    private Material lookup(String input) {
        if (asksForOriginal(input)) {
            if (AssistantGuide.asksForInstructions(input)) {
                var sections = guide.find(input);
                if (!sections.isEmpty()) return guideMaterial(sections);
            }
            return Material.notice("原件、聊天记录和照片不在助手读取范围内。可以输入工单号，查已核对的单据识别结果。");
        }
        var question = AssistantQuestion.parse(input);
        if (question == null) {
            var number = AssistantQuestion.singleOrderNumber(input);
            if (recognitionQuestion(input)) {
                if (number == null && AssistantGuide.asksForInstructions(input)) {
                    var sections = guide.find(input);
                    if (!sections.isEmpty()) return guideMaterial(sections);
                }
                return recognition(number, input);
            }
            boolean businessQuestion = AssistantBusiness.cashQuestion(input) || AssistantBusiness.performanceQuestion(input);
            if (businessQuestion) {
                var recent = RecentOrderScope.resolve(input, AssistantBusiness.today());
                if (recent != null) return recentSummary(recent, AssistantBusiness.revenueQuestion(input));
            }
            var sections = guide.find(input);
            if (number != null && AssistantBusiness.caseQuestion(input)) {
                var details = business.caseDetails(number);
                if (details == null) return Material.notice("没有查到这个工单编号，请核对后再试。");
                return new Material(Map.of("query", "CASE", "facts", details, "guide", sections),
                    null, "工单核对信息", details.display(), null);
            }
            if (sections.isEmpty() && businessQuestion) return businessSummary(input);
            if (sections.isEmpty()) return null;
            return guideMaterial(sections);
        }
        var window = question.period().window();
        var facts = switch (question.kind()) {
            case COUNT -> {
                var counts = orders.counts(question.status(), window.start(), window.end());
                yield new Facts(counts.stream().mapToLong(StatusCount::count).sum(), counts, List.of(), null);
            }
            case AMOUNT -> {
                var amount = orders.confirmedAmount(window.start(), window.end());
                yield new Facts(amount.count(), List.of(), List.of(), amount.totalCny().setScale(2).toPlainString());
            }
            case DETAIL, LIST -> {
                var rows = orders.orders(question.status(), window.start(), window.end(), question.orderNo());
                long total = rows.isEmpty() ? 0 : rows.getFirst().matchedCount();
                var entries = rows.stream().map(row -> new Entry(row.businessNo(), row.status(), row.updatedAt())).toList();
                yield new Facts(total, List.of(), entries, null);
            }
        };
        if (facts.total() == 0) return new Material(null, facts, null, null, question.kind() == AssistantQuestion.Kind.DETAIL
            ? "没有查到这个工单编号，请核对后再试。" : "当前条件下没有匹配的工单。");
        return new Material(Map.of("query", question.kind(), "period", question.period(), "facts", facts),
            facts, null, null, null);
    }

    private static Material guideMaterial(List<AssistantGuide.Section> sections) {
        var reference = sections.stream().map(section -> section.title() + "\n" + section.content())
            .collect(java.util.stream.Collectors.joining("\n\n"));
        return new Material(Map.of("query", "GUIDE", "source", "平台功能手册", "sections", sections),
            null, "平台功能手册", reference, null);
    }

    private Material recentSummary(RecentOrderScope scope, boolean showRevenue) {
        if (scope.clarification() != null) return Material.notice(scope.clarification());
        var report = business.recent(scope);
        if (report.orders().isEmpty()) return Material.notice(report.headline() + "当前没有符合条件的工单。");
        String headline = showRevenue ? report.headline() + "\n" + report.revenueSummary() : report.headline();
        return new Material(Map.of("query", "RECENT_BUSINESS", "facts", report), null,
            "本次取数与工单明细", report.display(), headline);
    }

    private static boolean asksForOriginal(String input) {
        return input.matches("(?s).*(读取|打开|下载|展示|发给我|逐字|转录|看一下|识别一下).*(照片|原件|原图|原文|聊天记录|附件).*")
            || input.matches("(?s).*(照片|原件|原图|原文|聊天记录|附件).*(内容是什么|写了什么|原文是什么|发给我).*");
    }

    private static boolean recognitionQuestion(String input) {
        return input.matches("(?is).*(识别结果|识别出的|识别数据|识别明细|OCR结果|脱敏单据|脱敏识别).*");
    }

    private Material recognition(String number, String input) {
        if (number == null) return Material.notice("请提供一张工单的完整工单号，再查询已核对的单据识别结果。");
        boolean quote = input.contains("报价");
        boolean assessment = input.contains("核损") || input.contains("定损");
        String kind = quote == assessment ? null : quote ? "SHOP_QUOTE" : "LOSS_ASSESSMENT";
        var rows = orders.recognizedValues(number, kind);
        if (rows.isEmpty()) return Material.notice("没有查到当前有效且已人工确认并用于业务的对应识别结果。请核对工单号或该单据的采用状态。");
        var values = rows.stream().map(row -> new DocumentValue(row.kind(), row.lineNo(), row.quantity(),
            AssistantBusiness.money(row.unitPrice()), AssistantBusiness.money(row.amount()))).toList();
        long total = rows.getFirst().matchedCount();
        var reference = new java.util.ArrayList<String>();
        reference.add(number + " · " + total + " 项");
        values.forEach(value -> reference.add(value.display()));
        if (total > values.size()) reference.add("仅显示前 " + values.size() + " 项");
        return new Material(Map.of("query", "RECOGNITION", "businessNo", number, "total", total, "values", values),
            null, "已核对的单据识别结果", String.join("\n", reference), null);
    }

    private Material businessSummary(String input) {
        var period = AssistantBusiness.period(input, AssistantBusiness.today());
        if (period == null) return Material.notice("目前经营分析支持今天、本月、上月或指定年月，请明确要查询的月份。");
        boolean cash = AssistantBusiness.cashQuestion(input);
        boolean performance = AssistantBusiness.performanceQuestion(input);
        var facts = new java.util.LinkedHashMap<String, Object>();
        var lines = new java.util.ArrayList<String>();
        if (performance) {
            var report = business.performance(period);
            facts.put("performance", report);
            lines.add("经营看板 · 按有效完工日期\n" + report.display());
        }
        if (cash) {
            var flow = business.cash(period);
            facts.put("cash", flow);
            lines.add("实际记账 · 按资金发生日期，排除已冲销记录\n" + flow.current().display() + "\n对比期 " + flow.comparison().display());
        }
        return new Material(Map.of("query", "BUSINESS", "facts", facts), null, "经营与资金汇总",
            String.join("\n\n", lines), null);
    }

    private record Material(Map<String, Object> payload, Facts facts, String source, String reference, String headline) {
        static Material notice(String text) { return new Material(null, null, null, null, text); }
        Reply reply(String answer, boolean ai, String model) { return new Reply(answer, ai, facts, source, reference, model, false); }
    }

    public record Reply(String answer, boolean ai, Facts facts, String source, String reference, String model, boolean blocked) {}
    public record Facts(long total, List<StatusCount> counts, List<Entry> orders, String totalCny) {}
    public record Entry(String businessNo, String status, Instant updatedAt) {}

    private record DocumentValue(String kind, int lineNo, Integer quantity, String unitPriceCny, String amountCny) {
        String display() {
            if ("LOSS_ASSESSMENT".equals(kind)) return "核损单 · 已采用金额 " + amountCny + " 元";
            return "网点报价第 " + lineNo + " 行 · 数量 " + quantity + " · 单价 " + unitPriceCny + " 元 · 小计 " + amountCny + " 元";
        }
    }
}
