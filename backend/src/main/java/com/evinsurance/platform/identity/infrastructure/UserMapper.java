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
    UserEntity lockById(@Param("id") long id);
}
