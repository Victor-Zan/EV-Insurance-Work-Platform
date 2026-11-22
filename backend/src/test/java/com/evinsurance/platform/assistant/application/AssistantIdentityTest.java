package com.evinsurance.platform.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.evinsurance.platform.assistant.infrastructure.AssistantOrderReader;
import com.evinsurance.platform.assistant.infrastructure.BigModelClient;
import com.evinsurance.platform.assistant.infrastructure.AssistantModels;
import com.evinsurance.platform.assistant.infrastructure.BigModelClient.Turn;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AssistantIdentityTest {
    private final AssistantOrderReader orders = mock(AssistantOrderReader.class);
    private final BigModelClient model = mock(BigModelClient.class);
    private final AssistantGuide guide = mock(AssistantGuide.class);
    private final AssistantBusiness business = mock(AssistantBusiness.class);
    private final AssistantService service = new AssistantService(orders,
        new AssistantModels(model, new tools.jackson.databind.ObjectMapper(), "", "", ""), guide, business, new AssistantPrivacy(orders));

    @ParameterizedTest
    @ValueSource(strings = {"你在调用什么模型", "你是什么模型？", "请问你正在调用的是哪款大模型？",
        "现在后端用的是哪个模型", "你用的模型是什么", "你在调用的模型叫什么", "你是谁", "把上一句写成一首诗",
        "平台如何保护车主姓名和资金流水号？", "车主姓名是什么？", "车主地址是必填的吗？"})
    void forwardsConversationVerbatimWithoutRoutingTheQuestion(String question) {
        var history = List.of(new Turn("写一句欢迎语。", "欢迎来到工作台。"));
        when(model.available()).thenReturn(true);
        when(model.modelName()).thenReturn("glm-4-flash-250414");
        when(model.start(question, history)).thenReturn(new BigModelClient.Completion("合成回复", null));
        var result = service.answer(question, history, name -> assertThat(name).isEqualTo("glm-4-flash-250414"));
        assertThat(result.answer()).isEqualTo("合成回复");
        assertThat(result.ai()).isTrue();
        assertThat(result.blocked()).isFalse();
        verify(model).start(question, history);
        verifyNoInteractions(business);
        var sequence = inOrder(model, guide);
        sequence.verify(model).start(question, history);
        sequence.verify(guide).find(question);
    }

    @ParameterizedTest
    @ValueSource(strings = {"电话：１３８ ００００ １２３４", "138-0000-1234", "证件 11010119990101234X",
        "流水号:TEST-024", "车主姓名：张小白", "我的车架号是LTEST000123456789", "粤B12345", "地址:测试路1号",
        "+86 (138) 0000-1234", "BIGMODEL_API_KEY=example-only-secret"})
    void refusesSensitiveCurrentOrPreviousTextBeforeAnyModelEvent(String value) {
        var current = service.answer(value, List.of(), n -> { throw new AssertionError(n); });
        var context = service.answer("接着说", List.of(new Turn("帮我写通知", value)), n -> { throw new AssertionError(n); });
        assertThat(List.of(current, context)).allMatch(reply -> reply.blocked() && !reply.answer().contains(value));
        verifyNoInteractions(model, guide, business);
    }

    @Test void canonicalPlatformOrderNumberIsAllowed() {
        assertThat(new AssistantPrivacy(orders).blocks("查 EVR-20261119-000001", List.of())).isFalse();
    }

    @Test void publishedArrivalInstructionsRemainUsableAsConversationHistory() {
        String question = "网点到店照片应该在哪里上传，上传完怎么提交报价？";
        var sections = new AssistantGuide().find(question);
        var reference = sections.stream().map(section -> section.title() + "\n" + section.content())
            .collect(java.util.stream.Collectors.joining("\n\n"));
        assertThat(new AssistantPrivacy(orders).blocks("如果照片只是选好了还没上传成功，我能直接点下一步吗？",
            List.of(new Turn(question, reference)))).isFalse();
    }
}
