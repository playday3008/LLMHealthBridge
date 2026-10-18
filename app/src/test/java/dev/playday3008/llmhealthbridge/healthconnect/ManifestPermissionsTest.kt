package dev.playday3008.llmhealthbridge.healthconnect

import kotlin.test.Test
import kotlin.test.assertEquals

import java.io.File

import javax.xml.parsers.DocumentBuilderFactory

class ManifestPermissionsTest {
    // Exactly the write permissions: a read permission would contradict the own-records-only design.
    @Test fun manifestDeclaresExactlyTheWritePermissions() {
        val doc = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(File("src/main/AndroidManifest.xml"))
        val nodes = doc.getElementsByTagName("uses-permission")
        val health = (0 until nodes.length)
            .map { nodes.item(it).attributes.getNamedItem("android:name").nodeValue }
            .filter { it.startsWith("android.permission.health.") }
            .toSet()
        assertEquals(ALL_WRITE_PERMISSIONS, health)
    }
}
