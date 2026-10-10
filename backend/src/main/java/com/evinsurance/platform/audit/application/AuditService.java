package com.evinsurance.platform.audit.application;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evinsurance.platform.audit.infrastructure.*;
import com.evinsurance.platform.foundation.api.PageResponse;
import com.evinsurance.platform.foundation.web.TraceContext;
import com.evinsurance.platform.identity.domain.CurrentUser;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuditService {
    private final AuditMapper mapper;
    public AuditService(AuditMapper mapper) { this.mapper = mapper; }
    public enum Action { LOGIN, LOGIN_FAILURE, USER_CREATE, USER_ENABLE, USER_DISABLE, PASSWORD_RESET,
        ROLE_CHANGE, SHOP_ACCOUNT_CHANGE, REGION_CREATE, REGION_UPDATE, SHOP_CREATE, SHOP_UPDATE, SERVICE_REGION_CHANGE,
        PRICE_CATALOGUE_CREATE, PRICE_CATALOGUE_UPDATE, PRICE_CATALOGUE_DELETE, PRICE_APPLICABILITY_CHANGE,
        PRICE_CREATE, PRICE_VERSION_CREATE, PRICE_VERSION_CLOSE, PRICE_IMPORT_PREVIEW, PRICE_IMPORT_SUCCESS, PRICE_IMPORT_FAILURE,
        WORK_ORDER_DRAFT_CREATE, WORK_ORDER_DRAFT_UPDATE, WORK_ORDER_DRAFT_DELETE, WORK_ORDER_SUBMIT,
        WORK_ORDER_CRITICAL_UPDATE, WORK_ORDER_CONFIG_UPDATE, WORK_ORDER_DISPATCH, WORK_ORDER_ACCEPT,
        WORK_ORDER_REJECT, WORK_ORDER_ASSIGNMENT_CANCEL, WORK_ORDER_REASSIGN,
        WORK_ORDER_ARRIVAL_EXCEPTION, WORK_ORDER_CONTINUE_WAITING, WORK_ORDER_ARRIVE, WORK_ORDER_CANCEL,
        FILE_UPLOAD, FILE_REPLACE, FILE_VOID, FILE_DOWNLOAD, MATERIAL_MISSING,
        QUOTE_RAW_SUBMIT, QUOTE_FORMAL_ISSUE, QUOTE_ASSESSMENT_RECORD, QUOTE_INSURER_CONFIRM, QUOTE_SERVICE_CONFIRM,
        QUOTE_CONFIRMATIONS_INVALIDATED, REPAIR_AUTHORIZATION_GRANTED, REPAIR_AUTHORIZATION_REVOKED, REPAIR_START, QUOTE_EXTERNAL_EXPORT,
        REPAIR_PROGRESS, REPAIR_COMPLETE, REPAIR_RECEIPT_CONFIRM, REPAIR_RECEIPT_WITHDRAW, REPAIR_REVIEW, REPAIR_REVIEW_CORRECT, COMPLAINT_CREATE, COMPLAINT_HANDLE, COMPLAINT_CORRECT, FUNDS_TARGET, FUNDS_ENTRY, FUNDS_REVERSE, FUNDS_EXPORT, FUNDS_IMPORT_PREVIEW, FUNDS_IMPORT_RESOLVE, FUNDS_IMPORT_CONFIRM, FUNDS_IMPORT_DOWNLOAD, NOTIFICATION_READ, TODO_DEADLINE, OCR_CREATE, OCR_RETRY, OCR_REVIEW, OCR_CONFIRM, OCR_SUCCEEDED, OCR_FAILED }
    // Callers only supply constructed summaries of IDs, roles and state; never request DTOs or credentials.
    @Transactional
    public void record(CurrentUser actor, Action action, String type, Long id, String summary) {
        recordIdentifier(actor,action,type,id == null ? null : id.toString(),summary);
    }
    @Transactional
    public void record(CurrentUser actor, Action action, String type, UUID id, String summary) {
        recordIdentifier(actor,action,type,id == null ? null : id.toString(),summary);
    }
    @Transactional
    public void recordIdentifier(CurrentUser actor, Action action, String type, String id, String summary) {
        var entry = new AuditEntity();
        entry.setActorId(actor == null ? null : actor.id());
        entry.setActorRoles(actor == null ? "ANONYMOUS" : actor.roles().stream().map(Enum::name).sorted().collect(java.util.stream.Collectors.joining(",")));
        entry.setAction(action.name()); entry.setObjectType(type); entry.setObjectId(id);
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
