package com.ai.assistant.integration.support;

import com.ai.assistant.service.BusinessException;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class MapService {
  private final String key;
  private final String endpoint;
  private final HttpClient http =
      HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(3))
          .followRedirects(HttpClient.Redirect.NEVER)
          .build();

  public MapService(SupportSettings settings) {
    key = settings.secret("integration.maps.api-key", "AMAP_API_KEY");
    endpoint =
        settings.value("integration.maps.endpoint", "AMAP_ENDPOINT", "https://restapi.amap.com");
    URI uri = URI.create(endpoint);
    if (uri.getHost() == null
        || (!uri.getScheme().equals("https")
            && !(uri.getScheme().equals("http")
                && List.of("127.0.0.1", "localhost").contains(uri.getHost())))
        || uri.getUserInfo() != null
        || uri.getRawQuery() != null
        || uri.getRawFragment() != null)
      throw new IllegalStateException(
          "AMAP_ENDPOINT must use HTTPS; only local contract servers may use HTTP");
  }

  public boolean enabled() {
    return !key.isBlank();
  }

  public List<Map<String, Object>> geocode(String address) {
    if (address == null || address.isBlank() || address.length() > 255)
      throw new IllegalArgumentException("请输入不超过255字的地址");
    JSONObject response = request("/v3/geocode/geo", "address=" + encode(address));
    JSONArray geocodes = response.getJSONArray("geocodes");
    if (geocodes == null) throw invalidResponse();
    List<Map<String, Object>> result = new ArrayList<>();
    for (int i = 0; i < Math.min(geocodes.size(), 10); i++) {
      JSONObject item = geocodes.getJSONObject(i);
      String coordinate = item.getString("location");
      try {
        validCoordinate(coordinate);
      } catch (IllegalArgumentException e) {
        throw invalidResponse();
      }
      String formatted = item.getString("formatted_address");
      if (formatted == null || formatted.isBlank()) throw invalidResponse();
      result.add(
          Map.of(
              "formattedAddress",
              formatted,
              "location",
              coordinate,
              "province",
              text(item, "province"),
              "city",
              text(item, "city"),
              "district",
              text(item, "district")));
    }
    return result;
  }

  public Map<String, Object> route(String origin, String destination) {
    validCoordinate(origin);
    validCoordinate(destination);
    JSONObject response =
        request(
            "/v5/direction/bicycling",
            "origin="
                + encode(origin)
                + "&destination="
                + encode(destination)
                + "&show_fields=cost,polyline");
    JSONObject route = response.getJSONObject("route");
    JSONArray paths = route == null ? null : route.getJSONArray("paths");
    if (paths == null || paths.isEmpty())
      throw new BusinessException(
          HttpStatus.NOT_FOUND, "MAP_ROUTE_NOT_FOUND", "未找到可用骑行路线，请核对位置或使用人工地址");
    JSONObject path = paths.getJSONObject(0);
    JSONObject cost = path.getJSONObject("cost");
    String distance = path.getString("distance");
    String duration = cost == null ? path.getString("duration") : cost.getString("duration");
    if (distance == null
        || !distance.matches("[0-9]+")
        || duration == null
        || !duration.matches("[0-9]+")) throw invalidResponse();
    StringBuilder polyline = new StringBuilder();
    JSONArray steps = path.getJSONArray("steps");
    if (steps != null)
      for (int i = 0; i < steps.size(); i++) {
        String line = steps.getJSONObject(i).getString("polyline");
        if (line != null && !line.isBlank()) {
          if (!polyline.isEmpty()) polyline.append(';');
          polyline.append(line);
        }
      }
    String navigation =
        "https://uri.amap.com/navigation?from="
            + encode(origin)
            + "&to="
            + encode(destination)
            + "&mode=ride&coordinate=gaode&src=AI-Order-Assistant";
    return Map.of(
        "distance",
        Long.parseLong(distance),
        "duration",
        Long.parseLong(duration),
        "polyline",
        polyline.toString(),
        "navigationUrl",
        navigation);
  }

  public static void validCoordinate(String coordinate) {
    if (coordinate == null
        || !coordinate.matches("-?[0-9]{1,3}(\\.[0-9]{1,8})?,-?[0-9]{1,2}(\\.[0-9]{1,8})?"))
      throw new IllegalArgumentException("坐标须为经度,纬度，使用高德坐标系");
    String[] parts = coordinate.split(",");
    double longitude = Double.parseDouble(parts[0]), latitude = Double.parseDouble(parts[1]);
    if (longitude < -180 || longitude > 180 || latitude < -90 || latitude > 90)
      throw new IllegalArgumentException("坐标超出有效范围");
  }

  private JSONObject request(String path, String query) {
    if (!enabled())
      throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "MAP_DISABLED", "地图服务尚未启用");
    HttpRequest request =
        HttpRequest.newBuilder(
                URI.create(
                    endpoint.replaceAll("/+$", "") + path + "?key=" + encode(key) + "&" + query))
            .timeout(Duration.ofSeconds(10))
            .GET()
            .build();
    try {
      var response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
      try (var stream = response.body()) {
        if (response.statusCode() != 200) throw unavailable();
        byte[] body = stream.readNBytes(512 * 1024 + 1);
        if (body.length > 512 * 1024) throw invalidResponse();
        JSONObject json = JSON.parseObject(body);
        if (json == null || !"1".equals(json.getString("status"))) throw unavailable();
        return json;
      }
    } catch (BusinessException e) {
      throw e;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw unavailable();
    } catch (Exception e) {
      // A URI contains the provider key, so transport exceptions must not reach public logs.
      throw unavailable();
    }
  }

  private static BusinessException unavailable() {
    return new BusinessException(HttpStatus.BAD_GATEWAY, "MAP_UNAVAILABLE", "地图服务暂时不可用，请稍后重试");
  }

  private static BusinessException invalidResponse() {
    return new BusinessException(HttpStatus.BAD_GATEWAY, "MAP_INVALID_RESPONSE", "地图服务响应无效，请稍后重试");
  }

  private static String text(JSONObject object, String key) {
    Object value = object.get(key);
    return value instanceof String text ? text : "";
  }

  private static String encode(String text) {
    return URLEncoder.encode(text, StandardCharsets.UTF_8);
  }
}
