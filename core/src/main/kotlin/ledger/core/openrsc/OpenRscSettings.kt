package ledger.core.openrsc

import java.nio.file.Files
import java.nio.file.Path

/**
 * Settings read from OpenRSC's `.conf` files exactly as the server reads them (its
 * `YMLReader`), so the ledger reaches the same values the server does.
 *
 * The files look like YAML but are read line by line: `key: value`, an optional `# comment`
 * after it, section headers ignored. The server loads several files into one set of
 * settings, and **the first value for a key wins** (except `null`, and values containing a
 * colon, which overwrite), so files must be loaded in the server's order.
 */
class OpenRscSettings {
    private val settings = mutableMapOf<String, String>()

    operator fun get(key: String): String? = settings[key]

    fun load(file: Path) = load(Files.readAllLines(file))

    fun load(lines: List<String>) {
        for (raw in lines) {
            var line = raw
            if ('#' in line) {
                // Split on '#' as Java does, dropping empty pieces at the end: with fewer than
                // two left, the server skips the line, even "key: value #".
                if (line.split('#').dropLastWhile { it.isEmpty() }.size < 2) { continue }
                // A commented-out setting.
                if (line.indexOf('#') < line.indexOf(':')) { continue }
                // Otherwise keep the part holding the setting, dropping the comment.
                line = line.split('#').firstOrNull { ':' in it } ?: continue
            }
            // As Java's String.split, which drops empty strings at the end.
            val parts = line.split(':').dropLastWhile { it.isEmpty() }.map { it.trim() }
            when (parts.size) {
                2 -> {
                    val (key, value) = parts
                    if (value.equals("null", ignoreCase = true)) {
                        settings[key] = value
                    } else {
                        settings.putIfAbsent(key, value)
                    }
                }
                // A value holding one colon, such as host:port.
                3 -> settings[parts[0]] = "${parts[1]}:${parts[2]}"
            }
        }
    }
}
