package com.evinsurance.platform.integration.storage;

import com.evinsurance.platform.foundation.api.ApiException;
import io.minio.*;
import java.io.InputStream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class MinioObjectStorage implements ObjectStorageService {
    private final String endpoint, access, secret, bucket;
    private MinioClient client;
    public MinioObjectStorage(@Value("${MINIO_ENDPOINT:}") String endpoint,
        @Value("${MINIO_ACCESS_KEY:}") String access, @Value("${MINIO_SECRET_KEY:}") String secret,
        @Value("${MINIO_BUCKET:ev-insurance-private}") String bucket) {
        this.endpoint=endpoint; this.access=access; this.secret=secret; this.bucket=bucket;
    }
    private synchronized MinioClient client() throws Exception {
        if(client==null) {
            if(endpoint.isBlank()||access.isBlank()||secret.isBlank()) throw unavailable();
            var next=MinioClient.builder().endpoint(endpoint).credentials(access,secret).build();
            if(!next.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
                next.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
            }
            // Fail closed for a bucket that was externally made public. Never change its policy silently.
            try {
                String policy=next.getBucketPolicy(GetBucketPolicyArgs.builder().bucket(bucket).build());
                if(policy!=null&&!policy.isBlank()) throw unavailable();
            } catch(io.minio.errors.ErrorResponseException e) {
                if(!"NoSuchBucketPolicy".equals(e.errorResponse().code())) throw e;
            }
            client=next;
        }
        return client;
    }
    public void put(String key,InputStream source,long size,String contentType) {
        try { client().putObject(PutObjectArgs.builder().bucket(bucket).object(key).stream(source,size,-1L).contentType(contentType).build()); }
        catch(Exception e) { throw unavailable(); }
    }
    public InputStream read(String key) {
        try { return client().getObject(GetObjectArgs.builder().bucket(bucket).object(key).build()); }
        catch(Exception e) { throw unavailable(); }
    }
    public void compensate(String key) {
        try { client().removeObject(RemoveObjectArgs.builder().bucket(bucket).object(key).build()); }
        catch(Exception e) { org.slf4j.LoggerFactory.getLogger(getClass()).error("Uncommitted upload compensation failed; reconciliation required"); }
    }
    private ApiException unavailable() { return new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"STORAGE_UNAVAILABLE","Private object storage is unavailable"); }
}
