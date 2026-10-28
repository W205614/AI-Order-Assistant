package com.ai.assistant.service;

import static com.ai.assistant.service.CommerceSupport.*;
import static com.ai.assistant.service.CommerceTypes.*;

import com.ai.assistant.security.UserContext;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CustomerAddressService {
  private final JdbcTemplate db;
  private final OrderSafetyService safety;

  public CustomerAddressService(JdbcTemplate db, OrderSafetyService safety) {
    this.db = db;
    this.safety = safety;
  }

  /** Explicit response fields: adding a storage column cannot publish it through the API. */
  public record AddressResponse(long id, long user_id, long campus_id, String recipient_name,
      String phone, String detail, long version) {
    Map<String, Object> asMap() {
      return Map.of("id", id, "user_id", user_id, "campus_id", campus_id,
          "recipient_name", recipient_name, "phone", phone, "detail", detail, "version", version);
    }
  }

  public List<AddressResponse> responses() {
    return db.query("SELECT id,user_id,campus_id,recipient_name,phone,detail,version"
        + " FROM customer_address WHERE user_id=? ORDER BY id",
        (r, i) -> new AddressResponse(r.getLong("id"), r.getLong("user_id"), r.getLong("campus_id"),
            r.getString("recipient_name"), r.getString("phone"), r.getString("detail"), r.getLong("version")),
        user());
  }

  public List<Map<String, Object>> addresses() {
    return responses().stream().map(AddressResponse::asMap).toList();
  }

  @Transactional
  public long saveAddress(Long id, AddressInput x) {
    long u = user();
    safety.lockUser(u);
    validCampus(x.campusId());
    text(x.recipientName(), 50, "收货人");
    text(x.detail(), 255, "地址");
    check(x.phone() != null && x.phone().matches("[0-9+ -]{7,30}"), "联系电话无效");
    if (id == null)
      return insert(
          db,
          "INSERT INTO customer_address(user_id,campus_id,recipient_name,phone,detail)"
              + " VALUES(?,?,?,?,?)",
          u,
          x.campusId(),
          x.recipientName(),
          x.phone(),
          x.detail());
    check(
        db.update(
                "UPDATE customer_address SET"
                    + " campus_id=?,recipient_name=?,phone=?,detail=?,version=version+1 WHERE id=?"
                    + " AND user_id=? AND version=?",
                x.campusId(),
                x.recipientName(),
                x.phone(),
                x.detail(),
                id,
                u,
                x.expectedVersion())
            == 1,
        "地址版本已变化或无权访问");
    return id;
  }

  @Transactional
  public void deleteAddress(long id) {
    safety.lockUser(user());
    check(
        db.update("DELETE FROM customer_address WHERE id=? AND user_id=?", id, user()) == 1,
        "地址不存在");
  }

  private void validCampus(long campus) {
    one(db, "SELECT id FROM campus WHERE id=? AND enabled=TRUE", campus);
  }
}
