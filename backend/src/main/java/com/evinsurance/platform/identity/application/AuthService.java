package com.evinsurance.platform.identity.application;

import com.evinsurance.platform.identity.api.*;
import com.evinsurance.platform.identity.infrastructure.*;
import com.evinsurance.platform.audit.application.AuditService;
import com.evinsurance.platform.audit.application.AuditService.Action;
import com.evinsurance.platform.foundation.api.ApiException;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {
    private final UserMapper users;
    private final PasswordEncoder encoder;
    private final JwtService jwt;
    private final AuditService audit;
    private final String dummyHash;
    public AuthService(UserMapper users, PasswordEncoder encoder, JwtService jwt, AuditService audit) {
        this.users=users; this.encoder=encoder; this.jwt=jwt; this.audit=audit;
        this.dummyHash=encoder.encode(java.util.UUID.randomUUID().toString());
    }
    public LoginResult login(IdentityRequests.Login request) {
        AccessRow row=users.accessByUsername(request.username());
        boolean fitsBcrypt=request.password().getBytes(java.nio.charset.StandardCharsets.UTF_8).length<=72;
        boolean matches=fitsBcrypt && encoder.matches(request.password(), row == null ? dummyHash : row.passwordHash());
        if (row == null || !matches || !row.enabled() || row.roles().isEmpty()) {
            audit.record(null,Action.LOGIN_FAILURE,"USER",row == null ? null : row.id(),"Credentials rejected");
            throw new ApiException(HttpStatus.UNAUTHORIZED,"INVALID_CREDENTIALS","Account or password is incorrect");
        }
        if (!request.portal().allows(row.roles())) {
            audit.record(row.current(),Action.LOGIN_FAILURE,"USER",row.id(),"Portal denied: " + request.portal());
            throw new ApiException(HttpStatus.FORBIDDEN,"PORTAL_MISMATCH","该账号不能登录当前端，请使用对应端登录");
        }
        var token=jwt.issue(row.current());
        audit.record(row.current(),Action.LOGIN,"USER",row.id(),"Portal: " + request.portal());
        return new LoginResult(token.accessToken(),token.expiresAt(),UserView.from(row));
    }
    public record LoginResult(String accessToken, Instant expiresAt, UserView user) {}
}
