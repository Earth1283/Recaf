package software.coley.recaf.services.analysis.plugin.security;

/**
 * Broad kind of behavior a {@link SecurityRule} looks for.
 */
public enum SecurityCategory {
	EXECUTION,
	DYNAMIC_CODE,
	NATIVE_CODE,
	NETWORK,
	FILE_SYSTEM,
	SENSITIVE_DATA,
	PRIVILEGE,
	SERVER_CONTROL,
	EVASION
}
