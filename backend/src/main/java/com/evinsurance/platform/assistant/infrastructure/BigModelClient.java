package com.evinsurance.platform.assistant.infrastructure;

import com.evinsurance.platform.foundation.api.ApiException;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.JsonNode;
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
        你是电动车保险维修平台管理员的只读对话助手，支持常识、写作和连续追问。一般问题直接回答。
        涉及平台入口、规则、经营、工单、已核对单据时调用 lookup_platform 获取资料。
        对同一批工单的解释和比较优先沿用前文已有明细。要求更新或缺少必要数据时再查询，保留前文明确的范围。
        不得凭空宣称查询成功或完成操作。无法读取原始聊天、照片、文件原件、车主隐私或资金流水号。
        用简体中文简洁回答。平台问题以工具提供的事实和平台说明为准，不能补造工单、金额、原因或操作入口。
        GUIDE 是平台操作手册。先给出用户所问动作的适用角色、准确菜单或按钮，再写必要的步骤和前置条件。按问题引用相关内容。
        有操作卡点时，说明在哪一页核对、由谁处理。资料只给出了通用规则时，不把它写成某张工单已发生的事实。
        BUSINESS 比较当前期和对比期。经营看板按有效完工时间，现金按资金发生时间排除冲销，分开解读；差额不等于盈利。
        RECENT_BUSINESS 只解读所列工单样本，禁止换成整月或添加对比期。accounted 是已完成且已纳入核算的工单，null 表示暂无核算值，不能写成零；实收实付与盈利分开。
        问样本营收、收入或营业额时，insurance 是已完工核算的保险应收，received 是这批工单实际净到账。两者分列，禁止相加或用报价、核损、盈利代替。
        CASE 是指定工单的核对信息。next 是该单的办理提示，指南是通用帮助。null 表示未记录，不能补零。
        RECOGNITION 是已人工核对并用于当前业务版本的单据数值。按业务类别和行号说明；不要猜测配件名称、原文或其他字段。
        COUNT 是状态数量，LIST 是最近更新的工单，DETAIL 是指定工单，AMOUNT 是已确认最终核损。
        金额单位为人民币元，核损不是实收；TODAY 和 MONTH 按北京时间及创建时间界定。
        列表可能被截断；total 是总匹配数。需要引用工单时使用 businessNo。
        """;

    private final ObjectMapper json;
    private final String key;
    private final String model;
    private final String base;
    private final String provider;
    private final boolean available;
    private final OkHttpClient http = new OkHttpClient.Builder()
        .callTimeout(Duration.ofSeconds(30)).retryOnConnectionFailure(false)
        .followRedirects(false).followSslRedirects(false).build();

    @org.springframework.beans.factory.annotation.Autowired
    public BigModelClient(ObjectMapper json, @Value("${BIGMODEL_API_KEY:}") String key,
                          @Value("${BIGMODEL_MODEL:glm-4.7-flash}") String model,
                          @Value("${BIGMODEL_BASE_URL:https://open.bigmodel.cn/api/paas/v4}") String base) {
        this(json, key, model, base, "智谱");
    }

    private BigModelClient(ObjectMapper json, String key, String model, String base, String provider) {
        this.json = json;
        this.key = key.trim();
        this.model = model.trim();
        this.base = base.trim().replaceFirst("/$", "");
        this.provider = provider;
        this.available = !this.key.isBlank() && this.model.matches("[A-Za-z0-9/._-]{1,80}")
            && ("硅基流动".equals(provider) ? "https://api.siliconflow.cn/v1".equals(this.base) : OFFICIAL_BASE.equals(this.base));
    }

    static BigModelClient siliconflow(ObjectMapper json, String key, String model) {
        return new BigModelClient(json, key, model, "https://api.siliconflow.cn/v1", "硅基流动");
    }

    public boolean available() { return available; }
    public String modelName() { return available ? model : null; }

    public record Turn(@NotBlank @Size(max = 4000) String message, @NotBlank @Size(max = 8000) String answer) {}
    public record Lookup(String id, String question) {}
    public record Completion(String answer, Lookup lookup) {}

    private static final String LOOKUP = "lookup_platform";
    private static final Map<String, Object> TOOL = Map.of("type", "function", "function", Map.of(
        "name", LOOKUP, "description", "查询平台手册或只读业务事实。保留提问的数量、日期、状态和工单号；结合前文补全指代。",
        "parameters", Map.of("type", "object", "required", List.of("question"), "properties", Map.of("question",
            Map.of("type", "string", "description", "完整中文查询，如 最近五单的经营情况、本月实际到账多少、今天有多少工单、如何提交网点报价。")))));

    private List<Map<String, Object>> messages(String question, List<Turn> history) {
        var result = new ArrayList<Map<String, Object>>();
        result.add(Map.of("role", "system", "content", SYSTEM + "\n当前服务商是 " + provider + "，实际调用模型是 " + model + "。以本条系统信息回答模型身份，前文可能来自其他模型。"));
        for (var turn : history) {
            result.add(Map.of("role", "user", "content", turn.message()));
            result.add(Map.of("role", "assistant", "content", turn.answer()));
        }
        result.add(Map.of("role", "user", "content", question));
        return result;
    }

    public Completion start(String question, List<Turn> history) {
        var reply = post(messages(question, history), true);
        var calls = reply.path("tool_calls");
        if (calls.isArray() && !calls.isEmpty()) {
            var call = calls.path(0);
            var function = call.path("function");
            if (calls.size() == 1 && LOOKUP.equals(function.path("name").asText())) {
                String id = call.path("id").asText("");
                String args = function.path("arguments").asText("");
                if (id.matches("[A-Za-z0-9_-]{1,128}") && args.length() <= 8000) {
                    try {
                        String lookup = json.readTree(args).path("question").asText("");
                        if (!lookup.isBlank() && lookup.length() <= 4000) return new Completion(null, new Lookup(id, lookup));
                    } catch (JacksonException ignored) { /* An invalid request cannot execute a query. */ }
                }
            }
            throw failure("ASSISTANT_PROVIDER_RESPONSE", "模型返回了无效的平台查询，请重试或切换模型");
        }
        return new Completion(content(reply), null);
    }

    public String finish(String question, List<Turn> history, Lookup lookup, Map<String, Object> facts) {
        var messages = messages(question, history);
        if (lookup == null) {
            var instructions = messages.getFirst().get("content") + "\n请根据下列后端已核对的平台资料回答当前问题。\n" + json.writeValueAsString(facts);
            messages.set(0, Map.of("role", "system", "content", instructions));
            return content(post(messages, false));
        }
        var function = Map.of("name", LOOKUP, "arguments", json.writeValueAsString(Map.of("question", lookup.question())));
        messages.add(Map.of("role", "assistant", "tool_calls", List.of(Map.of("id", lookup.id(), "type", "function", "function", function))));
        messages.add(Map.of("role", "tool", "tool_call_id", lookup.id(), "content", json.writeValueAsString(facts)));
        return content(post(messages, false));
    }

    private static String content(JsonNode message) {
        String reply = message.path("content").asText("");
        if (!reply.isBlank() && reply.length() <= 8000) return reply;
        throw failure("ASSISTANT_PROVIDER_RESPONSE", "模型没有返回有效回答，请重试或切换模型");
    }

    private JsonNode post(List<Map<String, Object>> messages, boolean allowLookup) {
        if (!available) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "ASSISTANT_DISABLED", "平台助手尚未配置");
        var body = new java.util.LinkedHashMap<String, Object>();
        body.put("model", model);
        body.put("max_tokens", 1000);
        if ("智谱".equals(provider)) body.put("thinking", Map.of("type", "disabled"));
        else if (model.equals("XingChenAGI/Xing4.0-29B")) body.put("enable_thinking", false);
        body.put("messages", messages);
        if (allowLookup) {
            body.put("tools", List.of(TOOL));
        }
        var request = new Request.Builder().url(base + "/chat/completions")
            .header("Authorization", "Bearer " + key).post(RequestBody.create(json.writeValueAsBytes(body), JSON)).build();
        try (var response = http.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String code = "";
                try {
                    code = json.readTree(response.peekBody(8192).bytes()).path("error").path("code").asText("");
                } catch (JacksonException ignored) {
                    // Keep the HTTP fallback for non-JSON or truncated error bodies.
                }
                if ("智谱".equals(provider)) throw providerFailure(response.code(), code);
                throw failure("ASSISTANT_PROVIDER_ERROR", switch (response.code()) {
                    case 401, 403 -> "硅基流动未接受当前密钥或模型权限，请检查配置";
                    case 429 -> "硅基流动达到调用限制，请稍后重试或切换模型";
                    default -> "硅基流动未能完成请求，请重试或切换模型";
                });
            }
            try (var content = response.body().byteStream()) {
                byte[] bytes = content.readNBytes(128 * 1024 + 1);
                if (bytes.length <= 128 * 1024) {
                    var message = json.readTree(bytes).path("choices").path(0).path("message");
                    if (message.isObject()) return message;
                }
            }
            throw failure("ASSISTANT_PROVIDER_RESPONSE", provider + "没有返回有效回答，请重试或切换模型");
        } catch (java.io.InterruptedIOException error) {
            throw failure("ASSISTANT_PROVIDER_TIMEOUT", "等待" + provider + "回复超时，请重试或切换模型");
        } catch (IOException | JacksonException error) {
            throw failure("ASSISTANT_PROVIDER_ERROR", "无法取得" + provider + "回复，请重试或切换模型");
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
