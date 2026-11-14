package software.coley.recaf.services.analysis.plugin.security;

import jakarta.annotation.Nonnull;

import java.util.Locale;

import static software.coley.recaf.services.analysis.plugin.security.SecurityCategory.*;
import static software.coley.recaf.services.analysis.plugin.security.Severity.*;

/**
 * Something {@link PluginSecurityScanner} reports. Most rules match single API uses. Combined rules match several
 * uses together, which is what separates a backdoor from a plugin that simply uses a powerful API.
 */
public enum SecurityRule {
	PROCESS_EXEC(HIGH, EXECUTION),
	DEFINE_CLASS(HIGH, DYNAMIC_CODE),
	GENERATED_CLASS(LOW, DYNAMIC_CODE),
	CLASS_LOADER(MEDIUM, DYNAMIC_CODE),
	SCRIPT_ENGINE(HIGH, DYNAMIC_CODE),
	INSTRUMENTATION(HIGH, DYNAMIC_CODE),
	NATIVE_LIBRARY(HIGH, NATIVE_CODE),
	DISGUISED_CLASS(HIGH, DYNAMIC_CODE),
	DISGUISED_ARCHIVE(MEDIUM, DYNAMIC_CODE),
	NATIVE_BINARY(MEDIUM, NATIVE_CODE),
	DISGUISED_BINARY(HIGH, NATIVE_CODE),
	HIGH_ENTROPY_RESOURCE(LOW, EVASION),
	NETWORK(LOW, SecurityCategory.NETWORK),
	LISTEN_SOCKET(MEDIUM, SecurityCategory.NETWORK),
	URL(LOW, SecurityCategory.NETWORK),
	SUSPICIOUS_URL(HIGH, SecurityCategory.NETWORK),
	FILE_WRITE(LOW, FILE_SYSTEM),
	FILE_DELETE(LOW, FILE_SYSTEM),
	ARCHIVE_WRITE(LOW, FILE_SYSTEM),
	PLUGINS_FOLDER(MEDIUM, FILE_SYSTEM),
	SENSITIVE_FILE(MEDIUM, SENSITIVE_DATA),
	ENVIRONMENT(LOW, SENSITIVE_DATA),
	SET_OP(HIGH, PRIVILEGE),
	CONSOLE_COMMAND(LOW, PRIVILEGE),
	PRIVILEGE_COMMAND(HIGH, PRIVILEGE),
	WILDCARD_PERMISSION(HIGH, PRIVILEGE),
	HARDCODED_PLAYER(MEDIUM, PRIVILEGE),
	SHUTDOWN(MEDIUM, SERVER_CONTROL),
	DISABLE_PLUGIN(MEDIUM, SERVER_CONTROL),
	UNSAFE(MEDIUM, EVASION),
	REFLECTION(LOW, EVASION),
	COMPUTED_CLASS_NAME(LOW, EVASION),
	DECODE(LOW, EVASION),
	DECRYPT(LOW, EVASION),
	HIDDEN_CODE(CRITICAL, DYNAMIC_CODE, true),
	REMOTE_CODE(CRITICAL, DYNAMIC_CODE, true),
	REMOTE_EXEC(CRITICAL, EXECUTION, true),
	DOWNLOADS_AND_DEFINES(HIGH, DYNAMIC_CODE, true),
	RUNTIME_LIBRARIES(MEDIUM, DYNAMIC_CODE, true),
	JAR_INFECTION(CRITICAL, FILE_SYSTEM, true),
	DOWNLOADS_TO_PLUGINS(HIGH, FILE_SYSTEM, true),
	CHAT_BACKDOOR(CRITICAL, PRIVILEGE, true),
	PLAYER_BACKDOOR(CRITICAL, PRIVILEGE, true),
	STRING_ENCRYPTION(LOW, EVASION, true),
	PAYLOAD_LOADER(CRITICAL, DYNAMIC_CODE, true);

	private final Severity severity;
	private final SecurityCategory category;
	private final boolean combined;

	SecurityRule(@Nonnull Severity severity, @Nonnull SecurityCategory category) {
		this(severity, category, false);
	}

	SecurityRule(@Nonnull Severity severity, @Nonnull SecurityCategory category, boolean combined) {
		this.severity = severity;
		this.category = category;
		this.combined = combined;
	}

	/**
	 * @return Severity of findings of this rule.
	 */
	@Nonnull
	public Severity severity() {
		return severity;
	}

	/**
	 * @return Kind of behavior the rule looks for.
	 */
	@Nonnull
	public SecurityCategory category() {
		return category;
	}

	/**
	 * @return {@code true} when the rule matches several behaviors together, listed as the finding's evidence.
	 */
	public boolean isCombined() {
		return combined;
	}

	/**
	 * @return Stable identifier, also the suffix of the rule's translation keys.
	 */
	@Nonnull
	public String id() {
		return name().toLowerCase(Locale.ROOT).replace('_', '-');
	}
}
