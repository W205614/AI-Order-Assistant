package com.ai.assistant.integration.support;

import static org.junit.jupiter.api.Assertions.*;

import com.ai.assistant.service.BusinessException;
import com.sun.net.httpserver.HttpServer;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

class SupportProviderHttpContractTest {
  @TempDir Path directory;

  static byte[] png() throws Exception {
    var output = new ByteArrayOutputStream();
    ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", output);
    return output.toByteArray();
  }

  @Test
  void validatesActualImageContentAndRoundTripsLocalStorage() throws Exception {
    var storage =
        new MediaStorage(
            new SupportSettings(
                new MockEnvironment()
                    .withProperty("STORAGE_LOCAL_DIRECTORY", directory.toString())));
    ImageContent image = ImageContent.validate(png());
    String key = "11111111-1111-1111-1111-111111111111.png";
    storage.put(key, image);
    assertArrayEquals(image.bytes(), storage.get("local", key));
    assertThrows(
        IllegalArgumentException.class,
        () -> ImageContent.validate("<script>alert(1)</script>".getBytes()));
    assertThrows(
        BusinessException.class, () -> ImageContent.validate(new byte[ImageContent.MAX_BYTES + 1]));
    assertThrows(IllegalArgumentException.class, () -> storage.get("local", "../outside.png"));
  }

  @Test
  void ossUsesOfficialSdkSigningAndRoundTripsTheRealHttpAdapter() throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    AtomicReference<byte[]> stored = new AtomicReference<>();
    AtomicReference<String> authorization = new AtomicReference<>();
    AtomicReference<String> path = new AtomicReference<>();
    server.createContext(
        "/",
        exchange -> {
          authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
          path.set(exchange.getRequestURI().getPath());
          byte[] response;
          if (exchange.getRequestMethod().equals("PUT")) {
            stored.set(exchange.getRequestBody().readAllBytes());
            response = new byte[0];
          } else response = stored.get();
          exchange.getResponseHeaders().set("x-oss-request-id", "contract-request");
          exchange.getResponseHeaders().set("ETag", "\"contract-etag\"");
          exchange.sendResponseHeaders(200, response.length == 0 ? -1 : response.length);
          if (response.length > 0) exchange.getResponseBody().write(response);
          exchange.close();
        });
    server.start();
    var settings =
        new SupportSettings(
            new MockEnvironment()
                .withProperty("OSS_ENDPOINT", "http://127.0.0.1:" + server.getAddress().getPort())
                .withProperty("STORAGE_PROVIDER", "oss")
                .withProperty("OSS_BUCKET", "contract-bucket")
                .withProperty("OSS_REGION", "cn-hangzhou")
                .withProperty("OSS_PATH_STYLE", "true")
                .withProperty("OSS_ACCESS_KEY_ID", "contract-key")
                .withProperty("OSS_ACCESS_KEY_SECRET", "contract-secret"));
    var storage = new MediaStorage(settings);
    try {
      String key = "22222222-2222-2222-2222-222222222222.png";
      storage.put(key, ImageContent.validate(png()));
      assertTrue(authorization.get().contains("contract-key"));
      assertEquals("/contract-bucket/" + key, path.get());
      assertArrayEquals(png(), storage.get("oss", key));
    } finally {
      storage.close();
      server.stop(0);
    }
  }

  @Test
  void mapsCallsActualHttpAndDoesNotReturnTheApiKey() throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/v3/geocode/geo",
        exchange -> {
          byte[] body =
              "{\"status\":\"1\",\"geocodes\":[{\"formatted_address\":\"测试校区\",\"location\":\"113.1,23.1\",\"province\":\"广东省\",\"city\":[],\"district\":\"校区\"}]}"
                  .getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.createContext(
        "/v5/direction/bicycling",
        exchange -> {
          byte[] body =
              "{\"status\":\"1\",\"route\":{\"paths\":[{\"distance\":\"1200\",\"cost\":{\"duration\":\"360\"},\"steps\":[{\"polyline\":\"113.1,23.1;113.2,23.2\"}]}]}}"
                  .getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.start();
    try {
      var maps =
          new MapService(
              new SupportSettings(
                  new MockEnvironment()
                      .withProperty(
                          "AMAP_ENDPOINT", "http://127.0.0.1:" + server.getAddress().getPort())
                      .withProperty("AMAP_API_KEY", "private-contract-key")));
      assertEquals("113.1,23.1", maps.geocode("测试校区").getFirst().get("location"));
      var route = maps.route("113.1,23.1", "113.2,23.2");
      assertEquals(1200L, route.get("distance"));
      assertEquals(360L, route.get("duration"));
      assertFalse(route.toString().contains("private-contract-key"));
      assertThrows(IllegalArgumentException.class, () -> maps.route("181,23", "113,23"));
      var disabled = new MapService(new SupportSettings(new MockEnvironment()));
      assertEquals(
          "MAP_DISABLED",
          assertThrows(BusinessException.class, () -> disabled.geocode("测试校区")).errorCode());
    } finally {
      server.stop(0);
    }
  }

  @Test
  void disabledSmsNeverPretendsToSend() {
    var sms = new SmsSender(new SupportSettings(new MockEnvironment()));
    assertEquals(
        "SMS_DISABLED",
        assertThrows(
                BusinessException.class,
                () -> sms.send("13800138000", "template", Map.of("code", "123456")))
            .errorCode());
  }
}
