package com.evinsurance.platform.audit.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AuditMapper extends BaseMapper<AuditEntity> {
}
