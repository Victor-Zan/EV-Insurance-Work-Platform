package com.evinsurance.platform.identity.infrastructure;

import com.evinsurance.platform.foundation.api.PageResponse;
import com.evinsurance.platform.identity.api.WechatViews;
import com.evinsurance.platform.identity.domain.Role;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class WechatBindingStore {
    public record Binding(UUID id, String appId, long userId, Role role, Long shopId, int version,
                          Instant createdAt, Instant revokedAt) {
        public boolean matches(AccessRow row) {
            return revokedAt==null && userId==row.id() && row.roles().equals(Set.of(role)) && Objects.equals(shopId,row.shopId());
        }
    }
    private final JdbcTemplate db;
    private static final String COLUMNS="id,app_id,user_id,role,shop_id,version,created_at,revoked_at";
    public WechatBindingStore(JdbcTemplate db) { this.db=db; }
    private Binding read(ResultSet row,int index) throws SQLException {
        var revoked=row.getTimestamp("revoked_at");
        return new Binding(row.getObject("id",UUID.class),row.getString("app_id"),row.getLong("user_id"),
            Role.valueOf(row.getString("role")),row.getObject("shop_id",Long.class),row.getInt("version"),
            row.getTimestamp("created_at").toInstant(),revoked==null?null:revoked.toInstant());
    }
    public Binding byIdentity(String appId,String openid) {
        var rows=db.query("SELECT "+COLUMNS+" FROM wechat_identity_binding WHERE app_id=? AND openid=? AND revoked_at IS NULL",this::read,appId,openid);
        return rows.isEmpty()?null:rows.getFirst();
    }
    public void lockIdentity(String appId,String openid) {
        db.queryForObject("SELECT 1 FROM pg_advisory_xact_lock(hashtextextended(?,0))",Integer.class,"wechat-login:"+appId+":"+openid);
    }
    public boolean hasIdentityHistory(String appId,String openid) {
        return Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM wechat_identity_binding WHERE app_id=? AND openid=?)",Boolean.class,appId,openid));
    }
    /** Called under the user lock in the administrator's assignment transaction. Historical snapshots remain immutable. */
    public int synchronizeAssignment(AccessRow user,long actorId) {
        var rows=db.queryForList("SELECT id,app_id,openid,role,shop_id,version FROM wechat_identity_binding WHERE user_id=? AND revoked_at IS NULL ORDER BY id FOR UPDATE",user.id());
        boolean mobile=user.roles().equals(Set.of(Role.OWNER))||user.roles().equals(Set.of(Role.REPAIR_SHOP));
        int changed=0;
        for(var row:rows) {
            var previousShop=(Number)row.get("shop_id");
            Long shopId=previousShop==null?null:previousShop.longValue();
            if(user.roles().equals(Set.of(Role.valueOf((String)row.get("role"))))&&Objects.equals(shopId,user.shopId()))continue;
            if(!revoke((UUID)row.get("id"),user.id(),((Number)row.get("version")).intValue(),actorId,"Administrator changed account role or shop"))
                throw com.evinsurance.platform.foundation.api.ApiException.conflict("WECHAT_BINDING_VERSION_CONFLICT","微信身份已变化，请刷新后重试");
            if(mobile)insert((String)row.get("app_id"),(String)row.get("openid"),user);
            changed++;
        }
        return changed;
    }
    public Binding activeForUser(String appId,long userId) {
        var rows=db.query("SELECT "+COLUMNS+" FROM wechat_identity_binding WHERE app_id=? AND user_id=? AND revoked_at IS NULL",this::read,appId,userId);
        return rows.isEmpty()?null:rows.getFirst();
    }
    public Binding byId(UUID id) {
        var rows=db.query("SELECT "+COLUMNS+" FROM wechat_identity_binding WHERE id=?",this::read,id);
        return rows.isEmpty()?null:rows.getFirst();
    }
    public Binding insert(String appId,String openid,AccessRow user) {
        UUID id=UUID.randomUUID();
        db.update("INSERT INTO wechat_identity_binding(id,app_id,openid,user_id,role,shop_id) VALUES(?,?,?,?,?,?)",
            id,appId,openid,user.id(),user.roles().iterator().next().name(),user.shopId());
        return byId(id);
    }
    public boolean validForSession(UUID id,String appId,AccessRow row) {
        var binding=byId(id);
        return binding!=null && Objects.equals(appId,binding.appId()) && binding.matches(row);
    }
    public boolean revoke(UUID id,long userId,int expected,long actorId,String reason) {
        return db.update("UPDATE wechat_identity_binding SET revoked_at=now(),revoked_by=?,revoke_reason=?,version=version+1 WHERE id=? AND user_id=? AND version=? AND revoked_at IS NULL",
            actorId,reason,id,userId,expected)==1;
    }
    public PageResponse<WechatViews.Binding> page(long userId,int page,int size) {
        int offset=PageResponse.offset(page,size);
        long total=db.queryForObject("SELECT count(*) FROM wechat_identity_binding WHERE user_id=?",Long.class,userId);
        var records=db.query("SELECT "+COLUMNS+" FROM wechat_identity_binding WHERE user_id=? ORDER BY created_at DESC,id LIMIT ? OFFSET ?",this::read,userId,size,offset)
            .stream().map(row->new WechatViews.Binding(row.id(),row.version(),row.role(),row.shopId(),
                row.revokedAt()==null?"ACTIVE":"REVOKED",row.createdAt(),row.revokedAt())).toList();
        return new PageResponse<>(page,size,total,records);
    }
}
