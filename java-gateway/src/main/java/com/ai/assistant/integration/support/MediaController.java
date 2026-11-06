package com.ai.assistant.integration.support;

import com.ai.assistant.vo.Result;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/media")
public class MediaController {
  private final MediaService media;

  public MediaController(MediaService media) {
    this.media = media;
  }

  @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public Result<?> upload(
      @RequestParam String purpose,
      @RequestParam String resourceId,
      @RequestParam MultipartFile image) {
    return Result.success(media.upload(purpose, resourceId, image));
  }

  @GetMapping
  public Result<?> list(@RequestParam String purpose, @RequestParam String resourceId) {
    return Result.success(media.list(purpose, resourceId));
  }

  @GetMapping("/{id}")
  public ResponseEntity<byte[]> download(@PathVariable String id) {
    var file = media.download(id);
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType(file.contentType()))
        .header(
            HttpHeaders.CACHE_CONTROL, file.privateAsset() ? "no-store" : "private, max-age=300")
        .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
        .header("X-Content-Type-Options", "nosniff")
        .eTag(file.sha256())
        .body(file.bytes());
  }
}
