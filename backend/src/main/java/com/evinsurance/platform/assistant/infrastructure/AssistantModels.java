package com.evinsurance.platform.assistant.infrastructure;

import com.evinsurance.platform.foundation.api.ApiException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class AssistantModels {
    private final Map<String, BigModelClient> clients = new LinkedHashMap<>();

    public AssistantModels(BigModelClient configured, ObjectMapper json,
                           @Value("${BIGMODEL_API_KEY:}") String key,
                           @Value("${BIGMODEL_BASE_URL:https://open.bigmodel.cn/api/paas/v4}") String base,
                           @Value("${SILICONFLOW_API_KEY:}") String siliconKey) {
        clients.put("configured", configured);
        for (String model : List.of("glm-4.7-flash", "glm-4-flash-250414")) {
            clients.put(model, new BigModelClient(json, key, model, base));
        }
        for (String model : List.of("XingChenAGI/Xing4.0-29B", "Qwen/Qwen2.5-7B-Instruct")) {
            clients.put(model, BigModelClient.siliconflow(json, siliconKey, model));
        }
    }

    public List<Option> options() {
        return clients.entrySet().stream().filter(entry -> !entry.getKey().equals(clients.get("configured").modelName()))
            .map(entry -> new Option(entry.getKey(),
            entry.getValue().modelName() == null ? entry.getKey().equals("configured") ? "默认模型" : entry.getKey()
                : entry.getValue().modelName(),
            entry.getKey().contains("/") ? "硅基流动" : "智谱", entry.getValue().available())).toList();
    }

    public String defaultId() {
        return clients.entrySet().stream().filter(entry -> entry.getValue().available())
            .map(Map.Entry::getKey).findFirst().orElse("configured");
    }

    public BigModelClient select(String id) {
        var client = clients.get(id == null || id.isBlank() ? defaultId() : id);
        if (client == null) throw new ApiException(HttpStatus.BAD_REQUEST, "ASSISTANT_MODEL_UNKNOWN", "所选模型不在可用列表，请刷新模型列表");
        if (!client.available()) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "ASSISTANT_DISABLED", "所选模型尚未配置，请选择已配置的模型");
        return client;
    }

    public record Option(String id, String name, String provider, boolean enabled) {}
}
