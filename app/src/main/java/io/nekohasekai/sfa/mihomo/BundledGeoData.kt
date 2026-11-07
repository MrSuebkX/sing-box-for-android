package io.nekohasekai.sfa.mihomo

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest

/** Seed the databases before mihomo parses rules, when its DNS is not initialized yet. */
internal object BundledGeoData {
    fun ensure(context: Context, directory: File) {
        val manifest = context.assets.open("mihomo-geodata/SHA256SUMS").bufferedReader().use { it.readText() }
        install(directory, manifest) { context.assets.open("mihomo-geodata/$it") }
    }

    internal fun install(directory: File, manifest: String, open: (String) -> InputStream) {
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create mihomo data directory" }
        val marker = File(directory, ".bundled-geodata")
        val alreadySeeded = marker.isFile && marker.readText() == manifest
        for (line in manifest.lineSequence().filter { it.isNotBlank() }) {
            val parts = line.trim().split(Regex("\\s+"), limit = 2)
            require(parts.size == 2 && parts[0].matches(Regex("[a-f0-9]{64}")))
            val name = parts[1]
            require(name in setOf("geoip.metadb", "GeoIP.dat", "GeoSite.dat", "ASN.mmdb"))
            val destination = File(directory, name)
            // Preserve later core-managed updates. An older unseeded/truncated download is replaced.
            if (alreadySeeded && destination.isFile && destination.length() > 0) continue
            val temporary = File.createTempFile(".geodata-", ".tmp", directory)
            try {
                val digest = MessageDigest.getInstance("SHA-256")
                open(name).use { input ->
                    FileOutputStream(temporary).use { output ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            val size = input.read(buffer)
                            if (size == -1) break
                            digest.update(buffer, 0, size)
                            output.write(buffer, 0, size)
                        }
                        output.fd.sync()
                    }
                }
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                check(actual == parts[0]) { "Bundled mihomo rule database is damaged; reinstall the APK" }
                check(temporary.renameTo(destination)) { "Cannot install mihomo rule database" }
            } finally {
                temporary.delete()
            }
        }
        marker.writeText(manifest)
    }
}
