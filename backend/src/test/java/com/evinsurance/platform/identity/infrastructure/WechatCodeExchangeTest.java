package com.evinsurance.platform.identity.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.evinsurance.platform.foundation.api.ApiException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import okhttp3.Call;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(OutputCaptureExtension.class)
class WechatCodeExchangeTest {
    private static final String SECRET = "sensitive-secret";
    private static final String CODE = "sensitive-code";
    private final ObjectMapper json = new ObjectMapper();

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties
    @Import(WechatLoginConfiguration.class)
    static class TestConfiguration { }

    private final ApplicationContextRunner context = new ApplicationContextRunner()
        .withUserConfiguration(TestConfiguration.class);

    @Test void configurationDefaultsOffAndEnabledRequiresBothCredentials() {
        context.run(c -> assertThat(c.getBean(WechatLoginConfiguration.class).isEnabled()).isFalse());
        context.withPropertyValues("app.wechat-login.enabled=true").run(c -> assertThat(c).hasFailed());
        context.withPropertyValues("app.wechat-login.enabled=true", "app.wechat-login.app-id=id",
            "app.wechat-login.app-secret= ").run(c -> assertThat(c).hasFailed());
        context.withPropertyValues("app.wechat-login.enabled=true", "app.wechat-login.app-id=id",
            "app.wechat-login.app-secret=" + SECRET).run(c -> {
                assertThat(c).hasNotFailed();
                assertThat(c.getBean(WechatLoginConfiguration.class).toString()).doesNotContain(SECRET);
            });
    }

    @Test void successUsesFixedEndpointAndReturnsOnlyOpenid() throws Exception {
        Call.Factory factory = response(200, "{\"openid\":\"openid-value\",\"session_key\":\"private-session\"}");
        assertThat(exchange(factory).exchange(CODE)).isEqualTo("openid-value");
        ArgumentCaptor<Request> request = ArgumentCaptor.forClass(Request.class);
        verify(factory).newCall(request.capture());
        assertThat(request.getValue().method()).isEqualTo("GET");
        var url = request.getValue().url();
        assertThat(url.scheme()).isEqualTo("https");
        assertThat(url.host()).isEqualTo("api.weixin.qq.com");
        assertThat(url.encodedPath()).isEqualTo("/sns/jscode2session");
        assertThat(url.queryParameter("appid")).isEqualTo("test-id");
        assertThat(url.queryParameter("secret")).isEqualTo(SECRET);
        assertThat(url.queryParameter("js_code")).isEqualTo(CODE);
        assertThat(url.queryParameter("grant_type")).isEqualTo("authorization_code");
        assertThat(url.querySize()).isEqualTo(4);
    }

    @Test void mapsKnownErrorsAndRejectsUnknownOrMalformedSuccess() throws Exception {
        failure("{\"errcode\":40029,\"openid\":\"ignored\"}", HttpStatus.UNAUTHORIZED, "WECHAT_CODE_INVALID");
        failure("{\"errcode\":40163}", HttpStatus.UNAUTHORIZED, "WECHAT_CODE_INVALID");
        failure("{\"errcode\":45011}", HttpStatus.TOO_MANY_REQUESTS, "ACCOUNT_RATE_LIMIT");
        failure("{\"errcode\":123456,\"openid\":\"ignored\"}", HttpStatus.BAD_GATEWAY, "WECHAT_PROVIDER_ERROR");
        failure("{\"errcode\":\"0\",\"openid\":\"ignored\"}", HttpStatus.BAD_GATEWAY, "WECHAT_PROVIDER_ERROR");
        failure("{\"errcode\":0}", HttpStatus.BAD_GATEWAY, "WECHAT_PROVIDER_ERROR");
        failure("{\"openid\":\"  \"}", HttpStatus.BAD_GATEWAY, "WECHAT_PROVIDER_ERROR");
        failure("not-json", HttpStatus.BAD_GATEWAY, "WECHAT_PROVIDER_ERROR");
        assertFailure(exchange(response(500, "{}")), HttpStatus.BAD_GATEWAY, "WECHAT_PROVIDER_ERROR");
    }

    @Test void refusesOversizedBodyEvenWhenLengthUnknown() throws Exception {
        assertFailure(exchange(response(200, "x".repeat(65537))), HttpStatus.BAD_GATEWAY, "WECHAT_PROVIDER_ERROR");
    }

    @Test void disabledOrMissingConfigurationNeverCallsProvider() {
        Call.Factory calls = mock(Call.Factory.class);
        var configuration = new WechatLoginConfiguration();
        assertFailure(new WechatCodeExchange(configuration, json, calls), HttpStatus.SERVICE_UNAVAILABLE, "WECHAT_UNAVAILABLE");
        configuration.setEnabled(true);
        assertFailure(new WechatCodeExchange(configuration, json, calls), HttpStatus.SERVICE_UNAVAILABLE, "WECHAT_UNAVAILABLE");
        verifyNoInteractions(calls);
    }

    @Test void transportAndMalformedResponseDoNotLeakSecrets(CapturedOutput output) throws Exception {
        Call.Factory factory = mock(Call.Factory.class);
        Call call = mock(Call.class);
        when(factory.newCall(any(Request.class))).thenReturn(call);
        when(call.execute()).thenThrow(new IOException("https://api.weixin.qq.com/?secret=" + SECRET + "&code=" + CODE + " private-session"));
        assertThatThrownBy(() -> exchange(factory).exchange(CODE)).isInstanceOfSatisfying(ApiException.class, error -> {
            assertThat(error.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(error.code()).isEqualTo("WECHAT_UNAVAILABLE");
            assertThat(error.getCause()).isNull();
            assertThat(error.toString()).doesNotContain(SECRET, CODE, "api.weixin.qq.com", "private-session");
        });
        assertFailure(exchange(response(200, "{\"errcode\":999,\"errmsg\":\"" + SECRET + CODE + "private-session\"}")),
            HttpStatus.BAD_GATEWAY, "WECHAT_PROVIDER_ERROR");
        assertThat(output.getAll()).doesNotContain(SECRET, CODE, "api.weixin.qq.com", "private-session");
    }

    private void failure(String body, HttpStatus status, String code) throws Exception {
        assertFailure(exchange(response(200, body)), status, code);
    }

    private void assertFailure(WechatCodeExchange exchange, HttpStatus status, String code) {
        assertThatThrownBy(() -> exchange.exchange(CODE)).isInstanceOfSatisfying(ApiException.class, error -> {
            assertThat(error.status()).isEqualTo(status);
            assertThat(error.code()).isEqualTo(code);
            assertThat(error.getCause()).isNull();
            assertThat(error.toString()).doesNotContain(SECRET, CODE, "api.weixin.qq.com", "private-session");
        });
    }

    private WechatCodeExchange exchange(Call.Factory calls) {
        var configuration = new WechatLoginConfiguration();
        configuration.setEnabled(true);
        configuration.setAppId("test-id");
        configuration.setAppSecret(SECRET);
        return new WechatCodeExchange(configuration, json, calls);
    }

    private Call.Factory response(int status, String body) throws Exception {
        Call.Factory factory = mock(Call.Factory.class);
        Call call = mock(Call.class);
        Response response = mock(Response.class);
        ResponseBody responseBody = mock(ResponseBody.class);
        when(factory.newCall(any(Request.class))).thenReturn(call);
        when(call.execute()).thenReturn(response);
        when(response.isSuccessful()).thenReturn(status >= 200 && status < 300);
        when(response.body()).thenReturn(responseBody);
        when(responseBody.contentLength()).thenReturn(-1L);
        when(responseBody.byteStream()).thenReturn(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
        return factory;
    }
}
