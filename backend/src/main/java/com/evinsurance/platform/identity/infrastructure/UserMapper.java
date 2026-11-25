package com.evinsurance.platform.identity.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface UserMapper extends BaseMapper<UserEntity> {
    AccessRow accessById(@Param("id") long id);
    AccessRow accessByUsername(@Param("username") String username);
    List<AccessRow> accessPage(@Param("offset") int offset, @Param("size") int size);
    void replaceRolesDelete(@Param("id") long id);
    void addRole(@Param("id") long id, @Param("role") String role);
    void deleteShopAccount(@Param("id") long id);
    void addShopAccount(@Param("id") long id, @Param("shopId") long shopId, @Param("actorId") long actorId);
    void ensureOwner(@Param("id") long id, @Param("displayName") String displayName);
    @org.apache.ibatis.annotations.Update("UPDATE app_user SET activated=false WHERE id=#{id}") void markPending(@Param("id") long id);
    @org.apache.ibatis.annotations.Select("SELECT id FROM work_order_config WHERE id=1 FOR UPDATE") int lockOwnerMatching();
    @org.apache.ibatis.annotations.Update("UPDATE owner_profile SET contact_phone=#{phone},updated_at=now() WHERE user_id=#{id}") int ownerContact(@Param("id") long id,@Param("phone") String phone);
    @org.apache.ibatis.annotations.Insert("INSERT INTO owner_contact_event(user_id,reason,actor_id) VALUES(#{id},#{reason},#{actor})") void contactEvent(@Param("id") long id,@Param("reason") String reason,@Param("actor") long actor);
    UserEntity lockById(@Param("id") long id);
    @org.apache.ibatis.annotations.Update("UPDATE app_user SET auth_version=auth_version+1,updated_at=now() WHERE id=#{id}")
    int invalidateSessions(@Param("id") long id);
    @org.apache.ibatis.annotations.Update("UPDATE app_user SET activated=true,password_hash=#{hash},auth_version=auth_version+1,updated_at=now() WHERE id=#{id} AND enabled AND NOT activated AND auth_version=#{version}")
    int activateInvited(@Param("id") long id,@Param("hash") String hash,@Param("version") long version);
}
