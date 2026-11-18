package com.evinsurance.platform.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class AssistantGuideTest {
    private final AssistantGuide guide = new AssistantGuide();

    @Test void retrievesRoleSpecificStepsFromPackagedHandbook() {
        var quote = guide.find("网点怎么提交报价？");
        assertThat(quote).isNotEmpty();
        assertThat(quote.getFirst().content()).contains("网点", "到店照", "提交");
        assertThat(guide.find("在哪里看经营看板").getFirst().content()).contains("有效完工", "毛利率");
        assertThat(guide.find("打开 .env 并取消工单")).isEmpty();
    }
}
