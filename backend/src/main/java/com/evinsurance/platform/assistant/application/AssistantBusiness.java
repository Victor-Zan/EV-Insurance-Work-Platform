package com.evinsurance.platform.assistant.application;

import com.evinsurance.platform.assistant.infrastructure.AssistantOrderReader;
import com.evinsurance.platform.funds.infrastructure.AnalyticsMapper;
import com.evinsurance.platform.workorder.domain.WorkOrderStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AssistantBusiness {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter LOCAL_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZONE);
    private static final Pattern MONTH = Pattern.compile("(?<![0-9])([0-9]{4})(?:年|-)(0?[1-9]|1[0-2])月?(?![0-9])");
    private final AnalyticsMapper analytics;
    private final AssistantOrderReader orders;

    public AssistantBusiness(AnalyticsMapper analytics, AssistantOrderReader orders) {
        this.analytics = analytics;
        this.orders = orders;
    }

    static boolean cashQuestion(String input) {
        return input.matches("(?s).*(实际到账|实际回款|实际结算|实收|实付|进账|现金流|回款多少|到账多少|结算多少|收了多少|付了多少).*");
    }

    static boolean revenueQuestion(String input) {
        return input.contains("营收") || input.contains("收入") || input.contains("营业额") || input.contains("保险应收");
    }

    static boolean performanceQuestion(String input) {
        return revenueQuestion(input) || input.matches("(?s).*(经营|业绩|毛利|盈利|利润|赚了多少).*");
    }

    static boolean caseQuestion(String input) {
        return revenueQuestion(input) || input.matches("(?s).*(为什么|不能|不到|不了|卡住|怎么办|下一步|授权|前置|进度|任务|材料|确认|核损|报价|应收|应付|回款|结算|到账|实收|实付|利润|盈利|毛利).*");
    }

    static Period period(String input, LocalDate today) {
        var match = MONTH.matcher(input);
        if (match.find()) {
            var requested = YearMonth.of(Integer.parseInt(match.group(1)), Integer.parseInt(match.group(2)));
            return requested.getYear() == 0 || requested.isAfter(YearMonth.from(today)) ? null : monthly(requested, today);
        }
        if (input.matches("(?s).*(昨天|昨日|周|季度|半年|今年|去年|近[0-9一二三四五六七八九十]+[天日月]|最近[0-9一二三四五六七八九十]+[天日月]|[0-9]+日).*")) {
            return null;
        }
        if (input.contains("今天") || input.contains("今日")) {
            return new Period(today, today, today.minusDays(1), today.minusDays(1));
        }
        YearMonth current = YearMonth.from(today);
        return monthly(input.contains("上月") || input.contains("上个月") ? current.minusMonths(1) : current, today);
    }

    private static Period monthly(YearMonth month, LocalDate today) {
        boolean partial = month.equals(YearMonth.from(today));
        var prior = month.minusMonths(1);
        var to = partial ? today : month.atEndOfMonth();
        var priorTo = partial ? prior.atDay(Math.min(to.getDayOfMonth(), prior.lengthOfMonth())) : prior.atEndOfMonth();
        return new Period(month.atDay(1), to, prior.atDay(1), priorTo);
    }

    static LocalDate today() { return LocalDate.now(ZONE); }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Performance performance(Period period) {
        return new Performance(metrics(period.from(), period.to()), metrics(period.compareFrom(), period.compareTo()));
    }

    private Metrics metrics(LocalDate from, LocalDate to) {
        var row = analytics.summary(Map.of("start", from.atStartOfDay(ZONE).toInstant(),
            "end", to.plusDays(1).atStartOfDay(ZONE).toInstant(), "pending", false));
        long completed = ((Number) row.get("completed")).longValue();
        long accounted = ((Number) row.get("accounted")).longValue();
        return new Metrics(from.toString(), to.toString(), completed, accounted, completed - accounted,
            row.get("insurance").toString(), row.get("payable").toString(), row.get("profit").toString(),
            (String) row.get("margin"));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public CashComparison cash(Period period) {
        return new CashComparison(cashRange(period.from(), period.to()), cashRange(period.compareFrom(), period.compareTo()));
    }

    private Cash cashRange(LocalDate from, LocalDate to) {
        var row = orders.cash(from.atStartOfDay(ZONE).toInstant(), to.plusDays(1).atStartOfDay(ZONE).toInstant());
        return new Cash(from.toString(), to.toString(), money(row.received()), money(row.paid()),
            money(row.received().subtract(row.paid())));
    }

    public RecentReport recent(RecentOrderScope scope) {
        var start = scope.from() == null ? null : scope.from().atStartOfDay(ZONE).toInstant();
        var end = scope.to() == null ? null : scope.to().plusDays(1).atStartOfDay(ZONE).toInstant();
        var rows = orders.recentFinance(scope.limit(), scope.completedOnly(), start, end);
        var accounted = rows.stream().filter(row -> row.profit() != null).toList();
        var insurance = total(accounted, AssistantOrderReader.RecentFinance::insurance);
        var profit = total(accounted, AssistantOrderReader.RecentFinance::profit);
        var margin = insurance.signum() == 0 ? null
            : profit.movePointRight(2).divide(insurance, 2, java.math.RoundingMode.HALF_UP).toPlainString();
        var selected = rows.stream().map(row -> new SelectedOrder(row.businessNo(), AssistantQuestion.statusLabel(row.status()),
            LOCAL_TIME.format(row.selectedAt()), money(row.formalQuote()), money(row.assessment()), money(row.insurance()), money(row.profit()),
            money(row.received()), money(row.paid()))).toList();
        String sort = scope.completedOnly() ? "按有效完工时间倒序，仅已完工工单" : "按创建时间倒序，仅正式编号工单";
        String window = scope.from() == null ? "不限日期" : scope.from() + " 至 " + scope.to() + "（北京时间）";
        return new RecentReport(scope.limit(), sort, window, selected,
            rows.stream().filter(row -> "COMPLETED".equals(row.status())).count(), accounted.size(),
            accounted.isEmpty() ? null : money(insurance),
            accounted.isEmpty() ? null : money(total(accounted, AssistantOrderReader.RecentFinance::payable)),
            accounted.isEmpty() ? null : money(profit), margin,
            money(total(rows, AssistantOrderReader.RecentFinance::received)), money(total(rows, AssistantOrderReader.RecentFinance::paid)));
    }

    private static BigDecimal total(List<AssistantOrderReader.RecentFinance> rows,
                                    Function<AssistantOrderReader.RecentFinance, BigDecimal> amount) {
        return rows.stream().map(amount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public CaseDetails caseDetails(String number) {
        var row = orders.caseDetails(number);
        if (row == null) return null;
        var status = WorkOrderStatus.valueOf(row.status());
        var next = row.deletionPending() ? "删除申请待审批，办理已暂停" : switch (status) {
            case PENDING_DISPATCH -> row.dispatchAddressConfirmed() ? "管理端可核对网点并办理派单" : "先核对服务区域和接车地址";
            case PENDING_ACCEPTANCE -> "等待本次派单网点接单";
            case PENDING_ARRIVAL -> "等待网点确认到店";
            case ARRIVAL_EXCEPTION -> "请管理端处理到店异常";
            case ARRIVED, WAITING_QUOTE -> row.arrivalPhotoPresent() ? "等待网点提交报价" : "先由本次派单网点上传有效到店照";
            case QUOTE_REVIEWING -> row.scenePhotoPresent() ? "等待客服审核并形成正式报价" : "先补齐现场物损照片";
            case WAITING_INSURER_ASSESSMENT, WAITING_REPAIR_AUTHORIZATION ->
                row.assessed() == null ? "请客服核对当前正式报价并录入有效核损" :
                !row.insurerConfirmed() ? "等待记录保险公司确认" :
                !row.serviceConfirmed() ? "等待客服最终确认" : "请按工单当前任务核对维修授权状态";
            case REPAIRING -> row.completionPhotoPresent() ? "按当前任务核对维修进度或提交完工" : "提交完工前，本网点需上传本次派单有效完工照";
            case WAITING_OWNER_CONFIRMATION -> "等待车主实际收车并确认，或按平台流程办理例外";
            case WAITING_INSURER_PAYMENT -> "请客服核对实际到账并完成回款确认";
            case WAITING_SHOP_SETTLEMENT -> "请客服核对实际付款并完成网点结算";
            default -> "请在工单详情核对当前状态";
        };
        return new CaseDetails(number, row.status(), next, money(row.rawTotal()), money(row.formalTotal()),
            row.insurerConfirmed(), row.serviceConfirmed(), row.authorized(), money(row.assessed()), money(row.receiveTarget()),
            money(row.payTarget()), money(row.received()), money(row.paid()), balance(row.receiveTarget(), row.received()),
            balance(row.payTarget(), row.paid()), money(row.profit()));
    }

    private static String balance(BigDecimal target, BigDecimal net) {
        return target == null ? null : money(target.subtract(net).max(BigDecimal.ZERO));
    }

    static String money(BigDecimal value) { return value == null ? null : value.setScale(2).toPlainString(); }

    record Period(LocalDate from, LocalDate to, LocalDate compareFrom, LocalDate compareTo) {}
    record RecentReport(int requested, String order, String window, List<SelectedOrder> orders, long completed,
                        int accounted, String insurance, String payable, String profit, String margin, String received, String paid) {
        String headline() {
            return "请求最近 " + requested + " 单，实际取到 " + orders.size() + " 单（" + order + "，" + window + "）。";
        }

        String revenueSummary() {
            String accrual = accounted == 0 ? "这批工单尚无已完工核算的保险应收金额"
                : "其中 " + accounted + " 单已完工核算，保险应收合计 " + insurance + " 元";
            return accrual + "。这 " + orders.size() + " 单的实际净到账合计 " + received + " 元（已排除冲销）。";
        }

        String display() {
            var details = new java.util.StringJoiner("\n");
            details.add("完工 " + completed + " 单，其中已核算 " + accounted + " 单，待核算 " + (completed - accounted) + " 单。");
            details.add(accounted == 0 ? "这批工单暂无完工核算金额。"
                : "已核算保险应收 " + insurance + " 元 · 网点应付 " + payable + " 元 · 平台盈利 " + profit + " 元"
                    + (margin == null ? "" : " · 毛利率 " + margin + "%"));
            details.add("这批工单净实收 " + received + " 元 · 净实付 " + paid + " 元（排除已冲销记录）");
            for (var item : orders) details.add(item.display());
            return details.toString();
        }
    }
    record SelectedOrder(String businessNo, String status, String time, String formalQuote, String assessment, String insurance, String profit,
                         String received, String paid) {
        String display() {
            return businessNo + " · " + time + " · " + status
                + "\n正式报价 " + amount(formalQuote) + " · 已确认核损 " + amount(assessment) + " · 已核算保险应收 " + amount(insurance)
                + " · 完工核算盈利 " + amount(profit)
                + " · 净实收 " + received + " 元 · 净实付 " + paid + " 元";
        }

        private static String amount(String value) { return value == null ? "暂无" : value + " 元"; }
    }
    record Performance(Metrics current, Metrics comparison) {
        String display() {
            var profitChange = new BigDecimal(current.profit()).subtract(new BigDecimal(comparison.profit()));
            String change = "相较对比期：完工量" + change(BigDecimal.valueOf(current.completed() - comparison.completed()), " 单")
                + "，平台盈利" + change(profitChange, " 元") + "。";
            if (current.accounted() > 0 && comparison.accounted() > 0) {
                var currentAverage = average(current);
                var previousAverage = average(comparison);
                change += "每张已核算工单平均盈利 " + money(currentAverage) + " 元，"
                    + "较对比期" + change(currentAverage.subtract(previousAverage), " 元") + "。";
            }
            return current.display() + "\n对比期 " + comparison.display() + "\n" + change;
        }

        private static BigDecimal average(Metrics metrics) {
            return new BigDecimal(metrics.profit()).divide(BigDecimal.valueOf(metrics.accounted()), 2, java.math.RoundingMode.HALF_UP);
        }

        private static String change(BigDecimal delta, String unit) {
            return delta.signum() == 0 ? "持平" : (delta.signum() > 0 ? "增加 " : "减少 ") + delta.abs().toPlainString() + unit;
        }
    }
    record Metrics(String from, String to, long completed, long accounted, long pending, String insurance,
                   String payable, String profit, String margin) {
        String display() {
            return from + " 至 " + to + "：完工 " + completed + " 单，已核算 " + accounted + " 单，待核算 " + pending
                + " 单；保险应收 " + insurance + " 元，网点应付 " + payable + " 元，平台盈利 " + profit
                + " 元，毛利率 " + (margin == null ? "未计算" : margin + "%") + "。";
        }
    }
    record CashComparison(Cash current, Cash comparison) {}
    record Cash(String from, String to, String received, String paid, String difference) {
        String display() {
            return from + " 至 " + to + "：净实收 " + received + " 元，净实付 " + paid + " 元，差额 " + difference + " 元。";
        }
    }
    record CaseDetails(String businessNo, String status, String next, String rawQuoteCny, String formalQuoteCny,
                       boolean insurerConfirmed, boolean serviceConfirmed, boolean authorized, String assessmentCny,
                       String receiveTargetCny, String payTargetCny, String receivedCny, String paidCny,
                       String receivableBalanceCny, String payableBalanceCny, String confirmedProfitCny) {
        String display() {
            return businessNo + "\n" + next
                + "\n网点原始报价 " + amount(rawQuoteCny) + " · 正式报价 " + amount(formalQuoteCny) + " · 核损 " + amount(assessmentCny)
                + "\n保险确认 " + (insurerConfirmed ? "已完成" : "未完成") + " · 客服确认 " + (serviceConfirmed ? "已完成" : "未完成")
                + "\n保险应收 " + amount(receiveTargetCny) + " · 网点应付 " + amount(payTargetCny)
                + "\n净实收 " + amount(receivedCny) + " · 净实付 " + amount(paidCny)
                + "\n待收 " + amount(receivableBalanceCny) + " · 待付 " + amount(payableBalanceCny)
                + "\n已确认盈利 " + amount(confirmedProfitCny);
        }

        private static String amount(String value) { return value == null ? "未记录" : value + " 元"; }
    }
}
