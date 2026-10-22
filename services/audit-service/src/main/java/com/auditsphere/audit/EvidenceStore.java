package com.auditsphere.audit;

import jakarta.annotation.PreDestroy;
import java.io.InputStream;
import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

@Component
class EvidenceStore {
  private final S3Client client;
  private final String bucket;

  EvidenceStore(
      @Value("${evidence.s3.endpoint:}") String endpoint,
      @Value("${evidence.s3.region:us-east-1}") String region,
      @Value("${evidence.s3.bucket:}") String bucket,
      @Value("${evidence.s3.access-key:}") String accessKey,
      @Value("${evidence.s3.secret-key:}") String secretKey,
      @Value("${evidence.s3.path-style:true}") boolean pathStyle) {
    this.bucket = bucket;
    this.client =
        endpoint.isBlank() || bucket.isBlank() || accessKey.isBlank() || secretKey.isBlank()
            ? null
            : S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.of(region))
                .credentialsProvider(
                    StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKey, secretKey)))
                .overrideConfiguration(
                    c ->
                        c.apiCallTimeout(java.time.Duration.ofSeconds(25))
                            .apiCallAttemptTimeout(java.time.Duration.ofSeconds(15)))
                .requestChecksumCalculation(
                    software.amazon.awssdk.core.checksums.RequestChecksumCalculation.WHEN_REQUIRED)
                .forcePathStyle(pathStyle)
                .build();
  }

  private S3Client ready() {
    if (client == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
    return client;
  }

  void put(String key, byte[] bytes, String mediaType) {
    ready()
        .putObject(
            PutObjectRequest.builder().bucket(bucket).key(key).contentType(mediaType).build(),
            RequestBody.fromBytes(bytes));
  }

  InputStream get(String key) {
    return ready().getObject(GetObjectRequest.builder().bucket(bucket).key(key).build());
  }

  void delete(String key) {
    ready().deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
  }

  @PreDestroy
  void close() {
    if (client != null) client.close();
  }
}
