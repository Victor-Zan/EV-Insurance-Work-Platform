package com.evinsurance.platform.pricing.infrastructure;
import java.util.UUID;
import java.sql.*;
import org.apache.ibatis.type.*;
@MappedTypes(UUID.class)
@MappedJdbcTypes(value=JdbcType.OTHER,includeNullJdbcType=true)
public class UuidTypeHandler extends BaseTypeHandler<UUID> {
 @Override public void setNonNullParameter(PreparedStatement statement,int index,UUID value,JdbcType jdbcType)throws SQLException{statement.setObject(index,value);}
 @Override public UUID getNullableResult(ResultSet result,String name)throws SQLException{return result.getObject(name,UUID.class);}
 @Override public UUID getNullableResult(ResultSet result,int index)throws SQLException{return result.getObject(index,UUID.class);}
 @Override public UUID getNullableResult(CallableStatement statement,int index)throws SQLException{Object value=statement.getObject(index);return value==null?null:(value instanceof UUID id?id:UUID.fromString(value.toString()));}
}
