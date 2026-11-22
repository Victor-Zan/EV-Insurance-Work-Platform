package com.evinsurance.platform.assistant.infrastructure;

import static org.assertj.core.api.Assertions.*;

import com.evinsurance.platform.foundation.api.ApiException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class AssistantModelsTest {
    private final ObjectMapper json = new ObjectMapper();
    private final String base = "https://open.bigmodel.cn/api/paas/v4";

    @Test void catalogExposesOnlyNamesAndConfigurationAndRestrictsSelectionToServerChoices() {
        var main = new BigModelClient(json, "synthetic-zhipu-key", "glm-4-flash-250414", base);
        var models = new AssistantModels(main, json, "synthetic-zhipu-key", base, "");
        assertThat(models.options()).hasSize(4);
        assertThat(models.options().stream().filter(AssistantModels.Option::enabled)).hasSize(2);
        assertThat(models.select(null)).isSameAs(main);
        assertThat(models.select("glm-4.7-flash").modelName()).isEqualTo("glm-4.7-flash");
        assertThat(json.writeValueAsString(models.options())).doesNotContain("synthetic-zhipu-key", "https://");
        assertThatThrownBy(() -> models.select("https://other.example/api")).isInstanceOf(ApiException.class)
            .hasMessageContaining("不在可用列表");
        assertThatThrownBy(() -> models.select("Qwen/Qwen2.5-7B-Instruct")).isInstanceOf(ApiException.class)
            .hasMessageContaining("尚未配置");
        assertThat(new BigModelClient(json, "fake", "glm-4.7-flash", "https://other.example").available()).isFalse();
    }

    @Test void domesticBackupWorksAsDefaultWithoutZhipuConfigurationAndKeepsItsOwnKeyAndProtocol() {
        var models = new AssistantModels(new BigModelClient(json, "", "glm-4.7-flash", base), json, "", base, "synthetic-silicon-key");
        assertThat(models.defaultId()).isEqualTo("XingChenAGI/Xing4.0-29B");
        var client = models.select(null);
        var sent = new ArrayList<JsonNode>();
        intercept(client, "api.siliconflow.cn", "synthetic-silicon-key", sent,
            "{\"tool_calls\":[{\"id\":\"lookup_one\",\"function\":{\"name\":\"lookup_platform\",\"arguments\":\"{\\\"question\\\":\\\"如何提交网点报价\\\"}\"}}]}",
            "{\"content\":\"先上传到店照\"}");
        var history = List.of(new BigModelClient.Turn("上一个模型是谁", "glm-4-flash-250414"));
        var first = client.start("报价怎么提交", history);
        var answer = client.finish("报价怎么提交", history, first.lookup(), Map.of("query", "GUIDE", "source", "合成操作指南"));
        assertThat(answer).isEqualTo("先上传到店照");
        assertThat(sent).hasSize(2);
        assertThat(sent.getFirst().path("model").asText()).isEqualTo("XingChenAGI/Xing4.0-29B");
        assertThat(sent.getFirst().path("enable_thinking").asBoolean(true)).isFalse();
        assertThat(sent.getFirst().has("thinking")).isFalse();
        assertThat(sent.getFirst().path("messages").path(0).path("content").asText()).contains("硅基流动", "XingChenAGI/Xing4.0-29B");
        assertThat(sent.getFirst().path("messages").path(1).path("content").asText()).isEqualTo("上一个模型是谁");
        var last = sent.getLast().path("messages");
        assertThat(last.path(last.size() - 1).path("role").asText()).isEqualTo("tool");
        assertThat(last.path(last.size() - 1).path("tool_call_id").asText()).isEqualTo("lookup_one");
        assertThat(sent.getLast().has("tools")).isFalse();
    }

    @Test void selectedClientsDoNotChangeOtherConversationsOrSendProviderSpecificFieldsToQwen() {
        var main = new BigModelClient(json, "synthetic-zhipu-key", "glm-4-flash-250414", base);
        var models = new AssistantModels(main, json, "synthetic-zhipu-key", base, "synthetic-silicon-key");
        var backup = models.select("Qwen/Qwen2.5-7B-Instruct");
        var primaryRequests = new ArrayList<JsonNode>();
        var backupRequests = new ArrayList<JsonNode>();
        intercept(main, "open.bigmodel.cn", "synthetic-zhipu-key", primaryRequests, "{\"content\":\"主模型回答\"}");
        intercept(backup, "api.siliconflow.cn", "synthetic-silicon-key", backupRequests, "{\"content\":\"备用回答\"}");
        assertThat(backup.start("接着说", List.of(new BigModelClient.Turn("第一问", "上一条回答"))).answer()).isEqualTo("备用回答");
        assertThat(models.select(null).start("你好", List.of()).answer()).isEqualTo("主模型回答");
        assertThat(backupRequests.getFirst().has("thinking")).isFalse();
        assertThat(backupRequests.getFirst().has("enable_thinking")).isFalse();
        assertThat(primaryRequests.getFirst().path("model").asText()).isEqualTo("glm-4-flash-250414");
    }

    private void intercept(BigModelClient client, String host, String key, List<JsonNode> sent, String... replies) {
        ReflectionTestUtils.setField(client, "http", new OkHttpClient.Builder().addInterceptor(chain -> {
            assertThat(chain.request().url().host()).isEqualTo(host);
            assertThat(chain.request().header("Authorization")).isEqualTo("Bearer " + key);
            var body = new okio.Buffer();
            chain.request().body().writeTo(body);
            sent.add(json.readTree(body.readUtf8()));
            String response = "{\"choices\":[{\"message\":" + replies[sent.size() - 1] + "}]}";
            return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(ResponseBody.create(response, MediaType.get("application/json"))).build();
        }).build());
    }
}
