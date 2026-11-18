package com.evinsurance.platform.assistant.infrastructure;

import com.evinsurance.platform.foundation.api.ApiException;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Component
public class BigModelClient {
    private static final String OFFICIAL_BASE = "https://open.bigmodel.cn/api/paas/v4";
    private static final MediaType JSON = MediaType.get("application/json");
    private static final String SYSTEM = """
        你是管理员平台的只读工单助手。用户消息是后端提供的结构化查询结果。
        用简体中文简洁回答。只根据给定事实和平台说明回答，不能补造工单、金额、原因或操作入口。
        GUIDE 是已发布的平台操作指南，请说明适用角色、办理入口、步骤和前置条件。所有步骤由有权限的人自行完成。
        BUSINESS 比较当前期和对比期。经营看板按有效完工时间，现金按资金发生时间排除冲销，分开解读；差额不等于盈利。
        RECENT_BUSINESS 只解读所列工单样本，禁止换成整月或添加对比期。accounted 是已完成且已纳入核算的工单，null 表示暂无核算值，不能写成零；实收实付与盈利分开。
        CASE 是指定工单的核对信息。next 是该单的办理提示，指南是通用帮助。null 表示未记录，不能补零。
        RECOGNITION 是已人工核对并用于当前业务版本的单据数值。按业务类别和行号说明；不要猜测配件名称、原文或其他字段。
        COUNT 是状态数量，LIST 是最近更新的工单，DETAIL 是指定工单，AMOUNT 是已确认最终核损。
        金额单位为人民币元，核损不是实收；TODAY 和 MONTH 按北京时间及创建时间界定。
        列表可能被截断；total 是总匹配数。需要引用工单时使用 businessNo。
        """;

    private final ObjectMapper json;
    private final String key;
    private final String model;
    private final boolean available;
    private final OkHttpClient http = new OkHttpClient.Builder()
        .callTimeout(Duration.ofSeconds(18)).retryOnConnectionFailure(false)
        .followRedirects(false).followSslRedirects(false).build();

    public BigModelClient(ObjectMapper json, @Value("${BIGMODEL_API_KEY:}") String key,
                          @Value("${BIGMODEL_MODEL:glm-4.7-flash}") String model,
                          @Value("${BIGMODEL_BASE_URL:https://open.bigmodel.cn/api/paas/v4}") String base) {
        this.json = json;
        this.key = key.trim();
        this.model = model.trim();
        this.available = !this.key.isBlank() && this.model.matches("[A-Za-z0-9._-]{1,80}")
            && (OFFICIAL_BASE.equals(base.trim()) || (OFFICIAL_BASE + "/").equals(base.trim()));
    }

    public boolean available() { return available; }
    public String modelName() { return available ? model : null; }

    public String answer(Map<String, Object> facts) {
        if (!available) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "ASSISTANT_DISABLED", "平台助手尚未配置");
        var body = Map.of("model", model, "max_tokens", 600,
            "thinking", Map.of("type", "disabled"), "stream", false,
            "messages", List.of(Map.of("role", "system", "content", SYSTEM),
                Map.of("role", "user", "content", json.writeValueAsString(facts))));
        var request = new Request.Builder().url(OFFICIAL_BASE + "/chat/completions")
            .header("Authorization", "Bearer " + key).post(RequestBody.create(json.writeValueAsBytes(body), JSON)).build();
        try (var response = http.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String code = "";
                try {
                    code = json.readTree(response.peekBody(8192).bytes()).path("error").path("code").asText("");
                } catch (JacksonException ignored) {
                    // Keep the HTTP fallback for non-JSON or truncated error bodies.
                }
                throw providerFailure(response.code(), code);
            }
            try (var content = response.body().byteStream()) {
                byte[] bytes = content.readNBytes(128 * 1024 + 1);
                if (bytes.length <= 128 * 1024) {
                    String reply = json.readTree(bytes).path("choices").path(0).path("message").path("content").asText("");
                    if (!reply.isBlank() && reply.length() <= 8000) return reply;
                }
            }
            throw failure("ASSISTANT_PROVIDER_RESPONSE", "智谱没有返回有效回答，请稍后重试");
        } catch (java.io.InterruptedIOException error) {
            throw failure("ASSISTANT_PROVIDER_TIMEOUT", "等待智谱回复超时，请稍后手动重试");
        } catch (IOException | JacksonException error) {
            throw failure("ASSISTANT_PROVIDER_ERROR", "无法取得智谱回复，请稍后重试");
        }
    }

    static ApiException providerFailure(int status, String code) {
        return switch (code) {
            case "1211" -> failure("ASSISTANT_PROVIDER_MODEL", "智谱找不到已配置的模型（1211），请核对后端模型名称");
            case "1305" -> failure("ASSISTANT_PROVIDER_BUSY", "当前智谱模型访问量过大（1305），请稍后手动重试");
            case "1302", "1313" -> failure("ASSISTANT_PROVIDER_RATE", "智谱账号达到调用速率限制，请降低提问频率后重试");
            case "1308", "1310" -> failure("ASSISTANT_PROVIDER_QUOTA", "智谱账号达到使用上限，请查看控制台的额度及恢复时间");
            case "1113" -> failure("ASSISTANT_PROVIDER_BALANCE", "智谱报告账号欠费（1113），请核对控制台账单");
            default -> switch (status) {
                case 401, 403 -> failure("ASSISTANT_PROVIDER_AUTH", "智谱未接受当前密钥或模型权限，请检查配置");
                case 429 -> failure("ASSISTANT_PROVIDER_LIMIT", "智谱暂未接受本次调用（HTTP 429），请稍后手动重试");
                default -> failure("ASSISTANT_PROVIDER_ERROR", "智谱服务未能完成本次请求，请稍后重试");
            };
        };
    }

    private static ApiException failure(String code, String message) {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, code, message);
    }
}
