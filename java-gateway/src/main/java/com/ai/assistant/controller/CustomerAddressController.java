package com.ai.assistant.controller;

import static com.ai.assistant.service.CommerceTypes.*;
import com.ai.assistant.service.*;
import com.ai.assistant.vo.Result;
import org.springframework.web.bind.annotation.*;

@RestController
public class CustomerAddressController {
  private final CustomerAddressService addresses;
  public CustomerAddressController(CustomerAddressService addresses) { this.addresses = addresses; }

  @GetMapping("/addresses")
  public Result<?> addresses() {
    return Result.success(addresses.responses());
  }

  @PostMapping("/addresses")
  public Result<?> address(@RequestBody AddressInput x) {
    return Result.success(addresses.saveAddress(null, x));
  }

  @PutMapping("/addresses/{id}")
  public Result<?> address(@PathVariable long id, @RequestBody AddressInput x) {
    return Result.success(addresses.saveAddress(id, x));
  }

  @DeleteMapping("/addresses/{id}")
  public Result<?> deleteAddress(@PathVariable long id) {
    addresses.deleteAddress(id);
    return Result.success(null);
  }

}
