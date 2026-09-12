package actor.starintel.mobile

import actor.starintel.update.UpdateFeed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class UpdateFeedTest {
    private val goodManifest = """
        {
          "schema": 1,
          "channel": "master",
          "ref": "main",
          "commit": "0123456789abcdef",
          "version_code": 1000042,
          "version_name": "0.1.0-master.0123456789",
          "artifacts": {
            "phone": {
              "package": "actor.starintel.wear",
              "url": "https://example.invalid/starintel-phone.apk",
              "sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
              "bytes": 1234
            },
            "wear": {
              "package": "actor.starintel.wear",
              "url": "https://example.invalid/starintel-wear.apk",
              "sha256": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
              "bytes": 2345
            },
            "watchface": {
              "package": "actor.starintel.watchface",
              "url": "https://example.invalid/starintel-watchface.apk",
              "sha256": "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
              "bytes": 3456
            }
          }
        }
    """.trimIndent()

    @Test
    fun parsesPinnedArtifacts() {
        val manifest = UpdateFeed.parse(goodManifest)
        assertEquals("master", manifest.channel)
        assertEquals(1000042L, manifest.versionCode)
        assertEquals("actor.starintel.watchface", manifest.artifact("watchface").packageName)
        assertEquals(3456L, manifest.artifact("watchface").size)
    }

    @Test
    fun rejectsUnknownSchema() {
        val invalid = goodManifest.replace("\"schema\": 1", "\"schema\": 2")
        assertThrows(IllegalArgumentException::class.java) { UpdateFeed.parse(invalid) }
    }

    @Test
    fun rejectsNonHttpsArtifact() {
        val invalid = goodManifest.replace(
            "https://example.invalid/starintel-phone.apk",
            "http://example.invalid/starintel-phone.apk",
        )
        assertThrows(IllegalArgumentException::class.java) { UpdateFeed.parse(invalid) }
    }

    @Test
    fun rejectsInvalidChecksum() {
        val invalid = goodManifest.replace(
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            "not-a-sha",
        )
        assertThrows(IllegalArgumentException::class.java) { UpdateFeed.parse(invalid) }
    }
}
