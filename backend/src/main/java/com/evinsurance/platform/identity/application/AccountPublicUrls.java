package com.evinsurance.platform.identity.application;
import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
@Component
public class AccountPublicUrls {
    private final String admin,h5;
    public AccountPublicUrls(@Value("${ACCOUNT_ADMIN_PUBLIC_URL:http://localhost:5173}") String admin,@Value("${ACCOUNT_H5_PUBLIC_URL:http://localhost:5174}") String h5){this.admin=validate(admin);this.h5=validate(h5);}
    private String validate(String value){URI uri;try{uri=URI.create(value);}catch(Exception e){throw new IllegalArgumentException("Invalid account public URL");}if(uri.getHost()==null||uri.getUserInfo()!=null||uri.getQuery()!=null||uri.getFragment()!=null||!("https".equals(uri.getScheme())||("http".equals(uri.getScheme())&&java.util.Set.of("localhost","127.0.0.1","[::1]").contains(uri.getHost()))))throw new IllegalArgumentException("Account public URL requires HTTPS except loopback");return value.replaceAll("/+$","");}
    public String base(com.evinsurance.platform.identity.domain.Portal portal){return portal==com.evinsurance.platform.identity.domain.Portal.ADMIN?admin:h5;}
}
