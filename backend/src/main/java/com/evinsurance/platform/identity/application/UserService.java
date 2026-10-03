package com.evinsurance.platform.identity.application;

import com.evinsurance.platform.identity.api.*;
import com.evinsurance.platform.identity.domain.*;
import com.evinsurance.platform.identity.infrastructure.*;
import com.evinsurance.platform.organization.infrastructure.ShopMapper;
import com.evinsurance.platform.audit.application.AuditService;
import com.evinsurance.platform.audit.application.AuditService.Action;
import com.evinsurance.platform.foundation.api.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Set;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {
    private final UserMapper users;
    private final ShopMapper shops;
    private final PasswordEncoder encoder;
    private final AuditService audit;
    public UserService(UserMapper users, ShopMapper shops, PasswordEncoder encoder, AuditService audit) {
        this.users=users; this.shops=shops; this.encoder=encoder; this.audit=audit;
    }
    public PageResponse<UserView> list(int page,int size) {
        CurrentUser.require().requireAdmin(); int offset=PageResponse.offset(page,size);
        return new PageResponse<>(page,size,users.selectCount(null),users.accessPage(offset,size).stream().map(UserView::from).toList());
    }
    @Transactional
    public UserView create(IdentityRequests.CreateUser request) {
        var actor=CurrentUser.require(); actor.requireAdmin();
        validateAssignment(request.roles(),request.shopId());
        var user=new UserEntity(); user.setUsername(request.username()); user.setDisplayName(request.displayName());
        user.setPasswordHash(hash(request.password())); user.setEnabled(true); user.setAuthVersion(0L);
        user.setCreatedAt(Instant.now()); user.setUpdatedAt(user.getCreatedAt()); user.setCreatedBy(actor.id()); user.setUpdatedBy(actor.id());
        users.insert(user);
        assign(user,request.roles(),request.shopId(),actor.id());
        audit.record(actor,Action.USER_CREATE,"USER",user.getId(),"Enabled: true; roles: " + roleNames(request.roles()));
        audit.record(actor,Action.ROLE_CHANGE,"USER",user.getId(),"Roles: [] -> " + roleNames(request.roles()));
        if (request.shopId()!=null) audit.record(actor,Action.SHOP_ACCOUNT_CHANGE,"USER",user.getId(),"Shop: null -> " + request.shopId());
        return UserView.from(users.accessById(user.getId()));
    }
    @Transactional
    public void setEnabled(long id,boolean enabled) {
        var actor=CurrentUser.require(); actor.requireAdmin(); var user=lock(id);
        boolean before=user.getEnabled(); user.setEnabled(enabled); touch(user,actor.id()); users.updateById(user);
        audit.record(actor,enabled ? Action.USER_ENABLE : Action.USER_DISABLE,"USER",id,"Enabled: " + before + " -> " + enabled);
    }
    @Transactional
    public void resetPassword(long id,String password) {
        var actor=CurrentUser.require(); actor.requireAdmin(); var user=lock(id);
        user.setPasswordHash(hash(password)); touch(user,actor.id()); users.updateById(user);
        audit.record(actor,Action.PASSWORD_RESET,"USER",id,"Password reset; existing sessions invalidated");
    }
    @Transactional
    public UserView assignment(long id,IdentityRequests.Assignment request) {
        var actor=CurrentUser.require(); actor.requireAdmin(); var user=lock(id);
        var before=users.accessById(id); validateAssignment(request.roles(),request.shopId());
        assign(user,request.roles(),request.shopId(),actor.id()); touch(user,actor.id()); users.updateById(user);
        audit.record(actor,Action.ROLE_CHANGE,"USER",id,"Roles: " + roleNames(before.roles()) + " -> " + roleNames(request.roles()));
        if (!java.util.Objects.equals(before.shopId(),request.shopId())) {
            audit.record(actor,Action.SHOP_ACCOUNT_CHANGE,"USER",id,"Shop: " + before.shopId() + " -> " + request.shopId());
        }
        return UserView.from(users.accessById(id));
    }
    private void assign(UserEntity user,Set<Role> roles,Long shopId,long actorId) {
        users.replaceRolesDelete(user.getId());
        roles.forEach(role -> users.addRole(user.getId(),role.name()));
        users.deleteShopAccount(user.getId());
        if (shopId!=null) users.addShopAccount(user.getId(),shopId,actorId);
        if (roles.contains(Role.OWNER)) users.ensureOwner(user.getId(),user.getDisplayName());
    }
    private void validateAssignment(Set<Role> roles,Long shopId) {
        if (roles == null || roles.isEmpty() || roles.stream().anyMatch(java.util.Objects::isNull)) throw ApiException.invalid("At least one valid role is required");
        if (!Portal.ADMIN.allows(roles) && !Portal.H5.allows(roles)) throw ApiException.invalid("Management and H5 roles cannot be combined on an account");
        if (roles.contains(Role.REPAIR_SHOP) != (shopId != null)) throw ApiException.invalid("REPAIR_SHOP requires exactly one shop; other accounts cannot have a shop");
        if (shopId != null && shops.selectById(shopId) == null) throw ApiException.missing("Shop");
    }
    private UserEntity lock(long id) { var user=users.lockById(id); if (user==null) throw ApiException.missing("User"); return user; }
    private void touch(UserEntity user,long actorId) {
        user.setAuthVersion(user.getAuthVersion()+1); user.setUpdatedAt(Instant.now()); user.setUpdatedBy(actorId);
    }
    private String hash(String password) {
        if (password==null || password.isBlank() || password.getBytes(StandardCharsets.UTF_8).length>72) throw ApiException.invalid("Password must be nonempty and fit BCrypt (72 UTF-8 bytes)");
        return encoder.encode(password);
    }
    private String roleNames(Set<Role> roles) { return roles.stream().map(Enum::name).sorted().toList().toString(); }
}
