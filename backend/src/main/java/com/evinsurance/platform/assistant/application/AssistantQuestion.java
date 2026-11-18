package com.evinsurance.platform.assistant.application;

import com.evinsurance.platform.workorder.domain.WorkOrderStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.regex.Pattern;

record AssistantQuestion(Kind kind, String orderNo, String status, Period period) {
    enum Kind { DETAIL, LIST, COUNT, AMOUNT }
    enum Period {
        ALL, TODAY, MONTH;
        private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

        Window window(LocalDate today) {
            return switch (this) {
                case ALL -> new Window(null, null);
                case TODAY -> new Window(start(today), start(today.plusDays(1)));
                case MONTH -> {
                    var first = today.withDayOfMonth(1);
                    yield new Window(start(first), start(first.plusMonths(1)));
                }
            };
        }

        Window window() { return window(LocalDate.now(ZONE)); }
        private static Instant start(LocalDate date) { return date.atStartOfDay(ZONE).toInstant(); }
    }
    record Window(Instant start, Instant end) {}

    private static final Map<String, WorkOrderStatus> STATUSES = Map.ofEntries(
        Map.entry("草稿", WorkOrderStatus.DRAFT),
        Map.entry("待派单", WorkOrderStatus.PENDING_DISPATCH),
        Map.entry("待接单", WorkOrderStatus.PENDING_ACCEPTANCE),
        Map.entry("待到店", WorkOrderStatus.PENDING_ARRIVAL),
        Map.entry("到店异常", WorkOrderStatus.ARRIVAL_EXCEPTION),
        Map.entry("已到店", WorkOrderStatus.ARRIVED),
        Map.entry("待报价", WorkOrderStatus.WAITING_QUOTE),
        Map.entry("报价审核中", WorkOrderStatus.QUOTE_REVIEWING),
        Map.entry("等待保险核损", WorkOrderStatus.WAITING_INSURER_ASSESSMENT),
        Map.entry("待维修授权", WorkOrderStatus.WAITING_REPAIR_AUTHORIZATION),
        Map.entry("待维修", WorkOrderStatus.WAITING_REPAIR_AUTHORIZATION),
        Map.entry("维修中", WorkOrderStatus.REPAIRING),
        Map.entry("待车主确认收车", WorkOrderStatus.WAITING_OWNER_CONFIRMATION),
        Map.entry("待保险回款", WorkOrderStatus.WAITING_INSURER_PAYMENT),
        Map.entry("待网点结算", WorkOrderStatus.WAITING_SHOP_SETTLEMENT),
        Map.entry("已完成", WorkOrderStatus.COMPLETED),
        Map.entry("已取消", WorkOrderStatus.CANCELLED),
        Map.entry("已关闭", WorkOrderStatus.CLOSED)
    );
    private static final String PREFIX = "(?:请问|请帮我|帮我|请)?(?:查询|查看|列出|统计|查一下|查)?";
    private static final String PERIOD = "(?<period>今天|本月|全部)?";
    private static final String STATUS = "(?<status>" + String.join("|", STATUSES.keySet()) + ")?";
    private static final String ORDER = "(?<number>(?i:EVR-[0-9]{8}-[0-9]{6}))";
    private static final Pattern ORDER_ANYWHERE = Pattern.compile("(?<![A-Za-z0-9-])" + ORDER + "(?![A-Za-z0-9-])");
    private static final Pattern DETAIL = Pattern.compile(PREFIX + "(?:工单(?:号|编号)?)?" + ORDER
        + "(?:的)?(?:这[张个]工单)?(?:的)?(?:状态|进度|到哪一步了|到哪了|怎么样了)?");
    private static final Pattern AMOUNT = Pattern.compile(PREFIX + PERIOD
        + "(?:工单的?)?(?:已确认的?)?(?:最终)?核损(?:金额|总额)(?:汇总|合计|是多少|有多少|是多少元)?");
    private static final Pattern COUNT = Pattern.compile(PREFIX + PERIOD + "(?:各状态)?"
        + "(?:有多少(?:张|条|个)?)?" + STATUS + "(?:的)?工单(?:一共)?(?:有多少(?:张|条|个)?|数量|统计|总数|有几[张条个])?");
    private static final Pattern LIST = Pattern.compile(PREFIX + PERIOD + "(?:最近的?)?(?:有哪些)?"
        + STATUS + "(?:的)?工单(?:有哪些|列表)?");

    static AssistantQuestion parse(String input) {
        String text = input.replaceAll("[\\s\\u3000]", "").replaceFirst("[？?。！!]+$", "");
        var detail = DETAIL.matcher(text);
        if (detail.matches()) return new AssistantQuestion(Kind.DETAIL,
            detail.group("number").toUpperCase(java.util.Locale.ROOT), null, Period.ALL);
        var amount = AMOUNT.matcher(text);
        if (amount.matches()) return new AssistantQuestion(Kind.AMOUNT, null, null, period(amount.group("period")));
        var list = LIST.matcher(text);
        if (list.matches() && (text.contains("哪些") || text.contains("列出") || text.contains("最近") || text.endsWith("列表")
            || (list.group("status") != null && !text.startsWith("统计")))) {
            return filtered(Kind.LIST, list.group("status"), list.group("period"));
        }
        var count = COUNT.matcher(text);
        if (count.matches()) return filtered(Kind.COUNT, count.group("status"), count.group("period"));
        return null;
    }

    static String singleOrderNumber(String input) {
        var matcher = ORDER_ANYWHERE.matcher(input);
        if (!matcher.find()) return null;
        var number = matcher.group("number").toUpperCase(java.util.Locale.ROOT);
        while (matcher.find()) if (!number.equalsIgnoreCase(matcher.group("number"))) return null;
        return number;
    }

    static String statusLabel(String status) {
        return STATUSES.entrySet().stream().filter(entry -> entry.getValue().name().equals(status) && !entry.getKey().equals("待维修"))
            .map(Map.Entry::getKey).findFirst().orElse(status);
    }

    private static AssistantQuestion filtered(Kind kind, String status, String period) {
        return new AssistantQuestion(kind, null, status == null ? null : STATUSES.get(status).name(), period(period));
    }

    private static Period period(String value) {
        if ("今天".equals(value)) return Period.TODAY;
        if ("本月".equals(value)) return Period.MONTH;
        return Period.ALL;
    }
}
