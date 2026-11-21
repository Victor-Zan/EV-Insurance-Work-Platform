package com.evinsurance.platform.identity.application;

import com.evinsurance.platform.audit.application.AuditService;
import com.evinsurance.platform.audit.application.AuditService.Action;
import com.evinsurance.platform.foundation.api.*;
import com.evinsurance.platform.identity.api.*;
import com.evinsurance.platform.identity.domain.*;
import com.evinsurance.platform.identity.infrastructure.*;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class WechatIdentityService {
    private final WechatLoginConfiguration configuration;
    private final WechatCodeExchange exchange;
    private final WechatBindingStore bindings;
    private final UserMapper users;
    private final JdbcTemplate db;
    private final AuthService auth;
    private final AccountActivationService accounts;
    private final AccountRateLimit limits;
    private final AccountFailureAudit failures;
    private final PasswordEncoder encoder;
    private final JwtService jwt;
    private final AuditService audit;
    private final TransactionTemplate transactions;

    public WechatIdentityService(WechatLoginConfiguration configuration,WechatCodeExchange exchange,WechatBindingStore bindings,
        UserMapper users,JdbcTemplate db,AuthService auth,AccountActivationService accounts,AccountRateLimit limits,
        AccountFailureAudit failures,PasswordEncoder encoder,JwtService jwt,AuditService audit,PlatformTransactionManager manager) {
        this.configuration=configuration;this.exchange=exchange;this.bindings=bindings;this.users=users;this.db=db;
        this.auth=auth;this.accounts=accounts;this.limits=limits;this.failures=failures;this.encoder=encoder;
        this.jwt=jwt;this.audit=audit;this.transactions=new TransactionTemplate(manager);
    }
    private void available() {
        if(!configuration.isEnabled())throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"WECHAT_UNAVAILABLE","微信登录服务尚未开通");
    }
    public void publicRequest(String remoteAddress) {
        available();limits.take("wechat-ip:"+remoteAddress,20);
    }
    private String identity(String code) {
        if(code==null||code.isBlank()||code.length()>512)throw ApiException.invalid("微信登录凭证格式无效");
        String openid=exchange.exchange(code);
        limits.take("wechat-identity:"+configuration.getAppId()+":"+openid,10);
        return openid;
    }
    private <T> T attempt(Supplier<T> operation) {
        try { return operation.get(); }
        catch(DataIntegrityViolationException error) {
            failures.wechatRejected();
            throw ApiException.conflict("WECHAT_BINDING_CONFLICT","微信或服务账号已存在绑定，请联系工作人员核实");
        } catch(ApiException error) {
            if(error.status()!=HttpStatus.TOO_MANY_REQUESTS)failures.wechatRejected();
            throw error;
        }
    }
    private Role mobileRole(AccessRow row) {
        if(row==null||(!row.roles().equals(Set.of(Role.OWNER))&&!row.roles().equals(Set.of(Role.REPAIR_SHOP))))
            throw ApiException.conflict("WECHAT_IDENTITY_CONFLICT","账号需要唯一车主或网点身份，请联系工作人员核实");
        return row.roles().iterator().next();
    }
    private AccessRow currentUser(long userId) {
        users.lockById(userId);
        var row=users.accessById(userId);
        if(row==null||!row.enabled()||!row.activated()||!row.bindingValid())
            throw new ApiException(HttpStatus.UNAUTHORIZED,"SESSION_INVALID","账号或身份绑定已失效，请联系工作人员");
        mobileRole(row);return row;
    }
    private void requireSnapshot(WechatBindingStore.Binding binding,AccessRow row) {
        if(!binding.matches(row))throw ApiException.conflict("WECHAT_IDENTITY_CONFLICT","绑定身份或网点已变化，请联系工作人员核实");
    }
    private void bump(long userId) {
        // Mapper writes also invalidate MyBatis' transaction-local AccessRow cache before JWT issuance.
        users.invalidateSessions(userId);
    }
    private WechatViews.LoginResult signed(AccessRow row,WechatBindingStore.Binding binding) {
        var issued=jwt.issueWechat(row.current(),binding.id());
        audit.record(row.current(),Action.LOGIN,"USER",row.id(),"Portal: WECHAT; binding="+binding.id());
        return new WechatViews.LoginResult(issued.accessToken(),issued.expiresAt(),WechatViews.User.from(row));
    }
    public WechatViews.LoginResult login(WechatRequests.Login request) {
        available();return attempt(()->{
            String openid=identity(request.code());
            return transactions.execute(status->{
                bindings.lockIdentity(configuration.getAppId(),openid);
                var observed=bindings.byIdentity(configuration.getAppId(),openid);
                if(observed==null) {
                    if(bindings.hasIdentityHistory(configuration.getAppId(),openid))
                        throw ApiException.conflict("WECHAT_IDENTITY_REVOKED","此微信的服务身份已解除，请联系工作人员核实");
                    return registerOwner(openid);
                }
                var row=currentUser(observed.userId());
                var binding=bindings.byIdentity(configuration.getAppId(),openid);
                if(binding==null||!binding.id().equals(observed.id()))
                    throw ApiException.conflict("WECHAT_BINDING_REQUIRED","微信绑定已变化，请重新登录");
                requireSnapshot(binding,row);return signed(row,binding);
            });
        });
    }
    private WechatViews.LoginResult registerOwner(String openid) {
        users.lockOwnerMatching();
        var user=new UserEntity();
        user.setUsername("wx_"+UUID.randomUUID().toString().replace("-",""));user.setDisplayName("微信用户");
        // A unique, unknown secret satisfies the existing account schema; no default password is issued.
        user.setPasswordHash(encoder.encode(UUID.randomUUID().toString()+UUID.randomUUID()));
        user.setEnabled(true);user.setActivated(true);user.setAuthVersion(0L);
        user.setCreatedAt(Instant.now());user.setUpdatedAt(user.getCreatedAt());users.insert(user);
        users.addRole(user.getId(),Role.OWNER.name());users.ensureOwner(user.getId(),user.getDisplayName());
        // Reuse the existing manual-claim guard: never discover/claim old cases by phone.
        db.update("UPDATE owner_profile SET invitation_managed=true WHERE user_id=?",user.getId());
        var row=users.accessById(user.getId());var binding=bindings.insert(configuration.getAppId(),openid,row);
        audit.record(row.current(),Action.USER_CREATE,"USER",row.id(),"Wechat first login; OWNER account created; no cases claimed");
        audit.record(row.current(),Action.ROLE_CHANGE,"USER",row.id(),"Roles: [] -> [OWNER]; first Wechat login");
        audit.record(row.current(),Action.WECHAT_BIND,"WECHAT_BINDING",binding.id(),"First Wechat login; account, owner profile and binding created atomically");
        return signed(row,binding);
    }
    private void password(String value) {
        if(value==null||value.isBlank()||value.getBytes(StandardCharsets.UTF_8).length>72)
            throw ApiException.invalid("密码须非空且不超过72个UTF8字节");
    }
    private void confirmed(Boolean confirmed) {
        if(!Boolean.TRUE.equals(confirmed))throw ApiException.invalid("请明确确认本次操作");
    }
    public WechatViews.LoginResult bind(WechatRequests.Bind request) {
        available();return attempt(()->{
            confirmed(request.confirmed());password(request.password());
            if(request.username()==null||request.username().isBlank()||request.username().length()>64)throw ApiException.invalid("账号格式无效");
            limits.take("wechat-account:"+request.username(),10);
            var proof=auth.authenticate(request.username(),request.password());mobileRole(proof);
            String openid=identity(request.code());
            return transactions.execute(status->{
                var row=currentUser(proof.id());
                if(!Objects.equals(row.passwordHash(),proof.passwordHash())||row.authVersion()!=proof.authVersion())
                    throw new ApiException(HttpStatus.UNAUTHORIZED,"INVALID_CREDENTIALS","账号凭据已变化，请重新核实");
                var identityBinding=bindings.byIdentity(configuration.getAppId(),openid);
                var userBinding=bindings.activeForUser(configuration.getAppId(),row.id());
                if(identityBinding!=null||userBinding!=null) {
                    if(identityBinding==null||userBinding==null||!identityBinding.id().equals(userBinding.id()))
                        throw ApiException.conflict("WECHAT_BINDING_CONFLICT","微信或服务账号已绑定其他身份，请联系工作人员核实");
                    requireSnapshot(identityBinding,row);return signed(row,identityBinding);
                }
                var binding=bindings.insert(configuration.getAppId(),openid,row);bump(row.id());
                row=users.accessById(row.id());
                audit.record(row.current(),Action.WECHAT_BIND,"WECHAT_BINDING",binding.id(),"Existing activated account bound; previous account sessions invalidated");
                return signed(row,binding);
            });
        });
    }
    private Map<String,Object> invitation(String token,boolean lock) {
        var invitation=accounts.valid(token,lock);
        var row=users.accessById(((Number)invitation.get("user_id")).longValue());
        if(!row.roles().equals(Set.of(Role.OWNER))&&!row.roles().equals(Set.of(Role.REPAIR_SHOP)))
            throw ApiException.conflict("INVITATION_INVALID","邀请不可用、已失效或已办理，请联系邀请人员核实");
        return invitation;
    }
    private String masked(String value) {
        return value==null||value.isBlank()?"***":value.substring(0,value.offsetByCodePoints(0,1))+"***";
    }
    public WechatViews.Preview inspect(WechatRequests.Inspect request) {
        available();return attempt(()->{
            var invitation=invitation(request.token(),false);
            var row=users.accessById(((Number)invitation.get("user_id")).longValue());
            String shop=row.shopId()==null?null:db.queryForObject("SELECT name FROM repair_shop WHERE id=?",String.class,row.shopId());
            return new WechatViews.Preview(masked(row.displayName()),masked(row.username()),mobileRole(row),maskedNullable(shop),
                ((Timestamp)invitation.get("expires_at")).toInstant());
        });
    }
    private String maskedNullable(String value) { return value==null?null:masked(value); }
    public WechatViews.LoginResult activate(WechatRequests.Activate request) {
        available();return attempt(()->{
            confirmed(request.confirmed());password(request.password());
            if(!request.password().equals(request.confirmPassword()))throw ApiException.invalid("两次密码输入不一致");
            invitation(request.token(),false);
            String hash=encoder.encode(request.password());
            String openid=identity(request.code());
            return transactions.execute(status->{
                var invitation=invitation(request.token(),true);
                long userId=((Number)invitation.get("user_id")).longValue();
                var row=users.accessById(userId);
                if(bindings.byIdentity(configuration.getAppId(),openid)!=null||bindings.activeForUser(configuration.getAppId(),userId)!=null)
                    throw ApiException.conflict("WECHAT_BINDING_CONFLICT","微信或服务账号已存在绑定，请联系工作人员核实");
                var binding=bindings.insert(configuration.getAppId(),openid,row);
                if(users.activateInvited(userId,hash,row.authVersion())!=1)
                    throw ApiException.conflict("INVITATION_INVALID","邀请状态已变化，请重新核实");
                if(db.update("UPDATE account_invitation SET used_at=now() WHERE id=? AND used_at IS NULL AND revoked_at IS NULL AND expires_at>now()",invitation.get("id"))!=1)
                    throw ApiException.conflict("INVITATION_INVALID","邀请不可用或已办理");
                row=users.accessById(userId);
                audit.record(row.current(),Action.ACCOUNT_ACTIVATE,"USER",userId,"Wechat invitation activation; password set; invitation consumed atomically");
                audit.record(row.current(),Action.WECHAT_BIND,"WECHAT_BINDING",binding.id(),"Invitation account activated and bound in one transaction");
                return signed(row,binding);
            });
        });
    }
    private WechatViews.Invitation view(AccountActivationService.InvitationCredential value) {
        return new WechatViews.Invitation(value.userId(),value.username(),value.alreadyActive(),value.expiresAt(),
            value.alreadyActive()?"pages/login/index":"pages/activate/index",value.token());
    }
    public WechatViews.Invitation issue(long userId) {
        available();return attempt(()->view(accounts.issueWechat(userId)));
    }
    public WechatViews.Invitation ownerInvitation(UUID caseId,WechatRequests.OwnerInvitation request) {
        available();confirmed(request.verified());
        return attempt(()->view(accounts.ownerWechat(caseId,new AccountActivationService.OwnerRequest(
            request.expectedVersion(),true,request.reason(),request.existingOwnerId()))));
    }
    public PageResponse<WechatViews.Binding> list(long userId,int page,int size) {
        CurrentUser.require().requireAdmin();
        if(users.accessById(userId)==null)throw ApiException.missing("User");
        return bindings.page(userId,page,size);
    }
    public void revoke(long userId,UUID bindingId,WechatRequests.Revoke request) {
        var actor=CurrentUser.require();actor.requireAdmin();confirmed(request.verified());
        if(request.expectedVersion()==null||request.expectedVersion()<0||request.reason()==null||request.reason().isBlank()||request.reason().length()>2000)
            throw ApiException.invalid("请填写有效版本与核实原因");
        limits.take("wechat-revoke:"+actor.id(),20);
        transactions.executeWithoutResult(status->{
            if(users.lockById(userId)==null)throw ApiException.missing("User");
            String reason=request.reason().trim().replaceAll("[\\p{Cntrl}]"," ")
                .replaceAll("[A-Za-z0-9_-]{16,}(?:\\.[A-Za-z0-9_-]+){0,2}","[REDACTED]")
                .replaceAll("[0-9]{7,}","[REDACTED]");
            if(!bindings.revoke(bindingId,userId,request.expectedVersion(),actor.id(),reason))
                throw ApiException.conflict("WECHAT_BINDING_VERSION_CONFLICT","绑定状态已变化，请刷新核实");
            bump(userId);
            audit.record(actor,Action.WECHAT_REVOKE,"WECHAT_BINDING",bindingId,"Verified user="+userId+"; all previous account sessions invalidated; recovery reason retained in restricted binding metadata");
        });
    }
}
