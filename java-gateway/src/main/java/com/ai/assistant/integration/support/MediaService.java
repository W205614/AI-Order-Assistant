package com.ai.assistant.integration.support;

import com.ai.assistant.security.UserContext;
import com.ai.assistant.service.AuditService;
import com.ai.assistant.service.BusinessException;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

@Service
public class MediaService {
  public record Asset(String id, String url, String purpose, String resourceId) {}

  public record Download(byte[] bytes, String contentType, String sha256, boolean privateAsset) {}

  private final JdbcTemplate db;
  private final MediaStorage storage;
  private final AuditService audit;
  private final TransactionTemplate tx;

  public MediaService(
      JdbcTemplate db,
      MediaStorage storage,
      AuditService audit,
      PlatformTransactionManager manager) {
    this.db = db;
    this.storage = storage;
    this.audit = audit;
    tx = new TransactionTemplate(manager);
  }

  public Asset upload(String purpose, String resourceId, MultipartFile file) {
    authorize(purpose, resourceId, true);
    if (file == null || file.getSize() < 1 || file.getSize() > ImageContent.MAX_BYTES)
      throw new BusinessException(
          HttpStatus.CONTENT_TOO_LARGE, "IMAGE_SIZE_INVALID", "图片须大于0且不超过5MiB");
    ImageContent image;
    try (var input = file.getInputStream()) {
      image = ImageContent.validate(input.readNBytes(ImageContent.MAX_BYTES + 1));
    } catch (IOException e) {
      throw new IllegalArgumentException("无法读取上传图片");
    }
    String id = UUID.randomUUID().toString();
    String key = id + image.extension();
    // Object upload is outside the database transaction. Unreferenced objects from a
    // failed DB insert are retained for operator inspection, never exposed by an API.
    storage.put(key, image);
    tx.executeWithoutResult(
        status -> {
          authorize(purpose, resourceId, true);
          db.update(
              "INSERT INTO"
                  + " media_asset(id,purpose,resource_id,owner_type,owner_id,provider,object_key,content_type,byte_length,sha256)"
                  + " VALUES(?,?,?,?,?,?,?,?,?,?)",
              id,
              purpose,
              resourceId,
              UserContext.isAdmin() ? "MANAGEMENT" : "CUSTOMER",
              UserContext.getCurrentId(),
              storage.provider(),
              key,
              image.contentType(),
              image.bytes().length,
              image.sha256());
          audit.record(null, "MEDIA_UPLOADED", id);
        });
    return new Asset(id, "/media/" + id, purpose, resourceId);
  }

  public List<Asset> list(String purpose, String resourceId) {
    authorize(purpose, resourceId, false);
    return db.query(
        "SELECT id,purpose,resource_id FROM media_asset WHERE purpose=? AND resource_id=? ORDER BY"
            + " created_at DESC,id DESC LIMIT 20",
        (rs, row) ->
            new Asset(
                rs.getString("id"),
                "/media/" + rs.getString("id"),
                rs.getString("purpose"),
                rs.getString("resource_id")),
        purpose,
        resourceId);
  }

  public Download download(String id) {
    if (!id.matches("[a-f0-9-]{36}")) throw new IllegalArgumentException("图片标识无效");
    var rows = db.queryForList("SELECT * FROM media_asset WHERE id=?", id);
    if (rows.isEmpty())
      throw new BusinessException(HttpStatus.NOT_FOUND, "MEDIA_NOT_FOUND", "图片不存在");
    var row = rows.getFirst();
    authorize(String.valueOf(row.get("purpose")), String.valueOf(row.get("resource_id")), false);
    byte[] data =
        storage.get(String.valueOf(row.get("provider")), String.valueOf(row.get("object_key")));
    return new Download(
        data,
        String.valueOf(row.get("content_type")),
        String.valueOf(row.get("sha256")),
        "AFTERSALE".equals(row.get("purpose")));
  }

  private void authorize(String purpose, String resourceId, boolean write) {
    if (UserContext.getCurrentId() == null)
      throw new BusinessException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "请先登录");
    if (!Set.of("DISH", "MERCHANT", "AFTERSALE").contains(purpose))
      throw new IllegalArgumentException("图片用途无效");
    List<Map<String, Object>> rows;
    if (purpose.equals("AFTERSALE")) {
      if (resourceId == null || !resourceId.matches("[a-f0-9-]{36}"))
        throw new IllegalArgumentException("售后标识无效");
      rows =
          db.queryForList(
              "SELECT o.merchant_id,o.user_id FROM aftersale a JOIN orders o ON o.id=a.order_id"
                  + " WHERE a.id=?",
              resourceId);
    } else {
      if (resourceId == null || !resourceId.matches("[1-9][0-9]{0,17}"))
        throw new IllegalArgumentException("资源标识无效");
      rows =
          purpose.equals("DISH")
              ? db.queryForList(
                  "SELECT merchant_id FROM dish WHERE id=?", Long.parseLong(resourceId))
              : db.queryForList(
                  "SELECT id merchant_id FROM merchant WHERE id=?", Long.parseLong(resourceId));
    }
    if (rows.isEmpty())
      throw new BusinessException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "资源不存在");
    var row = rows.getFirst();
    String role = UserContext.role();
    if (purpose.equals("AFTERSALE")
        && role.equals("CUSTOMER")
        && ((Number) row.get("user_id")).longValue() == UserContext.getCurrentId()) return;
    if (!purpose.equals("AFTERSALE") && !write) return;
    if (role.equals("PLATFORM_ADMIN")) return;
    if ((role.equals("OWNER") || (purpose.equals("AFTERSALE") && role.equals("STAFF")))
        && ((Number) row.get("merchant_id")).longValue() == UserContext.merchantId()) return;
    throw new BusinessException(HttpStatus.FORBIDDEN, "MEDIA_FORBIDDEN", "无权访问或修改该资源图片");
  }
}
