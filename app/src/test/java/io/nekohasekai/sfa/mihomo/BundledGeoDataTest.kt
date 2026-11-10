package io.nekohasekai.sfa.mihomo

import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BundledGeoDataTest {
    @get:Rule val temporary = TemporaryFolder()
    private val bytes = "verified test database".toByteArray()
    private val manifest = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) } + "  geoip.metadb\n"

    @Test fun replacesPartialFirstDownloadAndPreservesLaterUpdates() {
        val directory = temporary.newFolder()
        val database = File(directory, "geoip.metadb")
        database.writeText("truncated download")
        BundledGeoData.install(directory, manifest) { ByteArrayInputStream(bytes) }
        assertArrayEquals(bytes, database.readBytes())
        database.writeText("later core-managed update")
        BundledGeoData.install(directory, manifest) { error("Must not reinstall") }
        assertEquals("later core-managed update", database.readText())
        database.delete()
        BundledGeoData.install(directory, manifest) { ByteArrayInputStream(bytes) }
        assertArrayEquals(bytes, database.readBytes())
    }

    @Test fun damagedAssetDoesNotReplaceExistingDatabaseOrMarkInstallationComplete() {
        val directory = temporary.newFolder()
        val database = File(directory, "geoip.metadb").apply { writeText("existing") }
        assertThrows(IllegalStateException::class.java) {
            BundledGeoData.install(directory, manifest) { ByteArrayInputStream(byteArrayOf(1, 2)) }
        }
        assertEquals("existing", database.readText())
        assertFalse(File(directory, ".bundled-geodata").exists())
        assertEquals(listOf("geoip.metadb"), directory.list()!!.toList())
    }
}
