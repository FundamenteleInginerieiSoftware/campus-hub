package com.campus.storage.service;

import com.campus.common.error.NotFoundException;
import com.campus.storage.api.StorageService;
import com.campus.storage.service.StorageValidation.ValidatedPhoto;
import java.io.InputStream;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * Incident photos in the private S3 bucket, in prod. Same contract as Liviu's {@code
 * LocalStorageService} (dev/test), so Stefania's incident code works the same with both: it only
 * sees {@link StorageService}.
 */
@Slf4j
@Service
@Profile("prod")
public class S3StorageService implements StorageService {

    private final S3Client s3;
    private final String bucket;

    public S3StorageService(S3Client s3, @Value("${app.storage.photo-bucket}") String bucket) {
        this.s3 = s3;
        this.bucket = bucket;
    }

    // Liviu's StorageValidation does the same checks as on a laptop (type, size, magic bytes), so a
    // file that is refused locally is also refused on AWS.
    @Override
    public String store(InputStream in, long size, String contentType, String ext) {
        ValidatedPhoto photo = StorageValidation.validateAndRead(in, size, contentType, ext);
        String key = UUID.randomUUID() + "." + photo.canonicalExtension();
        PutObjectRequest put = PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .contentType(photo.contentType())
                .build();
        s3.putObject(put, RequestBody.fromBytes(photo.bytes()));
        log.info("Stored S3 photo: key={}, size={}", key, photo.bytes().length);
        return key;
    }

    // At most 5 MB per photo, so reading it into memory is fine.
    @Override
    public Resource load(String key) {
        StorageValidation.requireValidKeyFormat(key);
        try {
            GetObjectRequest get =
                    GetObjectRequest.builder().bucket(bucket).key(key).build();
            byte[] bytes = s3.getObjectAsBytes(get).asByteArray();
            return new ByteArrayResource(bytes);
        } catch (NoSuchKeyException e) {
            throw new NotFoundException("PHOTO_NOT_FOUND", "Photo was not found");
        } catch (S3Exception e) {
            // Bug fixed (#148): S3 only says "404 NoSuchKey" to a role that may list the bucket
            // (s3:ListBucket); without it, a missing key comes back as 403 AccessDenied, so the user got
            // a 500 with no hint why. The real fix is that permission on the instance role (Terraform,
            // doc 29). We don't turn the 403 into a 404: it can also mean a wrong bucket or role, and
            // hiding it would make those invisible. It stays a 500, but the log now names the likely cause.
            if (e.statusCode() == 403) {
                log.error(
                        "S3 denied GetObject for key={} in bucket={}. If the photo is missing, the instance role"
                                + " probably lacks s3:ListBucket on the bucket (S3 then answers 403 instead of 404)",
                        key,
                        bucket);
            }
            throw e;
        }
    }

    // S3 answers "OK" also when the key doesn't exist, which matches the contract (no-op).
    @Override
    public void delete(String key) {
        StorageValidation.requireValidKeyFormat(key);
        s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
        log.info("Deleted S3 photo: key={}", key);
    }
}
