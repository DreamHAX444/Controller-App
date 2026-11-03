/**
 * TRACE Phase 2B — Authentication and Authorization Engine
 * Enforces fail-closed, default-deny security on all incoming command submissions.
 */

import { COMMAND_SERVICE_MAP } from "./types.ts";
import type { AuthenticatedCaller, AuthError } from "./types.ts";
import type { ICommandDatabaseAccess } from "./db.ts";

/**
 * Validates the Authorization Bearer token and verifies caller account status.
 */
export async function authenticateCaller(
  authHeader: string | null,
  db: ICommandDatabaseAccess
): Promise<{ caller?: AuthenticatedCaller; error?: AuthError }> {
  if (!authHeader || !authHeader.trim()) {
    return {
      error: {
        httpStatus: 401,
        errorCode: "MISSING_AUTHORIZATION_HEADER",
        message: "Missing or empty Authorization header.",
      },
    };
  }

  const trimmed = authHeader.trim();
  if (!trimmed.startsWith("Bearer ") && trimmed !== "Bearer") {
    return {
      error: {
        httpStatus: 401,
        errorCode: "MALFORMED_AUTHORIZATION_SCHEME",
        message: "Malformed Authorization scheme: Bearer token required.",
      },
    };
  }

  const token = trimmed.startsWith("Bearer ") ? trimmed.substring(7).trim() : "";
  if (!token) {
    return {
      error: {
        httpStatus: 401,
        errorCode: "EMPTY_BEARER_TOKEN",
        message: "Bearer token cannot be empty.",
      },
    };
  }

  const caller = await db.getUserAndProfile(token);
  if (!caller) {
    return {
      error: {
        httpStatus: 401,
        errorCode: "INVALID_OR_EXPIRED_JWT",
        message: "Invalid, expired, or unauthenticated JWT token.",
      },
    };
  }

  return { caller };
}

/**
 * Enforces role restrictions, device existence, lifecycle, and granular service permissions.
 */
export async function authorizeCommandSubmission(
  caller: AuthenticatedCaller,
  deviceId: string,
  commandName: string,
  db: ICommandDatabaseAccess
): Promise<{ authorized: boolean; error?: AuthError }> {
  // Rule 1: Account status must be ACTIVE
  if (caller.accountStatus !== "active") {
    return {
      authorized: false,
      error: {
        httpStatus: 403,
        errorCode: "ACCOUNT_NOT_ACTIVE",
        message: `Account status is '${caller.accountStatus}'. Command submission forbidden.`,
      },
    };
  }

  // Rule 2: Strictly reject VISITOR role
  if (caller.role === "VISITOR") {
    return {
      authorized: false,
      error: {
        httpStatus: 403,
        errorCode: "VISITOR_ACCESS_DENIED",
        message: "Visitor role is restricted to synthetic demo mode and cannot execute hardware commands.",
      },
    };
  }

  // Rule 3: Allowlist command name and resolve required ServicePermission
  const requiredService = COMMAND_SERVICE_MAP[commandName];
  if (!requiredService) {
    return {
      authorized: false,
      error: {
        httpStatus: 400,
        errorCode: "UNSUPPORTED_COMMAND",
        message: `Unknown or disallowed command: '${commandName}'.`,
      },
    };
  }

  // Rule 4: Target device must exist and be ACTIVE
  const device = await db.getDevice(deviceId);
  if (!device) {
    return {
      authorized: false,
      error: {
        httpStatus: 403,
        errorCode: "DEVICE_ACCESS_DENIED",
        message: `Target device '${deviceId}' does not exist or access is forbidden.`,
      },
    };
  }

  if (device.lifecycle.toUpperCase() !== "ACTIVE") {
    return {
      authorized: false,
      error: {
        httpStatus: 403,
        errorCode: "DEVICE_INACTIVE",
        message: `Target device '${deviceId}' is '${device.lifecycle}'. Commands cannot be dispatched.`,
      },
    };
  }

  // Rule 5: Privileged Roles (OWNER / ADMIN) Verification
  if (caller.role === "OWNER" || caller.role === "ADMIN") {
    // If device is explicitly owned or assigned
    if (device.ownerId === caller.userId) {
      return { authorized: true };
    }

    const assignment = await db.getUserDeviceAssignment(caller.userId, deviceId);
    if (assignment || caller.role === "OWNER") {
      return { authorized: true };
    }

    return {
      authorized: false,
      error: {
        httpStatus: 403,
        errorCode: "CROSS_FLEET_ACCESS_DENIED",
        message: "Target device is not assigned to or owned by caller's fleet.",
      },
    };
  }

  // Rule 6: Standard USER Role Verification
  if (caller.role === "USER") {
    const assignment = await db.getUserDeviceAssignment(caller.userId, deviceId);
    if (!assignment) {
      return {
        authorized: false,
        error: {
          httpStatus: 403,
          errorCode: "DEVICE_NOT_ASSIGNED",
          message: `User is not assigned to device '${deviceId}'.`,
        },
      };
    }

    if (!assignment.allowedServices.includes(requiredService)) {
      return {
        authorized: false,
        error: {
          httpStatus: 403,
          errorCode: "FEATURE_PERMISSION_DENIED",
          message: `User lacks '${requiredService}' permission for device '${deviceId}'.`,
        },
      };
    }

    return { authorized: true };
  }

  // Default-deny fail-closed fallback
  return {
    authorized: false,
    error: {
      httpStatus: 403,
      errorCode: "ACCESS_DENIED_DEFAULT",
      message: "Access denied by default-deny security policy.",
    },
  };
}
