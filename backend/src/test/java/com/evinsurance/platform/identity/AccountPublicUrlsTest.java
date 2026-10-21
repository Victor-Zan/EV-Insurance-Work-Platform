package com.evinsurance.platform.identity;
import static org.assertj.core.api.Assertions.*;
import com.evinsurance.platform.identity.application.AccountPublicUrls;
import com.evinsurance.platform.identity.domain.Portal;
import org.junit.jupiter.api.Test;
class AccountPublicUrlsTest {
    @Test void configuredOriginsPreservePrefixAndRejectCredentialLeakOrInsecureHosts(){
        var urls=new AccountPublicUrls("https://staff.example.test/platform/","http://localhost:5174/");
        assertThat(urls.base(Portal.ADMIN)).isEqualTo("https://staff.example.test/platform");assertThat(urls.base(Portal.H5)).isEqualTo("http://localhost:5174");
        for(String invalid:new String[]{"http://example.test","https://user:pass@example.test","https://example.test?q=1","https://example.test#secret","//example.test","javascript:alert(1)"})assertThatThrownBy(()->new AccountPublicUrls(invalid,"http://localhost:5174")).isInstanceOf(IllegalArgumentException.class);
    }
}
