package com.ai.assistant.integration.support;

import com.ai.assistant.service.BusinessException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import javax.imageio.ImageIO;
import org.springframework.http.HttpStatus;

public record ImageContent(byte[] bytes, String contentType, String extension, String sha256) {
  public static final int MAX_BYTES = 5 * 1024 * 1024;

  public static ImageContent validate(byte[] bytes) {
    if (bytes.length == 0 || bytes.length > MAX_BYTES)
      throw new BusinessException(
          HttpStatus.CONTENT_TOO_LARGE, "IMAGE_SIZE_INVALID", "图片须大于0且不超过5MiB");
    try (var stream = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
      var readers = ImageIO.getImageReaders(stream);
      if (!readers.hasNext()) throw new IllegalArgumentException("仅支持真实的JPEG或PNG图片");
      var reader = readers.next();
      try {
        String format = reader.getFormatName();
        if (!format.equalsIgnoreCase("JPEG") && !format.equalsIgnoreCase("PNG"))
          throw new IllegalArgumentException("仅支持JPEG或PNG图片");
        reader.setInput(stream, true, true);
        int width = reader.getWidth(0), height = reader.getHeight(0);
        if (width < 1 || height < 1 || width > 4096 || height > 4096)
          throw new IllegalArgumentException("图片尺寸须在4096×4096以内");
        if (reader.read(0) == null) throw new IllegalArgumentException("图片内容损坏");
        boolean png = format.equalsIgnoreCase("PNG");
        return new ImageContent(
            bytes,
            png ? "image/png" : "image/jpeg",
            png ? ".png" : ".jpg",
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
      } finally {
        reader.dispose();
      }
    } catch (IOException e) {
      throw new IllegalArgumentException("图片内容损坏");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
