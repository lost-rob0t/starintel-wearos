package actor.starintel.mobile

import actor.starintel.update.PackageTransferProtocol
import actor.starintel.update.UpdateFeed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateFeedTest {
    @Test
    fun parsesMultiFaceCatalogAndOrdersWearSelfUpdateLast() {
        val sha = "a".repeat(64)
        val json = """
            {
              "schema": 2,
              "channel": "master",
              "ref": "main",
              "commit": "0123456789abcdef",
              "version_code": 3,
              "version_name": "0.2.0-alpha",
              "artifacts": {
                "phone": {"package":"actor.starintel.wear","target":"phone","install_order":0,"url":"https://example.test/phone.apk","sha256":"$sha","bytes":100},
                "wear": {"package":"actor.starintel.wear","target":"wear","install_order":100,"url":"https://example.test/wear.apk","sha256":"$sha","bytes":100},
                "watchface-neon": {"package":"actor.starintel.watchface.neon","target":"wear","install_order":10,"url":"https://example.test/neon.apk","sha256":"$sha","bytes":100},
                "watchface-command": {"package":"actor.starintel.watchface.command","target":"wear","install_order":20,"url":"https://example.test/command.apk","sha256":"$sha","bytes":100},
                "watchface-terminal": {"package":"actor.starintel.watchface.terminal","target":"wear","install_order":30,"url":"https://example.test/terminal.apk","sha256":"$sha","bytes":100}
              }
            }
        """.trimIndent()
        val manifest = UpdateFeed.parse(json)
        assertEquals(3, manifest.versionCode)
        assertEquals(
            listOf("watchface-neon", "watchface-command", "watchface-terminal", "wear"),
            manifest.wearArtifacts().map { it.id },
        )
    }

    @Test
    fun transferHeaderRoundTrips() {
        val header = PackageTransferProtocol.Header(
            transferId = "12345678-1234-1234-1234-123456789abc",
            artifactId = "watchface-neon",
            packageName = "actor.starintel.watchface.neon",
            versionCode = 3,
            size = 1234,
            sha256 = "b".repeat(64),
        )
        val parsed = PackageTransferProtocol.Header.parse(header.toBytes())
        assertEquals(header, parsed)
        assertTrue(parsed.sha256.all { it == 'b' })
    }

    @Test
    fun statusQueryRoundTripsAndStatusesRejectUnknownStates() {
        val id = "12345678-1234-1234-1234-123456789abc"
        assertEquals(id, PackageTransferProtocol.parseStatusQuery(PackageTransferProtocol.statusQuery(id)))
        val invalid = """{"version":1,"transfer_id":"$id","artifact_id":"wear","state":"frozen"}"""
        val failed = runCatching { PackageTransferProtocol.Status.parse(invalid.toByteArray()) }
        assertTrue(failed.isFailure)
    }
}
