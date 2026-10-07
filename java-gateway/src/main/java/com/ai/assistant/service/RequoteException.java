package com.ai.assistant.service;

import com.ai.assistant.model.OrderDraft;
import org.springframework.http.HttpStatus;

/** 此冲突保留新报价；事务中尚未创建订单或扣库存。 */
public class RequoteException extends BusinessException {
  public RequoteException(OrderDraft draft) {
    super(HttpStatus.CONFLICT, "REQUOTE_REQUIRED", "价格或菜单标注已变化，请重新确认", draft);
  }
}
