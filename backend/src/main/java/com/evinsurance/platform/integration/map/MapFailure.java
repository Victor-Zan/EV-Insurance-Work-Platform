package com.evinsurance.platform.integration.map;

import com.evinsurance.platform.foundation.api.ApiException;
import org.springframework.http.HttpStatus;

/** Only controlled codes/messages cross the provider boundary; never attach HTTP causes or URLs. */
public final class MapFailure extends ApiException {
    private MapFailure(String code) { super(HttpStatus.BAD_GATEWAY,"MAP_"+code,messageFor(code)); }
    public static MapFailure of(String code){return new MapFailure(code);}
    private static String messageFor(String code){return switch(code){
        case "CREDENTIALS" -> "高德凭据无效，请管理员检查后端配置";
        case "PERMISSION" -> "高德接口权限或安全配置不允许本次请求";
        case "QUOTA" -> "高德调用额度不足";
        case "RATE_LIMIT" -> "地图请求过于频繁，请稍后明确重试";
        case "LOCAL_BUDGET" -> "本机地图调用预算未启用或已用完";
        case "TIMEOUT" -> "高德请求超时，可能已计入调用量；未自动重试";
        case "NETWORK" -> "无法连接高德服务，未自动重试";
        case "NO_RESULT" -> "未解析到位置，请补充详细地址";
        case "NO_ROUTE" -> "未找到可行驶路线，距离未知";
        case "REGION_MISMATCH" -> "位置不属于所选广东城市或区县";
        case "AMBIGUOUS" -> "位置不明确，请补充街道门牌或明确地标";
        case "STRUCTURED_ADDRESS_REQUIRED" -> "真实定位需要广东城市、区县和详细地址";
        case "INVALID_REQUEST" -> "高德拒绝定位参数，请核对详细地址或坐标";
        case "UNSUPPORTED_OPERATION" -> "当前供应商不支持此操作";
        case "UPSTREAM" -> "高德服务返回异常，本次未取得可信结果；未自动重试";
        case "BAD_RESPONSE" -> "高德返回的数据格式异常，本次未取得可信结果；未自动重试";
        default -> "高德返回异常，本次未取得可信结果";
    };}
}
