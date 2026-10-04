package com.evinsurance.platform.audit.application;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evinsurance.platform.audit.infrastructure.*;
import com.evinsurance.platform.foundation.api.PageResponse;
import com.evinsurance.platform.foundation.web.TraceContext;
import com.evinsurance.platform.identity.domain.CurrentUser;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuditService {
    private final AuditMapper mapper;
    public AuditService(AuditMapper mapper) { this.mapper = mapper; }
    public enum Action { LOGIN, LOGIN_FAILURE, USER_CREATE, USER_ENABLE, USER_DISABLE, PASSWORD_RESET,
        ROLE_CHANGE, SHOP_ACCOUNT_CHANGE, REGION_CREATE, REGION_UPDATE, SHOP_CREATE, SHOP_UPDATE, SERVICE_REGION_CHANGE,
        PRICE_CATALOGUE_CREATE, PRICE_CATALOGUE_UPDATE, PRICE_CATALOGUE_DELETE, PRICE_APPLICABILITY_CHANGE,
        PRICE_CREATE, PRICE_VERSION_CREATE, PRICE_VERSION_CLOSE, PRICE_IMPORT_SUCCESS, PRICE_IMPORT_FAILURE }
    // Callers only supply constructed summaries of IDs, roles and state; never request DTOs or credentials.
    @Transactional
    public void record(CurrentUser actor, Action action, String type, Long id, String summary) {
        var entry = new AuditEntity();
        entry.setActorId(actor == null ? null : actor.id());
        entry.setActorRoles(actor == null ? "ANONYMOUS" : actor.roles().stream().map(Enum::name).sorted().collect(java.util.stream.Collectors.joining(",")));
        entry.setAction(action.name()); entry.setObjectType(type); entry.setObjectId(id == null ? null : id.toString());
        entry.setSummary(summary);
        String trace = TraceContext.currentTraceId();
        entry.setTraceId(trace == null ? java.util.UUID.randomUUID().toString() : trace);
        entry.setOccurredAt(Instant.now()); mapper.insert(entry);
    }
    public PageResponse<AuditEntity> list(int page, int size) {
        CurrentUser.require().requireAdmin();
        int offset = PageResponse.offset(page, size);
        return new PageResponse<>(page,size,mapper.selectCount(null),mapper.selectList(new QueryWrapper<AuditEntity>()
            .orderByDesc("occurred_at","id").last("LIMIT " + size + " OFFSET " + offset)));
    }
}
