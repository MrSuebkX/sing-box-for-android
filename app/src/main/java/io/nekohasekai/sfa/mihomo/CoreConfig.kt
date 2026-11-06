package io.nekohasekai.sfa.mihomo

import io.nekohasekai.libbox.Libbox
import io.nekohasekai.sfa.Application
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor

object CoreConfig {
    fun yaml(): Yaml = Yaml(SafeConstructor(LoaderOptions().apply {
        isAllowDuplicateKeys = false
        codePointLimit = 8 * 1024 * 1024
        maxAliasesForCollections = 50
    }))

    fun clashMap(content: String): MutableMap<String, Any?>? {
        // Sing-box JSON must keep its existing parser (including supported JSON extensions).
        val trimmed = content.trimStart('\uFEFF', ' ', '\n', '\r', '\t')
        if (trimmed.startsWith("{") && !Regex("\"(proxies|proxy-groups|proxy-providers)\"\\s*:").containsMatchIn(trimmed)) return null
        if (trimmed.startsWith("//") || trimmed.startsWith("/*")) return null
        val value = yaml().load<Any?>(trimmed) as? Map<*, *> ?: error("Expected a configuration mapping")
        require(value.keys.all { it is String }) { "Configuration keys must be strings" }
        require(value.containsKey("proxies") || value.containsKey("proxy-providers") || value.containsKey("proxy-groups")) {
            "Expected Clash YAML containing proxies or proxy-providers"
        }
        return value.entries.associate { it.key as String to it.value }.toMutableMap()
    }

    fun check(content: String) {
        if (clashMap(content) == null) Libbox.checkConfig(content)
        else MihomoRuntime.validate(Application.application, content)
    }

    fun format(content: String): String = if (clashMap(content) != null) yaml().dump(clashMap(content)) else content
}
