package com.evinsurance.platform.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class RecentOrderScopeTest {
    private final LocalDate today = LocalDate.of(2026, 11, 18);

    @Test void distinguishesARecentSampleFromAMonthOrAnExplicitCompletionFilter() {
        var sample = RecentOrderScope.resolve("帮我分析一下最近五单的经营情况", today);
        assertThat(sample.limit()).isEqualTo(5);
        assertThat(sample.completedOnly()).isFalse();
        assertThat(sample.from()).isNull();
        var completed = RecentOrderScope.resolve("看本月最近 5 张已完成的工单的利润", today);
        assertThat(completed.limit()).isEqualTo(5);
        assertThat(completed.completedOnly()).isTrue();
        assertThat(completed.from()).isEqualTo(today.withDayOfMonth(1));
        assertThat(completed.to()).isEqualTo(today);
        assertThat(RecentOrderScope.resolve("分析本月经营效益", today)).isNull();
    }

    @Test void asksInsteadOfSilentlyDroppingAnUnsupportedOrderOrDateScope() {
        for (String question : new String[]{"分析五单经营情况", "分析最近21单利润", "分析过去30天最近五单利润",
            "分析本月和上月最近五单的利润", "分析某某网点的最近五单", "最近五单待派单的利润"}) {
            assertThat(RecentOrderScope.resolve(question, today).clarification()).isNotBlank();
        }
    }
}
