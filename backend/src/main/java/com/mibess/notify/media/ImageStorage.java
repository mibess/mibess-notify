package com.mibess.notify.media;

import jakarta.annotation.PreDestroy;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

@Service
public class ImageStorage {

  private final String bucket;
  private final S3Client s3;
  private final S3Presigner signer;

  public ImageStorage(
    @Value("${notify.images.bucket:}") String bucket,
    @Value("${notify.images.region:us-east-1}") String region
  ) {
    this.bucket = bucket;
    this.s3 = bucket.isBlank()
      ? null
      : S3Client.builder()
          .region(Region.of(region))
          .overrideConfiguration(c ->
            c
              .apiCallTimeout(Duration.ofSeconds(30))
              .apiCallAttemptTimeout(Duration.ofSeconds(10))
          )
          .build();
    this.signer = bucket.isBlank()
      ? null
      : S3Presigner.builder().region(Region.of(region)).build();
  }

  public boolean configured() {
    return s3 != null;
  }

  private void requireConfigured() {
    if (!configured()) throw new ResponseStatusException(
      HttpStatus.SERVICE_UNAVAILABLE,
      "Configure NOTIFY_IMAGES_BUCKET e AWS_REGION no servidor para habilitar imagens."
    );
  }

  public void put(String key, byte[] bytes, String type) {
    requireConfigured();
    try {
      s3.putObject(
        PutObjectRequest.builder()
          .bucket(bucket)
          .key(key)
          .contentType(type)
          .serverSideEncryption(ServerSideEncryption.AES256)
          .build(),
        RequestBody.fromBytes(bytes)
      );
    } catch (software.amazon.awssdk.core.exception.SdkException e) {
      throw new ResponseStatusException(
        HttpStatus.SERVICE_UNAVAILABLE,
        "Não foi possível armazenar a imagem no S3."
      );
    }
  }

  public byte[] read(String key) {
    requireConfigured();
    try {
      return s3
        .getObjectAsBytes(
          GetObjectRequest.builder().bucket(bucket).key(key).build()
        )
        .asByteArray();
    } catch (software.amazon.awssdk.core.exception.SdkException e) {
      throw new ResponseStatusException(
        HttpStatus.SERVICE_UNAVAILABLE,
        "Não foi possível carregar a imagem do S3."
      );
    }
  }

  public String deliveryUrl(String key) {
    requireConfigured();
    return signer
      .presignGetObject(r ->
        r
          .signatureDuration(Duration.ofHours(1))
          .getObjectRequest(b -> b.bucket(bucket).key(key))
      )
      .url()
      .toExternalForm();
  }

  @PreDestroy
  public void close() {
    if (s3 != null) s3.close();
    if (signer != null) signer.close();
  }
}
