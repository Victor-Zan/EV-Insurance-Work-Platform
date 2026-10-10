package com.evinsurance.platform.notification.application;
import com.evinsurance.platform.notification.infrastructure.NotificationMapper;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
@Service
public class NotificationProjector {
    private final NotificationMapper mapper;
    public NotificationProjector(NotificationMapper mapper){this.mapper=mapper;}
    @Transactional public void project(){
        for(var event:mapper.claim()){
            long id=((Number)event.get("audit_id")).longValue();
            for(var recipient:mapper.recipients((UUID)event.get("work_order_id"),(String)event.get("kind")))mapper.deliver(id,((Number)recipient.get("id")).longValue(),(String)recipient.get("code"));
            mapper.projected(id);
        }
    }
}
