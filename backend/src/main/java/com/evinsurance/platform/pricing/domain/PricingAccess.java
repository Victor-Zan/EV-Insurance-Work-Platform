package com.evinsurance.platform.pricing.domain;
import com.evinsurance.platform.identity.domain.*;
import com.evinsurance.platform.foundation.api.ApiException;
public final class PricingAccess {
 private PricingAccess() {}
 public static CurrentUser read() {
  var user=CurrentUser.require();
  if (!user.roles().contains(Role.ADMIN) && !user.roles().contains(Role.CUSTOMER_SERVICE)) throw ApiException.denied();
  return user;
 }
 public static CurrentUser write() { var user=CurrentUser.require(); user.requireAdmin(); return user; }
}
