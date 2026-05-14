package com.claudemobile.core.bridge.bootstrap

/**
 * Represents the version information stored in the `.version` JSON marker file
 * within the extracted Embedded_Prefix directory.
 *
 * Format:
 * ```json
 * {
 *   "prefixVersion": "1.0.0",
 *   "extractedAt": "2025-01-15T10:30:00Z",
 *   "archHash": "sha256:abc123..."
 * }
 * ```
 */
public data class PrefixVersion(
    val prefixVersion: String,
    val extractedAt: String,
    val archHash: String
) {
    /**
     * Serializes this version to a JSON string for writing to the `.version` file.
     */
    public fun toJson(): String {
        return buildString {
            appendLine("{")
            appendLine("""  "$KEY_PREFIX_VERSION": "${escapeJson(prefixVersion)}",""")
            appendLine("""  "$KEY_EXTRACTED_AT": "${escapeJson(extractedAt)}",""")
            appendLine("""  "$KEY_ARCH_HASH": "${escapeJson(archHash)}"""")
            append("}")
        }
    }

    public companion object {
        internal const val KEY_PREFIX_VERSION = "prefixVersion"
        internal const val KEY_EXTRACTED_AT = "extractedAt"
        internal const val KEY_ARCH_HASH = "archHash"

        /**
         * Parses a [PrefixVersion] from a JSON string.
         *
         * @param json The JSON string from the `.version` file
         * @return The parsed version, or null if parsing fails
         */
        public fun fromJson(json: String): PrefixVersion? {
            return try {
                val map = parseSimpleJson(json)
                val prefixVersion = map[KEY_PREFIX_VERSION] ?: return null
                val extractedAt = map[KEY_EXTRACTED_AT] ?: return null
                val archHash = map[KEY_ARCH_HASH] ?: return null
                PrefixVersion(
                    prefixVersion = prefixVersion,
                    extractedAt = extractedAt,
                    archHash = archHash,
                )
            } catch (_: Exception) {
                null
            }
        }

        /**
         * Simple JSON object parser for flat string-value objects.
         * Handles the limited format used by the .version file.
         */
        private fun parseSimpleJson(json: String): Map<String, String> {
            val result = mutableMapOf<String, String>()
            // Match "key": "value" patterns
            val pattern = """"([^"\\]*(?:\\.[^"\\]*)*)"\s*:\s*"([^"\\]*(?:\\.[^"\\]*)*)"""".toRegex()
            for (match in pattern.findAll(json)) {
                val key = unescapeJson(match.groupValues[1])
                val value = unescapeJson(match.groupValues[2])
                result[key] = value
            }
            return result
        }

        private fun escapeJson(value: String): String {
            return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t")
        }

        private fun unescapeJson(value: String): String {
            return value
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")
                .replace("\\n", "\n")
                .replace("\\r", "\r")
                .replace("\\t", "\t")
        }
    }
}
