package com.campus.storage.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.campus.common.error.BadRequestException;
import com.campus.common.error.NotFoundException;
import java.io.ByteArrayInputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

// Mockito instead of a real bucket: the test checks what we ask S3 to do, without AWS.
@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class S3StorageServiceTest {

    private static final byte[] PNG = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00};
    private static final String KEY = "123e4567-e89b-12d3-a456-426614174000.png";

    @Mock
    private S3Client s3;

    private S3StorageService storage;

    @BeforeEach
    void setUp() {
        storage = new S3StorageService(s3, "campus-photos");
    }

    @Test
    void storePutsThePhotoInTheBucketUnderANewKey() {
        String key = storage.store(new ByteArrayInputStream(PNG), PNG.length, "image/png", null);

        ArgumentCaptor<PutObjectRequest> put = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3).putObject(put.capture(), any(RequestBody.class));
        assertThat(key).matches("[0-9a-f-]{36}\\.png");
        assertThat(put.getValue().bucket()).isEqualTo("campus-photos");
        assertThat(put.getValue().key()).isEqualTo(key);
        assertThat(put.getValue().contentType()).isEqualTo("image/png");
    }

    @Test
    void storeRefusesAFakeImageWithoutCallingS3() {
        byte[] html = "<script>alert(1)</script>".getBytes();

        assertThatThrownBy(() -> storage.store(new ByteArrayInputStream(html), html.length, "image/png", null))
                .isInstanceOf(BadRequestException.class);
        verifyNoInteractions(s3);
    }

    @Test
    void loadReturnsTheBytesFromS3() throws Exception {
        when(s3.getObjectAsBytes(any(GetObjectRequest.class)))
                .thenReturn(
                        ResponseBytes.fromByteArray(GetObjectResponse.builder().build(), PNG));

        assertThat(storage.load(KEY).getContentAsByteArray()).isEqualTo(PNG);
    }

    @Test
    void loadOfAMissingKeyIs404() {
        when(s3.getObjectAsBytes(any(GetObjectRequest.class)))
                .thenThrow(NoSuchKeyException.builder().build());

        assertThatThrownBy(() -> storage.load(KEY))
                .isInstanceOf(NotFoundException.class)
                .extracting("code")
                .isEqualTo("PHOTO_NOT_FOUND");
    }

    // #148: without s3:ListBucket, S3 answers 403 instead of 404 for a missing key. That is a setup
    // error on our side, not a missing photo, so it stays an error and the log names the fix.
    @Test
    void loadDeniedByS3IsRethrownAndLogsTheMissingPermission(CapturedOutput output) {
        S3Exception denied = (S3Exception)
                S3Exception.builder().statusCode(403).message("Access Denied").build();
        when(s3.getObjectAsBytes(any(GetObjectRequest.class))).thenThrow(denied);

        assertThatThrownBy(() -> storage.load(KEY)).isSameAs(denied);
        assertThat(output).contains("s3:ListBucket");
    }

    @Test
    void deleteRefusesATraversalKeyWithoutCallingS3() {
        assertThatThrownBy(() -> storage.delete("../other-bucket/secret.png")).isInstanceOf(NotFoundException.class);
        verifyNoInteractions(s3);
    }

    @Test
    void deleteRemovesTheObject() {
        storage.delete(KEY);

        verify(s3)
                .deleteObject(DeleteObjectRequest.builder()
                        .bucket("campus-photos")
                        .key(KEY)
                        .build());
    }
}
