package com.ai.assistant.integration.support;

import com.ai.assistant.service.BusinessException;
import com.aliyun.sdk.service.oss2.OSSClient;
import com.aliyun.sdk.service.oss2.credentials.StaticCredentialsProvider;
import com.aliyun.sdk.service.oss2.models.GetObjectRequest;
import com.aliyun.sdk.service.oss2.models.PutObjectRequest;
import com.aliyun.sdk.service.oss2.transport.BinaryData;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class MediaStorage {
  private final String provider;
  private final Path directory;
  private final OSSClient oss;
  private final String bucket;

  public MediaStorage(SupportSettings settings) {
    provider = settings.value("integration.storage.provider", "STORAGE_PROVIDER", "local");
    if (!provider.equals("local") && !provider.equals("oss"))
      throw new IllegalStateException("STORAGE_PROVIDER must be local or oss");
    directory =
        Path.of(
                settings.value(
                    "integration.storage.local-directory", "STORAGE_LOCAL_DIRECTORY", "/app/media"))
            .toAbsolutePath()
            .normalize();
    if (provider.equals("oss")) {
      bucket = settings.required("integration.storage.oss-bucket", "OSS_BUCKET");
      String endpoint = settings.required("integration.storage.oss-endpoint", "OSS_ENDPOINT");
      var uri = java.net.URI.create(endpoint);
      if (uri.getHost() == null
          || uri.getUserInfo() != null
          || uri.getRawQuery() != null
          || uri.getRawFragment() != null
          || !(uri.getScheme().equals("https")
              || (uri.getScheme().equals("http")
                  && java.util.Set.of("localhost", "127.0.0.1").contains(uri.getHost()))))
        throw new IllegalStateException(
            "OSS_ENDPOINT must use HTTPS; HTTP is restricted to local contract servers");
      oss =
          OSSClient.newBuilder()
              .credentialsProvider(
                  new StaticCredentialsProvider(
                      settings.required(
                          "integration.storage.oss-access-key-id", "OSS_ACCESS_KEY_ID"),
                      settings.required(
                          "integration.storage.oss-access-key-secret", "OSS_ACCESS_KEY_SECRET")))
              .region(settings.required("integration.storage.oss-region", "OSS_REGION"))
              .endpoint(endpoint)
              .usePathStyle(
                  Boolean.parseBoolean(
                      settings.value(
                          "integration.storage.oss-path-style", "OSS_PATH_STYLE", "false")))
              .retryMaxAttempts(1)
              .connectTimeout(Duration.ofSeconds(3))
              .readWriteTimeout(Duration.ofSeconds(10))
              .build();
    } else {
      bucket = "";
      oss = null;
    }
  }

  public String provider() {
    return provider;
  }

  public void put(String key, ImageContent image) {
    try {
      if (oss != null) {
        oss.putObject(
            PutObjectRequest.newBuilder()
                .bucket(bucket)
                .key(key)
                .contentType(image.contentType())
                .body(BinaryData.fromBytes(image.bytes()))
                .build());
      } else {
        Files.createDirectories(directory);
        if (Files.isSymbolicLink(directory))
          throw new IOException("Storage directory must not be a symlink");
        Files.write(
            path(key), image.bytes(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
      }
    } catch (Exception e) {
      throw new BusinessException(HttpStatus.BAD_GATEWAY, "MEDIA_WRITE_FAILED", "图片存储失败，请稍后重试");
    }
  }

  public byte[] get(String storedProvider, String key) {
    if (!storedProvider.equals("local") && !storedProvider.equals("oss"))
      throw new IllegalStateException("Unknown stored media provider");
    if (storedProvider.equals("oss") && oss == null)
      throw new BusinessException(
          HttpStatus.SERVICE_UNAVAILABLE, "MEDIA_PROVIDER_CHANGED", "该图片的OSS存储源尚未配置");
    try {
      if (storedProvider.equals("oss")) {
        try (var result =
            oss.getObject(GetObjectRequest.newBuilder().bucket(bucket).key(key).build())) {
          byte[] data = result.body().readNBytes(ImageContent.MAX_BYTES + 1);
          if (data.length > ImageContent.MAX_BYTES)
            throw new IOException("Stored image exceeds limit");
          return data;
        }
      }
      Path path = path(key);
      if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
        throw new IOException("Image not found");
      try (var stream = Files.newInputStream(path)) {
        byte[] data = stream.readNBytes(ImageContent.MAX_BYTES + 1);
        if (data.length > ImageContent.MAX_BYTES)
          throw new IOException("Stored image exceeds limit");
        return data;
      }
    } catch (Exception e) {
      throw new BusinessException(HttpStatus.BAD_GATEWAY, "MEDIA_READ_FAILED", "图片暂时无法读取");
    }
  }

  private Path path(String key) {
    if (!key.matches("[a-f0-9-]{36}\\.(jpg|png)")) throw new IllegalArgumentException("存储对象标识无效");
    return directory.resolve(key);
  }

  @PreDestroy
  public void close() throws Exception {
    if (oss != null) oss.close();
  }
}
