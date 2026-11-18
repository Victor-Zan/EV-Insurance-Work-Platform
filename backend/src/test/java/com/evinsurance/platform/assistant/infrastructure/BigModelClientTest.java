package com.evinsurance.platform.assistant.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class BigModelClientTest {
    @Test void distinguishesModelAndAccountFailuresWithoutReusingProviderText() {
        assertThat(BigModelClient.providerFailure(400, "1211").code()).isEqualTo("ASSISTANT_PROVIDER_MODEL");
        assertThat(BigModelClient.providerFailure(429, "1305").code()).isEqualTo("ASSISTANT_PROVIDER_BUSY");
        assertThat(BigModelClient.providerFailure(429, "1302").code()).isEqualTo("ASSISTANT_PROVIDER_RATE");
        assertThat(BigModelClient.providerFailure(429, "1308").code()).isEqualTo("ASSISTANT_PROVIDER_QUOTA");
        assertThat(BigModelClient.providerFailure(429, "1113").code()).isEqualTo("ASSISTANT_PROVIDER_BALANCE");
        assertThat(BigModelClient.providerFailure(429, "untrusted provider payload").getMessage())
            .contains("HTTP 429").doesNotContain("untrusted", "额度");
    }
}
