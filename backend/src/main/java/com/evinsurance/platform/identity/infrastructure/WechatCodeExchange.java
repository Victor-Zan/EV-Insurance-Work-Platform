package com.evinsurance.platform.identity.infrastructure;

import com.evinsurance.platform.foundation.api.ApiException;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import okhttp3.Call;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class WechatCodeExchange {
    private static final String ENDPOINT = "https://api.weixin.qq.com/sns/jscode2session";
    private static final int RESPONSE_LIMIT = 64 * 1024;
    private final WechatLoginConfiguration configuration;
    private final ObjectMapper json;
    private final Call.Factory calls;

    @Autowired
    public WechatCodeExchange(WechatLoginConfiguration configuration, ObjectMapper json) {
        this(configuration, json, new OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS).callTimeout(10, TimeUnit.SECONDS)
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build());
    }

    // Test injection only; the destination remains fixed in all modes.
    WechatCodeExchange(WechatLoginConfiguration configuration, ObjectMapper json, Call.Factory calls) {
        this.configuration = configuration;
        this.json = json;
        this.calls = calls;
    }

    public String exchange(String code) {
        if (!configuration.isEnabled() || configuration.getAppId() == null || configuration.getAppId().isBlank()
            || configuration.getAppSecret() == null || configuration.getAppSecret().isBlank()) {
            throw unavailable();
        }
        if (code == null || code.isBlank()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "WECHAT_CODE_INVALID", "WeChat login code is invalid");
        }
        try {
            HttpUrl url = HttpUrl.get(ENDPOINT).newBuilder()
                .addQueryParameter("appid", configuration.getAppId())
                .addQueryParameter("secret", configuration.getAppSecret())
                .addQueryParameter("js_code", code)
                .addQueryParameter("grant_type", "authorization_code").build();
            Request request = new Request.Builder().url(url).get().build();
            try (var response = calls.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null
                    || response.body().contentLength() > RESPONSE_LIMIT) throw providerError();
                byte[] bytes;
                try (var input = response.body().byteStream()) {
                    bytes = input.readNBytes(RESPONSE_LIMIT + 1);
                }
                if (bytes.length > RESPONSE_LIMIT) throw providerError();
                JsonNode root = json.readTree(bytes);
                if (root == null || !root.isObject()) throw providerError();
                JsonNode errcode = root.get("errcode");
                if (errcode != null) {
                    if (!errcode.isIntegralNumber() || !errcode.canConvertToInt()) throw providerError();
                    int value = errcode.asInt();
                    if (value != 0) throw switch (value) {
                        case 40029, 40163 -> new ApiException(HttpStatus.UNAUTHORIZED, "WECHAT_CODE_INVALID", "WeChat login code is invalid");
                        case 45011 -> new ApiException(HttpStatus.TOO_MANY_REQUESTS, "ACCOUNT_RATE_LIMIT", "WeChat login is temporarily rate limited");
                        default -> providerError();
                    };
                }
                JsonNode openid = root.get("openid");
                if (openid == null || !openid.isString() || openid.asString().isBlank() || openid.asString().length()>128
                    || !openid.asString().equals(openid.asString().trim()) || openid.asString().chars().anyMatch(Character::isISOControl)) throw providerError();
                return openid.asString();
            }
        } catch (ApiException error) {
            throw error;
        } catch (IOException error) {
            throw unavailable();
        } catch (RuntimeException error) {
            throw providerError();
        }
    }

    private static ApiException providerError() {
        return new ApiException(HttpStatus.BAD_GATEWAY, "WECHAT_PROVIDER_ERROR", "WeChat login provider failed");
    }

    private static ApiException unavailable() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "WECHAT_UNAVAILABLE", "WeChat login is unavailable");
    }
}
