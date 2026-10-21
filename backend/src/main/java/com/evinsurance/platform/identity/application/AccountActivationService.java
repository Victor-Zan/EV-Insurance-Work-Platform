package com.evinsurance.platform.identity.application;

import com.evinsurance.platform.identity.api.*;
import com.evinsurance.platform.identity.domain.*;
import com.evinsurance.platform.identity.infrastructure.*;
import com.evinsurance.platform.audit.application.AuditService;
import com.evinsurance.platform.audit.application.AuditService.Action;
import com.evinsurance.platform.document.application.FileService;
import com.evinsurance.platform.document.application.FileAccess;
import com.evinsurance.platform.foundation.api.ApiException;
import com.evinsurance.platform.workorder.infrastructure.WorkOrderMapper;
import java.util.*;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountActivationService {
    public record Prepare(String username,String displayName,Role role,Long shopId){}
    public record OwnerRequest(Long expectedVersion,boolean verified,String reason,Long existingOwnerId){}
    public record Invitation(long userId,String username,String url,Instant expiresAt,boolean alreadyActive){
        public Invitation(long userId,String username,String url,Instant expiresAt){this(userId,username,url,expiresAt,false);}
    }
    private final JdbcTemplate db;private final UserMapper users;private final UserService userService;private final PasswordEncoder encoder;private final AuditService audit;private final AccountFailureAudit failure;private final AccountRateLimit limits;private final AccountPublicUrls urls;private final WorkOrderMapper orders;private final FileAccess access;
    public AccountActivationService(JdbcTemplate db,UserMapper users,UserService userService,PasswordEncoder encoder,AuditService audit,AccountFailureAudit failure,AccountRateLimit limits,AccountPublicUrls urls,WorkOrderMapper orders,FileAccess access){this.db=db;this.users=users;this.userService=userService;this.encoder=encoder;this.audit=audit;this.failure=failure;this.limits=limits;this.urls=urls;this.orders=orders;this.access=access;}
    public static String token(){byte[] bytes=new byte[32];new java.security.SecureRandom().nextBytes(bytes);return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);}
    public static String digest(String value){return FileService.hash(Objects.toString(value,"").getBytes(StandardCharsets.UTF_8));}
    public static Portal portal(AccessRow user){if(Portal.ADMIN.allows(user.roles()))return Portal.ADMIN;if(Portal.H5.allows(user.roles()))return Portal.H5;throw ApiException.denied();}
    @Transactional public Invitation prepare(Prepare request){
        CurrentUser.require().requireAdmin();if(request.role()==null||request.role()==Role.OWNER)throw ApiException.invalid("车主请从明确案件开通");
        if(request.role()==Role.REPAIR_SHOP&&!Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM repair_shop WHERE id=? AND enabled)",Boolean.class,request.shopId())))throw ApiException.invalid("请选择有效合作网点");
        if(request.username()==null||!request.username().matches("[A-Za-z0-9_.-]{3,64}")||request.displayName()==null||request.displayName().isBlank()||request.displayName().length()>100)throw ApiException.invalid("账号或显示名称不符合要求");
        limits.take("prepare:"+CurrentUser.require().id(),20);
        var user=userService.create(new IdentityRequests.CreateUser(request.username(),request.displayName(),token(),Set.of(request.role()),request.shopId(),null));
        users.markPending(user.id());return issue(user.id());
    }
    @Transactional public Invitation adminIssue(long userId){CurrentUser.require().requireAdmin();return issue(userId);}
    private Invitation issue(long userId){
        limits.take("issuer:"+CurrentUser.require().id(),20);limits.take("invite-target:"+userId,3);
        var user=users.lockById(userId);if(user==null)throw ApiException.missing("User");var row=users.accessById(userId);
        if(!row.enabled()||row.activated()||!row.bindingValid())throw ApiException.conflict("INVITATION_STATE","仅可邀请启用且身份绑定有效的待激活账号");
        boolean previous=Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM account_invitation WHERE user_id=?)",Boolean.class,userId));
        db.update("UPDATE account_invitation SET revoked_at=now() WHERE user_id=? AND used_at IS NULL AND revoked_at IS NULL",userId);
        String raw=token();Instant expires=Instant.now().plusSeconds(86400);
        db.update("INSERT INTO account_invitation(id,user_id,token_hash,purpose,auth_version,created_by,expires_at) VALUES(?,?,?,'ACTIVATE_ACCOUNT',?,?,?)",UUID.randomUUID(),userId,digest(raw),row.authVersion(),CurrentUser.require().id(),java.sql.Timestamp.from(expires));
        audit.record(CurrentUser.require(),previous?Action.INVITATION_REISSUE:Action.INVITATION_ISSUE,"USER",userId,"Activation invitation issued; validity=24h; credential omitted");
        return new Invitation(userId,row.username(),urls.base(portal(row))+"/activate#token="+raw,expires);
    }
    public Map<String,Object> status(long userId){CurrentUser.require().requireAdmin();var row=users.accessById(userId);if(row==null)throw ApiException.missing("User");var out=new LinkedHashMap<String,Object>();out.put("activated",row.activated());out.put("enabled",row.enabled());out.put("state",!row.enabled()?"DISABLED":row.activated()?"ACTIVE":"PENDING");var latest=db.queryForList("SELECT expires_at,used_at,revoked_at,auth_version FROM account_invitation WHERE user_id=? ORDER BY created_at DESC,id DESC LIMIT 1",userId);if(!latest.isEmpty()){var invite=latest.getFirst();out.put("expiresAt",invite.get("expires_at"));out.put("invitationState",invite.get("used_at")!=null?"USED":invite.get("revoked_at")!=null||((Number)invite.get("auth_version")).longValue()!=row.authVersion()?"REVOKED":((java.sql.Timestamp)invite.get("expires_at")).toInstant().isAfter(Instant.now())?"ISSUED":"EXPIRED");}return out;}
    private Map<String,Object> valid(String raw,boolean lock){
        if(raw==null||!raw.matches("[A-Za-z0-9_-]{43}"))return rejected();
        var found=db.queryForList("SELECT * FROM account_invitation WHERE token_hash=?",digest(raw));if(found.isEmpty())return rejected();long id=((Number)found.getFirst().get("user_id")).longValue();if(lock)users.lockById(id);
        var current=db.queryForList("SELECT * FROM account_invitation WHERE token_hash=? AND purpose='ACTIVATE_ACCOUNT' AND used_at IS NULL AND revoked_at IS NULL AND expires_at>now()",digest(raw));var user=users.accessById(id);
        if(current.isEmpty()||user==null||user.activated()||!user.enabled()||!user.bindingValid()||user.authVersion()!=((Number)current.getFirst().get("auth_version")).longValue())return rejected();return current.getFirst();
    }
    private Map<String,Object> rejected(){failure.rejected();throw ApiException.conflict("INVITATION_INVALID","邀请不可用、已失效或已办理，请联系邀请人员核实");}
    public Map<String,Object> inspect(String raw){var invitation=valid(raw,false);var row=users.accessById(((Number)invitation.get("user_id")).longValue());var out=new LinkedHashMap<String,Object>();out.put("username",row.username().substring(0,Math.min(2,row.username().length()))+"***");out.put("displayName",row.displayName().substring(0,1)+"**");out.put("roles",row.roles());out.put("loginUrl",urls.base(portal(row))+"/login");if(row.shopId()!=null)out.put("shop",db.queryForObject("SELECT name FROM repair_shop WHERE id=?",String.class,row.shopId()));return out;}
    @Transactional public Map<String,String> activate(String raw,String password,String confirm){
        if(password==null||password.isBlank()||password.getBytes(StandardCharsets.UTF_8).length>72||!password.equals(confirm))throw ApiException.invalid("密码须非空、不超过72个UTF8字节，且两次输入一致");
        var invitation=valid(raw,true);long id=((Number)invitation.get("user_id")).longValue();
        if(db.update("UPDATE app_user SET activated=true,password_hash=?,auth_version=auth_version+1,updated_at=now() WHERE id=? AND enabled AND NOT activated",encoder.encode(password),id)!=1){rejected();throw ApiException.denied();}
        if(db.update("UPDATE account_invitation SET used_at=now() WHERE id=? AND used_at IS NULL AND revoked_at IS NULL",invitation.get("id"))!=1)throw ApiException.conflict("INVITATION_INVALID","邀请已办理");
        var row=users.accessById(id);
        audit.record(row.current(),Action.ACCOUNT_ACTIVATE,"USER",id,"Invitation-bound account activated; enabled state unchanged");
        return Map.of("username",row.username(),"loginUrl",urls.base(portal(row))+"/login");
    }
    @Transactional public Invitation owner(UUID caseId,OwnerRequest request){
        var actor=CurrentUser.require();if(!actor.roles().contains(Role.ADMIN)&&!actor.roles().contains(Role.CUSTOMER_SERVICE))throw ApiException.denied();
        if(!request.verified()||request.reason()==null||request.reason().isBlank()||request.reason().length()>2000)throw ApiException.invalid("请通过已核实联系方式确认本人及案件归属，并填写核实说明");
        if(request.existingOwnerId()!=null&&!actor.roles().contains(Role.ADMIN))throw ApiException.denied();
        var order=access.order(caseId,true);if(Set.of("DRAFT","CANCELLED","COMPLETED","CLOSED").contains(order.getStatus())||!Objects.equals(order.getVersion(),request.expectedVersion()))throw ApiException.conflict("OWNER_INVITATION_STATE","案件状态或版本变化，请刷新核对");
        users.lockOwnerMatching();Long target=order.getOwnerUserId();
        if(target!=null&&request.existingOwnerId()!=null&&!target.equals(request.existingOwnerId()))throw ApiException.conflict("OWNER_ALREADY_BOUND","不能转移既有案件绑定");
        if(target==null){
            if(order.getOwnerName()==null||order.getOwnerName().isBlank()||order.getOwnerPhone()==null||order.getOwnerPhone().isBlank())throw ApiException.invalid("车主姓名和联系方式未完整，请先完善并人工核实");
            var existing=db.queryForList("SELECT user_id FROM owner_profile WHERE contact_phone=? ORDER BY user_id LIMIT 2",order.getOwnerPhone());
            if(request.existingOwnerId()!=null){var candidate=users.accessById(request.existingOwnerId());if(candidate==null||!candidate.roles().equals(Set.of(Role.OWNER))||!Objects.equals(candidate.ownerPhone(),order.getOwnerPhone())||!candidate.displayName().equals(order.getOwnerName()))throw ApiException.conflict("OWNER_IDENTITY_CONFLICT","指定身份与案件资料冲突，请先核实资料");target=candidate.id();}
            else if(!existing.isEmpty())throw ApiException.conflict("OWNER_IDENTITY_CONFLICT","存在已有或多个可能车主身份，请管理员核实并明确关联，不自动合并");
            else {
                var user=new UserEntity();user.setUsername("owner_"+UUID.randomUUID().toString().replace("-",""));user.setDisplayName(order.getOwnerName());user.setPasswordHash(encoder.encode(token()));user.setEnabled(true);user.setActivated(false);user.setAuthVersion(0L);user.setCreatedAt(Instant.now());user.setUpdatedAt(Instant.now());user.setCreatedBy(actor.id());user.setUpdatedBy(actor.id());users.insert(user);users.addRole(user.getId(),"OWNER");users.ensureOwner(user.getId(),order.getOwnerName());users.ownerContact(user.getId(),order.getOwnerPhone());db.update("UPDATE owner_profile SET invitation_managed=true WHERE user_id=?",user.getId());target=user.getId();audit.record(actor,Action.USER_CREATE,"USER",target,"Fixed OWNER pending account from explicitly verified case");
            }
            if(orders.bindOwner(caseId,order.getVersion(),target,actor.id(),Instant.now())!=1)throw ApiException.conflict("OWNER_IDENTITY_CONFLICT","案件绑定已变化");
            audit.record(actor,Action.WORK_ORDER_OWNER_BIND,"WORK_ORDER",caseId,"Owner invitation explicit binding; user="+target);
        }
        var account=users.accessById(target);
        if(!account.roles().equals(Set.of(Role.OWNER)))throw ApiException.conflict("OWNER_IDENTITY_CONFLICT","绑定身份不是独立车主账号，请管理员核实");
        if(!account.enabled()||!account.bindingValid())throw ApiException.conflict("INVITATION_STATE","账号已停用或身份绑定无效，请管理员核实");
        db.update("INSERT INTO owner_activation_evidence(work_order_id,user_id,actor_id,reason) VALUES(?,?,?,?)",caseId,target,actor.id(),request.reason().trim());
        if(account.activated()){
            limits.take("issuer:"+actor.id(),20);limits.take("invite-target:"+target,3);
            audit.record(actor,Action.WORK_ORDER_OWNER_BIND,"WORK_ORDER",caseId,"Existing activated OWNER reused; no invitation or password change; user="+target);
            return new Invitation(target,account.username(),urls.base(Portal.H5)+"/login",null,true);
        }
        return issue(target);
    }
}
