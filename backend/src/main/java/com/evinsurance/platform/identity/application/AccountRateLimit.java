package com.evinsurance.platform.identity.application;
import com.evinsurance.platform.document.application.FileService;
import com.evinsurance.platform.foundation.api.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
@Service
public class AccountRateLimit {
    private final JdbcTemplate db;
    public AccountRateLimit(JdbcTemplate db){this.db=db;}
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void take(String bucket,int maximum){
        String hash=FileService.hash(bucket.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        int count=db.queryForObject("INSERT INTO account_rate_limit(bucket_hash,window_started,requests) VALUES(?,now(),1) ON CONFLICT(bucket_hash) DO UPDATE SET requests=CASE WHEN account_rate_limit.window_started<=now()-interval '10 minutes' THEN 1 ELSE account_rate_limit.requests+1 END,window_started=CASE WHEN account_rate_limit.window_started<=now()-interval '10 minutes' THEN now() ELSE account_rate_limit.window_started END RETURNING requests",Integer.class,hash);
        if(count>maximum)throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,"ACCOUNT_RATE_LIMIT","操作过于频繁，请10分钟后重试");
        db.update("DELETE FROM account_rate_limit WHERE bucket_hash IN (SELECT bucket_hash FROM account_rate_limit WHERE window_started<now()-interval '1 day' ORDER BY window_started LIMIT 100)");
    }
}
