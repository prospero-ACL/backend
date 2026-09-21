package com.prospero_acl.backend.config;

import com.prospero_acl.backend.model.enums.SecurityLevel;

/**
 * Carries the caller's clearance to the routing DataSource, which has no access to the request.
 * Request threads are pooled, so a value left behind here would be inherited by the next request
 * on that thread — always clear it in a finally block.
 */
public final class ClearanceContext {

  private static final ThreadLocal<SecurityLevel> CURRENT = new ThreadLocal<>();

  private ClearanceContext() {
  }

  public static void set(SecurityLevel level) {
    CURRENT.set(level);
  }

  public static SecurityLevel get() {
    return CURRENT.get();
  }

  public static void clear() {
    CURRENT.remove();
  }
}
