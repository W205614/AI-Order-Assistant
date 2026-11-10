package com.ai.assistant.integration.support;

import com.ai.assistant.security.UserContext;
import com.ai.assistant.service.AuthRateService;
import com.ai.assistant.vo.Result;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/maps")
public class MapController {
  private final MapService maps;
  private final AuthRateService rates;

  public MapController(MapService maps, AuthRateService rates) {
    this.maps = maps;
    this.rates = rates;
  }

  @PostMapping("/geocode")
  public Result<?> geocode(@RequestParam String q, HttpServletRequest request) {
    rates.check("maps-geocode", request.getRemoteAddr(), UserContext.actor());
    return Result.success(maps.geocode(q));
  }

  @PostMapping("/route")
  public Result<?> route(
      @RequestParam String origin, @RequestParam String destination, HttpServletRequest request) {
    rates.check("maps-route", request.getRemoteAddr(), UserContext.actor());
    return Result.success(maps.route(origin, destination));
  }
}
