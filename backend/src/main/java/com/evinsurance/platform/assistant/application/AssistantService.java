package com.evinsurance.platform.assistant.application;

import com.evinsurance.platform.assistant.infrastructure.AssistantOrderReader;
import com.evinsurance.platform.assistant.infrastructure.AssistantOrderReader.StatusCount;
import com.evinsurance.platform.assistant.infrastructure.BigModelClient;
import com.evinsurance.platform.foundation.api.ApiException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class AssistantService {
    private static final Pattern IDENTITY = Pattern.compile("(?:请问|告诉我)?(?:你(?:是|用的是|用的|使用的是|使用的)(?:哪个|什么)(?:AI|大)?模型|你是谁|"
        + "你用的模型是什么|你当前用什么模型|(?:当前|现在)(?:用的?是?|使用的?是?)(?:哪个|什么)模型)[吗呢？?。！!]*", Pattern.CASE_INSENSITIVE);
    private static final Pattern CAPABILITIES = Pattern.compile("(?:请问)?你(?:能|可以|会)(?:帮我)?(?:做什么|干什么|查什么|做哪些事|回答哪些问题)[吗呢？?。！!]*");
    private final AssistantOrderReader orders;
    private final BigModelClient model;
    private final AssistantGuide guide;
    private final AssistantBusiness business;

    public AssistantService(AssistantOrderReader orders, BigModelClient model, AssistantGuide guide, AssistantBusiness business) {
        this.orders = orders;
        this.model = model;
        this.guide = guide;
        this.business = business;
    }

    public boolean available() { return model.available(); }
    public String modelName() { return model.modelName(); }

    public Reply answer(String input) {
        return answer(input, ignored -> {});
    }

    public Reply answer(String input, Consumer<String> onModelStart) {
        String shortQuestion = input.replaceAll("[\\s\\u3000]+", "");
        if (IDENTITY.matcher(shortQuestion).matches()) {
            String currentModel = modelName();
            return new Reply("我是电动车保险平台的只读助手。"
                + (currentModel == null ? "当前没有启用外部模型。" : "后端当前配置的智谱模型是 " + currentModel + "。"),
                false, null);
        }
        if (CAPABILITIES.matcher(shortQuestion).matches()) {
            return new Reply("我可以分析经营和实际收付，查询工单状态与办理卡点，解释平台操作步骤，或查看已核对的脱敏单据识别结果。",
                false, null);
        }
        if (!available()) throw new ApiException(
            HttpStatus.SERVICE_UNAVAILABLE, "ASSISTANT_DISABLED", "平台助手尚未配置");
        if (asksForOriginal(input)) return new Reply(
            "原件、聊天记录和照片不在助手读取范围内。可以输入工单号，查已核对的单据识别结果。", false, null);
        var question = AssistantQuestion.parse(input);
        if (question == null) {
            var number = AssistantQuestion.singleOrderNumber(input);
            if (recognitionQuestion(input)) return recognition(number, input, onModelStart);
            boolean businessQuestion = AssistantBusiness.cashQuestion(input) || AssistantBusiness.performanceQuestion(input);
            if (businessQuestion) {
                var recent = RecentOrderScope.resolve(input, AssistantBusiness.today());
                if (recent != null) return recentSummary(recent, onModelStart);
            }
            var sections = guide.find(input);
            if (number != null && AssistantBusiness.caseQuestion(input)) {
                var details = business.caseDetails(number);
                if (details == null) return new Reply("没有查到这个工单编号，请核对后再试。", false, null);
                return summarize(Map.of("query", "CASE", "facts", details, "guide", sections),
                    null, "工单核对信息", details.display(), onModelStart);
            }
            if (sections.isEmpty() && businessQuestion) return businessSummary(input, onModelStart);
            if (sections.isEmpty()) return new Reply(
                "可以问「分析本月经营效益」「本月实际到账多少」「怎么提交网点报价」，或带工单号查询进度与卡点。", false, null);
            var reference = sections.stream().map(section -> section.title() + "\n" + section.content())
                .collect(java.util.stream.Collectors.joining("\n\n"));
            return summarize(Map.of("query", "GUIDE", "source", "平台功能手册", "sections", sections),
                null, "平台功能手册", reference, onModelStart);
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
        if (facts.total() == 0) return new Reply(question.kind() == AssistantQuestion.Kind.DETAIL
            ? "没有查到这个工单编号，请核对后再试。" : "当前条件下没有匹配的工单。", false, facts);
        return summarize(Map.of("query", question.kind(), "period", question.period(), "facts", facts),
            facts, null, null, onModelStart);
    }

    private Reply recentSummary(RecentOrderScope scope, Consumer<String> onModelStart) {
        if (scope.clarification() != null) return new Reply(scope.clarification(), false, null);
        var report = business.recent(scope);
        if (report.orders().isEmpty()) return new Reply(report.headline() + "当前没有符合条件的工单。", false, null);
        var reply = summarize(Map.of("query", "RECENT_BUSINESS", "facts", report), null,
            "本次取数与工单明细", report.display(), onModelStart);
        return new Reply(report.headline() + "\n\n" + reply.answer(), reply.ai(), reply.facts(),
            reply.source(), reply.reference(), reply.model());
    }

    private static boolean asksForOriginal(String input) {
        return input.matches("(?s).*(读取|打开|下载|展示|发给我|逐字|转录|看一下|识别一下).*(照片|原件|原图|原文|聊天记录|附件).*")
            || input.matches("(?s).*(照片|原件|原图|原文|聊天记录|附件).*(内容是什么|写了什么|原文是什么|发给我).*");
    }

    private static boolean recognitionQuestion(String input) {
        return input.matches("(?is).*(识别结果|识别出的|识别数据|识别明细|OCR结果|脱敏单据|脱敏识别).*");
    }

    private Reply recognition(String number, String input, Consumer<String> onModelStart) {
        if (number == null) return new Reply("请提供一张工单的完整工单号，再查询已核对的单据识别结果。", false, null);
        boolean quote = input.contains("报价");
        boolean assessment = input.contains("核损") || input.contains("定损");
        String kind = quote == assessment ? null : quote ? "SHOP_QUOTE" : "LOSS_ASSESSMENT";
        var rows = orders.recognizedValues(number, kind);
        if (rows.isEmpty()) return new Reply(
            "没有查到当前有效且已人工确认并用于业务的对应识别结果。请核对工单号或该单据的采用状态。", false, null);
        var values = rows.stream().map(row -> new DocumentValue(row.kind(), row.lineNo(), row.quantity(),
            AssistantBusiness.money(row.unitPrice()), AssistantBusiness.money(row.amount()))).toList();
        long total = rows.getFirst().matchedCount();
        var reference = new java.util.ArrayList<String>();
        reference.add(number + " · " + total + " 项");
        values.forEach(value -> reference.add(value.display()));
        if (total > values.size()) reference.add("仅显示前 " + values.size() + " 项");
        return summarize(Map.of("query", "RECOGNITION", "businessNo", number, "total", total, "values", values),
            null, "已核对的单据识别结果", String.join("\n", reference), onModelStart);
    }

    private Reply businessSummary(String input, Consumer<String> onModelStart) {
        var period = AssistantBusiness.period(input, AssistantBusiness.today());
        if (period == null) return new Reply("目前经营分析支持今天、本月、上月或指定年月，请明确要查询的月份。", false, null);
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
        return summarize(Map.of("query", "BUSINESS", "facts", facts), null, "经营与资金汇总",
            String.join("\n\n", lines), onModelStart);
    }

    private Reply summarize(Map<String, Object> payload, Facts facts, String source, String reference,
                            Consumer<String> onModelStart) {
        String modelName = modelName();
        onModelStart.accept(modelName);
        try {
            return new Reply(model.answer(payload), true, facts, source, reference, modelName);
        } catch (ApiException error) {
            return new Reply(error.getMessage() + "。请查看下方平台资料。", false, facts, source, reference, modelName);
        }
    }

    public record Reply(String answer, boolean ai, Facts facts, String source, String reference, String model) {
        Reply(String answer, boolean ai, Facts facts) { this(answer, ai, facts, null, null, null); }
    }
    public record Facts(long total, List<StatusCount> counts, List<Entry> orders, String totalCny) {}
    public record Entry(String businessNo, String status, Instant updatedAt) {}

    private record DocumentValue(String kind, int lineNo, Integer quantity, String unitPriceCny, String amountCny) {
        String display() {
            if ("LOSS_ASSESSMENT".equals(kind)) return "核损单 · 已采用金额 " + amountCny + " 元";
            return "网点报价第 " + lineNo + " 行 · 数量 " + quantity + " · 单价 " + unitPriceCny + " 元 · 小计 " + amountCny + " 元";
        }
    }
}
