package com.evinsurance.platform.identity.infrastructure;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.wechat-login")
public class WechatLoginConfiguration {
    private boolean enabled;
    private String appId;
    private String appSecret;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getAppId() { return appId; }
    public void setAppId(String appId) { this.appId = appId; }
    public String getAppSecret() { return appSecret; }
    public void setAppSecret(String appSecret) { this.appSecret = appSecret; }

    @PostConstruct
    void validate() {
        if (enabled && (appId == null || !appId.matches("[A-Za-z0-9_-]{1,64}") || appSecret == null || appSecret.isBlank()
            || appSecret.length()>1024 || !appSecret.equals(appSecret.trim()))) {
            throw new IllegalStateException("WeChat login requires AppID and AppSecret");
        }
    }
}
