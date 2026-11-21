package com.evinsurance.platform.identity.application;
import com.evinsurance.platform.audit.application.AuditService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
@Service
public class AccountFailureAudit {
    private final AuditService audit;public AccountFailureAudit(AuditService audit){this.audit=audit;}
    @Transactional(propagation=Propagation.REQUIRES_NEW) public void rejected(){audit.recordIdentifier(null,AuditService.Action.ACCOUNT_ACTIVATION_FAILURE,"ACCOUNT_ACTIVATION",null,"Activation credential rejected; no account details");}
    @Transactional(propagation=Propagation.REQUIRES_NEW) public void wechatRejected(){audit.recordIdentifier(null,AuditService.Action.WECHAT_AUTH_FAILURE,"WECHAT_AUTH",null,"Wechat authentication rejected; credentials and identity omitted");}
}
