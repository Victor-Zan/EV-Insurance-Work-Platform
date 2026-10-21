package com.evinsurance.platform.identity.application;
import com.evinsurance.platform.identity.domain.*;
import com.evinsurance.platform.identity.infrastructure.*;
import com.evinsurance.platform.identity.api.*;
import com.evinsurance.platform.foundation.api.ApiException;
import com.evinsurance.platform.audit.application.AuditService;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
@Service
public class UnifiedLoginService {
    private final AuthService auth;private final UserMapper users;private final JwtService jwt;private final JdbcTemplate db;private final AccountPublicUrls urls;private final AuditService audit;
    public UnifiedLoginService(AuthService auth,UserMapper users,JwtService jwt,JdbcTemplate db,AccountPublicUrls urls,AuditService audit){this.auth=auth;this.users=users;this.jwt=jwt;this.db=db;this.urls=urls;this.audit=audit;}
    public record Destination(Portal portal,String loginUrl,String code){}
    public Destination signIn(String username,String password){var row=auth.authenticate(username,password);Portal portal=AccountActivationService.portal(row);String code=AccountActivationService.token();db.update("INSERT INTO login_handoff(token_hash,user_id,auth_version,portal,expires_at) VALUES(?,?,?,?,now()+interval '60 seconds')",AccountActivationService.digest(code),row.id(),row.authVersion(),portal.name());return new Destination(portal,urls.base(portal)+"/login",code);}
    @Transactional public AuthService.LoginResult redeem(String code,Portal portal){
        if(code==null||!code.matches("[A-Za-z0-9_-]{43}")||portal==null)throw ApiException.denied();
        var result=db.queryForList("SELECT * FROM login_handoff WHERE token_hash=?",AccountActivationService.digest(code));if(result.isEmpty())throw ApiException.denied();var entry=result.getFirst();long id=((Number)entry.get("user_id")).longValue();users.lockById(id);var row=users.accessById(id);
        if(row==null||!row.enabled()||!row.activated()||!row.bindingValid()||row.authVersion()!=((Number)entry.get("auth_version")).longValue()||!portal.name().equals(entry.get("portal"))||!portal.allows(row.roles()))throw ApiException.denied();
        if(db.update("UPDATE login_handoff SET used_at=now() WHERE token_hash=? AND used_at IS NULL AND expires_at>now()",AccountActivationService.digest(code))!=1)throw ApiException.conflict("HANDOFF_INVALID","登录交接已失效，请重新登录");
        var token=jwt.issue(row.current());audit.record(row.current(),AuditService.Action.LOGIN,"USER",id,"Unified sign-in; portal="+portal);return new AuthService.LoginResult(token.accessToken(),token.expiresAt(),UserView.from(row));
    }
}
