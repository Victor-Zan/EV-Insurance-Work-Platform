package com.evinsurance.platform.assistant.application;

import java.time.LocalDate;
import java.util.regex.Pattern;

record RecentOrderScope(int limit, boolean completedOnly, LocalDate from, LocalDate to, String clarification) {
    private static final String NUMERAL = "[0-9零一二两三四五六七八九十百千万]+";
    private static final String COMPLETED = "(?:已完成|完成|已完工|完工|已结案|结案)";
    private static final Pattern ORDER_LIMIT = Pattern.compile(
        "(?:最近|最新|近|最后)(?:的)?(?:" + COMPLETED + "(?:的)?)?(" + NUMERAL + ")"
            + "(?:张|笔|条|个)?(?:" + COMPLETED + "(?:的)?)?(?:工单|案件|订单|单)");
    private static final Pattern SOME_ORDERS = Pattern.compile(
        "(?:" + NUMERAL + "|几)(?:张|笔|条|个)?(?:" + COMPLETED + "(?:的)?)?(?:工单|案件|订单|单)");
    private static final Pattern FINISHED = Pattern.compile(COMPLETED);
    private static final Pattern CALENDAR = Pattern.compile(
        "今天|今日|本月|这个月|当月|上月|上个月|昨天|昨日|[上下本这]周|季度|半年|今年|去年|[0-9]{4}(?:年|-)[0-9]{1,2}月?|"
            + "(?:过去|最近|近)" + NUMERAL + "(?:个)?[天日月周]|[0-9]+日|截至");
    private static final Pattern EXTRA_SCOPE = Pattern.compile("(?:网点|客服|区域|保险公司|车主)(?:负责|经办|下)?的(?:最近|最新|近|最后)|"
        + "(?:网点|客服|区域|保险公司|车主)(?:负责|经办|下)?的"
        + "(?:工单|案件|订单)|待派单|待结算|已取消|维修中");

    static boolean hasExplicitRange(String input) {
        return ORDER_LIMIT.matcher(compact(input)).find();
    }

    private static String compact(String input) { return input.replaceAll("[\\s\\u3000]+", ""); }

    static RecentOrderScope resolve(String input, LocalDate today) {
        String text = compact(input);
        var match = ORDER_LIMIT.matcher(text);
        if (!match.find()) {
            return SOME_ORDERS.matcher(text).find()
                ? ask("请说明要查最近几张正式工单，还是最近几张已完工单。例如「分析最近五单的经营情况」。")
                : null;
        }
        int limit = number(match.group(1));
        boolean completedOnly = FINISHED.matcher(match.group()).find();
        if (match.find() || EXTRA_SCOPE.matcher(text).find() || AssistantQuestion.singleOrderNumber(text) != null) {
            return ask("这次提问包含多个工单范围或额外筛选条件。请先选一个范围，例如「最近五单」或「最近完工的五单」。");
        }
        if (limit < 1 || limit > 20) return ask("每次可分析最近 1 至 20 张工单，请调整单量。");
        var date = CALENDAR.matcher(text);
        if (!date.find()) return new RecentOrderScope(limit, completedOnly, null, null, null);
        String requestedDate = date.group();
        if (date.find()) return ask("请为这批工单指定一个日期范围，例如「分析本月最近五单」。");
        if (!requestedDate.matches("今天|今日|本月|这个月|当月|上月|上个月|[0-9]{4}(?:年|-)[0-9]{1,2}月?")) {
            return ask("这批工单的日期范围暂无法确认，请指定今天、本月、上月或具体年月。");
        }
        var period = AssistantBusiness.period(text, today);
        if (period == null) return ask("这批工单的日期范围暂无法确认，请指定今天、本月、上月或具体年月。");
        return new RecentOrderScope(limit, completedOnly, period.from(), period.to(), null);
    }

    private static RecentOrderScope ask(String text) { return new RecentOrderScope(0, false, null, null, text); }

    private static int number(String value) {
        if (value.matches("[0-9]{1,9}")) return Integer.parseInt(value);
        if (value.length() == 1 && !value.equals("十")) return digit(value.charAt(0));
        if (value.equals("十")) return 10;
        if (value.matches("十[一二三四五六七八九]")) return 10 + digit(value.charAt(1));
        return value.equals("二十") ? 20 : 0;
    }

    private static int digit(char value) {
        return value == '两' ? 2 : "零一二三四五六七八九".indexOf(value);
    }
}
