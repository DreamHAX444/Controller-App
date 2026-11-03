/**
 * TRACE Phase 2B — Database Access Layer Interface & Implementations
 * Provides abstract ICommandDatabaseAccess for pure offline mock testing
 * and SupabaseDatabaseAccess for live Supabase service-role execution.
 */

import type {
  AuthenticatedCaller,
  DeviceRecord,
  UserDeviceAssignment,
  StoredCommandRecord,
  CallerRole,
  AccountStatus,
  ServicePermission,
} from "./types.ts";

export interface ICommandDatabaseAccess {
  /**
   * Verifies the Bearer JWT and retrieves caller profile.
   */
  getUserAndProfile(token: string): Promise<AuthenticatedCaller | null>;

  /**
   * Retrieves registered device record by deviceId.
   */
  getDevice(deviceId: string): Promise<DeviceRecord | null>;

  /**
   * Retrieves user-device assignment and allowed services.
   */
  getUserDeviceAssignment(
    userId: string,
    deviceId: string
  ): Promise<UserDeviceAssignment | null>;

  /**
   * Queries existing command by idempotencyKey.
   */
  findCommandByIdempotencyKey(
    idempotencyKey: string
  ): Promise<StoredCommandRecord | null>;

  /**
   * Atomically reserves the next monotonic sequence ID from public.commands_id_seq.
   */
  allocateCommandId(): Promise<number>;

  /**
   * Atomically inserts signed command record into public.commands.
   */
  insertSignedCommand(
    record: Omit<StoredCommandRecord, "created_at">
  ): Promise<StoredCommandRecord>;

  /**
   * Retrieves enrolled device public key by deviceId.
   */
  getEnrolledDevicePublicKey(deviceId: string): Promise<string | null>;

  /**
   * Atomically records authenticated command acknowledgement.
   */
  recordAcknowledgement(
    commandId: number,
    deviceId: string,
    idempotencyKey: string,
    status: string,
    timestampEpochSec: number,
    resultDigest?: string
  ): Promise<{ success: boolean; error?: string; alreadyAcknowledged?: boolean }>;
}

/**
 * In-memory Mock Database Access Layer for 100% offline unit and security testing.
 */
export class MockCommandDatabaseAccess implements ICommandDatabaseAccess {
  public users: Map<string, AuthenticatedCaller> = new Map();
  public devices: Map<string, DeviceRecord> = new Map();
  public assignments: Map<string, UserDeviceAssignment> = new Map(); // key: `${userId}:${deviceId}`
  public commands: Map<string, StoredCommandRecord> = new Map(); // key: idempotency_key
  public enrolledDevices: Map<string, { publicKey: string; isRevoked: boolean }> = new Map();
  public sequenceCounter: number = 1041;
  public simulateDbDown: boolean = false;
  public simulateUniqueViolationOnInsert: boolean = false;

  constructor() {
    this.seedDefaultTestState();
  }

  private seedDefaultTestState(): void {
    // 1. Seed standard test users
    this.users.set("jwt-owner", {
      userId: "user-owner-uuid",
      email: "owner@livetracker.com",
      role: "OWNER",
      accountStatus: "active",
    });

    this.users.set("jwt-admin", {
      userId: "user-admin-uuid",
      email: "admin@livetracker.com",
      role: "ADMIN",
      accountStatus: "active",
    });

    this.users.set("jwt-user-assigned", {
      userId: "user-assigned-uuid",
      email: "user@livetracker.com",
      role: "USER",
      accountStatus: "active",
    });

    this.users.set("jwt-user-unassigned", {
      userId: "user-unassigned-uuid",
      email: "unassigned@livetracker.com",
      role: "USER",
      accountStatus: "active",
    });

    this.users.set("jwt-visitor", {
      userId: "user-visitor-uuid",
      email: "visitor@livetracker.com",
      role: "VISITOR",
      accountStatus: "active",
    });

    this.users.set("jwt-suspended", {
      userId: "user-suspended-uuid",
      email: "suspended@livetracker.com",
      role: "USER",
      accountStatus: "suspended",
    });

    // 2. Seed test devices
    this.devices.set("tracker-alpha-01", {
      id: "tracker-alpha-01",
      lifecycle: "ACTIVE",
      ownerId: "user-owner-uuid",
    });

    this.devices.set("tracker-decommissioned", {
      id: "tracker-decommissioned",
      lifecycle: "DECOMMISSIONED",
      ownerId: "user-owner-uuid",
    });

    // 3. Seed device assignment with CAMERA and LOCATION services
    this.assignments.set("user-assigned-uuid:tracker-alpha-01", {
      userId: "user-assigned-uuid",
      deviceId: "tracker-alpha-01",
      allowedServices: ["CAMERA", "LOCATION"],
    });
  }

  async getUserAndProfile(token: string): Promise<AuthenticatedCaller | null> {
    if (this.simulateDbDown) throw new Error("Connection to database lost: connection refused");
    return this.users.get(token) || null;
  }

  async getDevice(deviceId: string): Promise<DeviceRecord | null> {
    if (this.simulateDbDown) throw new Error("Connection to database lost: connection refused");
    return this.devices.get(deviceId) || null;
  }

  async getUserDeviceAssignment(
    userId: string,
    deviceId: string
  ): Promise<UserDeviceAssignment | null> {
    if (this.simulateDbDown) throw new Error("Connection to database lost: connection refused");
    return this.assignments.get(`${userId}:${deviceId}`) || null;
  }

  async findCommandByIdempotencyKey(
    idempotencyKey: string
  ): Promise<StoredCommandRecord | null> {
    if (this.simulateDbDown) throw new Error("Connection to database lost: connection refused");
    return this.commands.get(idempotencyKey) || null;
  }

  async allocateCommandId(): Promise<number> {
    if (this.simulateDbDown) throw new Error("Connection to database lost: connection refused");
    this.sequenceCounter += 1;
    return this.sequenceCounter;
  }

  async insertSignedCommand(
    record: Omit<StoredCommandRecord, "created_at">
  ): Promise<StoredCommandRecord> {
    if (this.simulateDbDown) throw new Error("Connection to database lost: connection refused");

    if (this.simulateUniqueViolationOnInsert || this.commands.has(record.idempotency_key)) {
      const err: any = new Error("duplicate key value violates unique constraint \"uq_commands_idempotency_key\"");
      err.code = "23505";
      err.constraint = "uq_commands_idempotency_key";
      throw err;
    }

    const fullRecord: StoredCommandRecord = {
      ...record,
      created_at: new Date().toISOString(),
    };

    this.commands.set(record.idempotency_key, fullRecord);
    return fullRecord;
  }

  async getEnrolledDevicePublicKey(deviceId: string): Promise<string | null> {
    if (this.simulateDbDown) throw new Error("Mock database connection failure");
    const dev = this.enrolledDevices.get(deviceId);
    if (!dev || dev.isRevoked) return null;
    return dev.publicKey;
  }

  async recordAcknowledgement(
    commandId: number,
    deviceId: string,
    idempotencyKey: string,
    status: string,
    timestampEpochSec: number,
    resultDigest?: string
  ): Promise<{ success: boolean; error?: string; alreadyAcknowledged?: boolean }> {
    if (this.simulateDbDown) throw new Error("Mock database connection failure");
    const dev = this.enrolledDevices.get(deviceId);
    if (!dev) {
      return { success: false, error: "UNAUTHORIZED_DEVICE" };
    }
    if (dev.isRevoked) {
      return { success: false, error: "DEVICE_REVOKED" };
    }
    let foundCmd: StoredCommandRecord | undefined;
    for (const cmd of this.commands.values()) {
      if (cmd.id === commandId) {
        foundCmd = cmd;
        break;
      }
    }
    if (!foundCmd) {
      return { success: false, error: "COMMAND_NOT_FOUND" };
    }
    if (foundCmd.device_id !== deviceId) {
      return { success: false, error: "CROSS_DEVICE_ACK_DENIED" };
    }
    if (foundCmd.idempotency_key !== idempotencyKey) {
      return { success: false, error: "IDEMPOTENCY_MISMATCH" };
    }
    if (foundCmd.status === "executed" || foundCmd.status === "failed" || foundCmd.status === "expired") {
      if (foundCmd.status === status) {
        return { success: true, alreadyAcknowledged: true };
      }
      return { success: false, error: "TERMINAL_STATE_REGRESSION" };
    }
    foundCmd.status = status;
    return { success: true };
  }
}

/**
 * Production Database Access Layer using Supabase service-role client.
 */
export class SupabaseDatabaseAccess implements ICommandDatabaseAccess {
  private supabaseUrl: string;
  private serviceRoleKey: string;

  constructor(supabaseUrl: string, serviceRoleKey: string) {
    this.supabaseUrl = supabaseUrl.replace(/\/$/, "");
    this.serviceRoleKey = serviceRoleKey;
  }

  private async fetchPostgrest(path: string, options: RequestInit = {}): Promise<any> {
    const headers = new Headers(options.headers || {});
    headers.set("apikey", this.serviceRoleKey);
    headers.set("Authorization", `Bearer ${this.serviceRoleKey}`);
    headers.set("Content-Type", "application/json");

    const res = await fetch(`${this.supabaseUrl}/rest/v1/${path}`, {
      ...options,
      headers,
    });

    if (!res.ok) {
      const errorText = await res.text();
      const err: any = new Error(`PostgREST error [${res.status}]: ${errorText}`);
      err.status = res.status;
      err.body = errorText;
      throw err;
    }

    const contentType = res.headers.get("content-type");
    if (contentType && contentType.includes("application/json")) {
      return await res.json();
    }
    return null;
  }

  async getUserAndProfile(token: string): Promise<AuthenticatedCaller | null> {
    // 1. Verify GoTrue JWT
    const authRes = await fetch(`${this.supabaseUrl}/auth/v1/user`, {
      headers: {
        apikey: this.serviceRoleKey,
        Authorization: `Bearer ${token}`,
      },
    });

    if (!authRes.ok) {
      return null;
    }

    const authUser = await authRes.json();
    const userId = authUser.id;

    // 2. Query profile
    const profiles = await this.fetchPostgrest(
      `profiles?id=eq.${encodeURIComponent(userId)}&select=id,role,account_status,email`
    );

    if (!profiles || profiles.length === 0) {
      return null;
    }

    const profile = profiles[0];
    return {
      userId,
      email: profile.email || authUser.email || null,
      role: (profile.role || "VISITOR").toUpperCase() as CallerRole,
      accountStatus: (profile.account_status || "unknown").toLowerCase() as AccountStatus,
    };
  }

  async getDevice(deviceId: string): Promise<DeviceRecord | null> {
    const devices = await this.fetchPostgrest(
      `devices?device_id=eq.${encodeURIComponent(deviceId)}&select=device_id,status,name`
    );
    if (!devices || devices.length === 0) return null;
    const dev = devices[0];
    return {
      id: dev.device_id,
      lifecycle: dev.status || "ACTIVE",
    };
  }

  async getUserDeviceAssignment(
    userId: string,
    deviceId: string
  ): Promise<UserDeviceAssignment | null> {
    const rows = await this.fetchPostgrest(
      `user_device_assignments?user_id=eq.${encodeURIComponent(userId)}&device_id=eq.${encodeURIComponent(deviceId)}&select=id,status`
    );
    if (!rows || rows.length === 0) return null;
    const assignment = rows[0];

    // Fetch granted features
    const featureRows = await this.fetchPostgrest(
      `device_feature_permissions?assignment_id=eq.${encodeURIComponent(assignment.id)}&is_allowed=eq.true&select=feature`
    );

    const services = Array.isArray(featureRows)
      ? featureRows.map((f: any) => f.feature as ServicePermission)
      : [];

    return {
      userId,
      deviceId,
      allowedServices: services,
    };
  }

  async findCommandByIdempotencyKey(
    idempotencyKey: string
  ): Promise<StoredCommandRecord | null> {
    const rows = await this.fetchPostgrest(
      `commands?idempotency_key=eq.${encodeURIComponent(idempotencyKey)}&select=*`
    );
    if (!rows || rows.length === 0) return null;
    return rows[0] as StoredCommandRecord;
  }

  async allocateCommandId(): Promise<number> {
    // Calls PostgreSQL RPC check_idempotency_or_allocate_id or sequence reservation
    const result = await this.fetchPostgrest("rpc/allocate_command_sequence", {
      method: "POST",
      body: JSON.stringify({}),
    });
    return Number(result);
  }

  async insertSignedCommand(
    record: Omit<StoredCommandRecord, "created_at">
  ): Promise<StoredCommandRecord> {
    const rows = await this.fetchPostgrest("commands", {
      method: "POST",
      headers: {
        Prefer: "return=representation",
      },
      body: JSON.stringify(record),
    });
    return rows[0] as StoredCommandRecord;
  }

  async getEnrolledDevicePublicKey(deviceId: string): Promise<string | null> {
    const rows = await this.fetchPostgrest(
      `enrolled_devices?device_id=eq.${encodeURIComponent(deviceId)}&select=public_key_spki,is_revoked`
    );
    if (!rows || rows.length === 0 || rows[0].is_revoked) return null;
    return rows[0].public_key_spki;
  }

  async recordAcknowledgement(
    commandId: number,
    deviceId: string,
    idempotencyKey: string,
    status: string,
    timestampEpochSec: number,
    resultDigest?: string
  ): Promise<{ success: boolean; error?: string; alreadyAcknowledged?: boolean }> {
    try {
      const result = await this.fetchPostgrest("rpc/acknowledge_command", {
        method: "POST",
        body: JSON.stringify({
          p_command_id: commandId,
          p_device_id: deviceId,
          p_idempotency_key: idempotencyKey,
          p_status: status,
          p_timestamp_epoch_sec: timestampEpochSec,
          p_result_digest: resultDigest || "",
        }),
      });
      return { success: true, alreadyAcknowledged: result?.status === "ALREADY_ACKNOWLEDGED" };
    } catch (e: any) {
      return { success: false, error: e.message };
    }
  }
}
