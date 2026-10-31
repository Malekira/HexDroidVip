/* HexDroidIRC — Copyright (C) 2026 boxlabs. GPL-3.0-or-later. */
package com.boxlabs.hexdroid

import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.nio.file.Files
import java.util.zip.*

class PersonalPhase3Test {
    @Test fun writingFormatUsesValidCodesAndProtectsCommandsAndLeadingNumbers() {
        val s = WritingStyle(4, 2, true, true, true, true)
        assertEquals("\u0002\u001d\u001f\u0016\u000304,02\u0002\u0002123, texto", s.apply("123, texto"))
        assertEquals("/whois DemoNick01", s.apply("/whois DemoNick01"))
        assertEquals("texto", WritingStyle(999, -4).apply("texto"))
        assertEquals("\u000399,02texto", WritingStyle(background = 2).apply("texto"))
        assertEquals("texto", WritingStyle().apply("texto"))
    }
    @Test fun palettesKeepBothVariantsAndEnsureContrastWithoutChangingTheSavedChoice() {
        val t = PersonalTheme(darkColors = mapOf("CHAT" to 0xff123456.toInt()), lightColors = mapOf("CHAT" to 0xffabcdef.toInt()))
        assertEquals(0xff123456.toInt(), t.color(ThemeRole.CHAT, true))
        assertEquals(0xffabcdef.toInt(), t.color(ThemeRole.CHAT, false))
        assertEquals(0xff000000.toInt(), readableThemeText(0xffffffff.toInt(), 0xffffffff.toInt()))
        ThemeRole.entries.forEach { r ->
            listOf(true, false).forEach { d ->
                val background = t.color(r, d)
                assertTrue(themeContrast(readableThemeText(background, background), background) >= 4.5)
            }
        }
        assertEquals(0xff123456.toInt(), parseThemeHex("#123456"))
        assertNull(parseThemeHex("xyzxyz")); assertNull(parseThemeHex("#123"))
    }
    @Test fun completionListsEveryMemberAndTabCyclesWithoutChangingSurroundingText() {
        val nicks = listOf("@DemoNick01", "+DemoNick02", "OtherDemo", "DemoNick01")
        assertEquals(listOf("DemoNick01", "DemoNick02"), completionMatches(nicks, "dem"))
        val a = advanceNickCompletion("Hola dem después", 8, nicks, null)!!
        assertEquals("Hola DemoNick01 después", a.text)
        val b = advanceNickCompletion(a.text, a.cursor, nicks, a)!!
        assertEquals("Hola DemoNick02 después", b.text)
        val c = advanceNickCompletion(b.text, b.cursor, nicks, b, true)!!
        assertEquals(a.text, c.text)
        val changedMembers = advanceNickCompletion(b.text, b.cursor, listOf("@DemoNick03"), b)!!
        assertEquals("Hola DemoNick03 después", changedMembers.text)
        assertEquals(listOf("DemoNick03"), changedMembers.matches)
        val newRoom = advanceNickCompletion("dem", 3, listOf("+DemoNick03"), null)!!
        assertEquals(listOf("DemoNick03"), newRoom.matches)
        assertEquals("DemoNick03: ", newRoom.text)
        assertNull(advanceNickCompletion("zzz", 3, nicks, null))
    }
    @Test fun completionIsNotLimitedToSixteenMatchesAndRecognizesOperatorAndVoice() {
        val list = (1..80).map { "@DemoNick%03d".format(it) } + "+DemoNick081"
        assertEquals(81, completionMatches(list, "Demo").size)
    }
    @Test fun secretFilteringAndNewlineEncodingKeepUnicode() {
        assertNull(safeHistoryText("NickServ", "identify not-a-real-secret"))
        assertNull(safeHistoryText("#demo", "/oper demo not-a-real-secret"))
        val filtered = safeHistoryText("#demo", "token=FAKE_TOKEN password:FAKE_PASSWORD hola ñ 🙂")!!
        assertFalse(filtered.contains("FAKE_TOKEN")); assertFalse(filtered.contains("FAKE_PASSWORD")); assertTrue(filtered.contains("ñ 🙂"))
        val text = "Acentos áéíóú 🙂\nsegunda línea\\ruta\rfin"
        assertEquals(text, decodeHistoryBody(encodeHistoryBody(text)))
        assertFalse(encodeHistoryBody(text).contains('\n'))
    }
    @Test fun rejectionCannotLoseItsMeaningAndNeverInjectsCommands() {
        assertTrue(rejectionText("Ahora no", "ocupado").contains("rechazado"))
        assertEquals("Su privado fue rechazado Motivo: motivo de prueba", rejectionText("Su privado fue rechazado", "motivo de prueba"))
        assertEquals("hola  PRIVMSG #demo :x", safeAutomaticText("hola\r\nPRIVMSG #demo :x"))
        assertNotEquals(privateRequestId("net-1", "demonick01"), privateRequestId("net-2", "demonick01"))
        assertTrue(rejectionText("Rechazo", "").startsWith("Su privado fue rechazado"))
        assertEquals("MODE DemoNick01 +g", callerIdCommand("g", true, "DemoNick01", "enable"))
        assertEquals("ACCEPT +DemoNick02", callerIdCommand("g", true, "DemoNick01", "accept", "DemoNick02"))
        try { callerIdCommand(null, true, "DemoNick01", "list"); fail("Invented CALLERID support") } catch (_: IllegalArgumentException) { }
        try { callerIdCommand("g", false, "DemoNick01", "accept", "DemoNick02"); fail("Sent ACCEPT while offline") } catch (_: IllegalStateException) { }
    }
    @Test fun historyStreamsSearchPaginationAndCompatibleZipWithoutLoss() {
        val root = Files.createTempDirectory("history-unit").toFile()
        val staging = Files.createTempDirectory("history-stage").toFile()
        try {
            val path = "network-demo/#demo.txt"
            val f = HistoryFiles.resolve(root, path); f.parentFile!!.mkdirs()
            val lines = (0..204).map { "2026-10-27 20:15:32\t<DemoNick01> mensaje $it á 🙂" }
            f.writeText(lines.joinToString("\n") + "\n", Charsets.UTF_8)
            val first = HistoryFiles.page(root, path, "DemoNick01 2026-10-27", 0)
            assertEquals(100, first.lines.size); assertEquals(100L, first.next)
            val second = HistoryFiles.page(root, path, "", first.next!!)
            assertEquals(lines[100], second.lines.first())
            assertEquals(listOf(lines[204]), HistoryFiles.page(root, path, "mensaje 204", 0).lines)
            val out = ByteArrayOutputStream(); HistoryFiles.export(root, setOf(path), out)
            assertEquals(listOf(path), HistoryFiles.stage(root, ByteArrayInputStream(out.toByteArray()), staging))
            assertArrayEquals(f.readBytes(), HistoryFiles.resolve(staging, path).readBytes())
            assertEquals(f.length(), HistoryFiles.list(root).single().bytes)
        } finally { root.deleteRecursively(); staging.deleteRecursively() }
    }
    @Test fun restoreRejectsTraversalAndForeignOrMalformedArchivesBeforeMerge() {
        val root = Files.createTempDirectory("history-root").toFile()
        val stage = Files.createTempDirectory("history-stage").toFile()
        try {
            listOf("../outside.txt", "network/../../outside.txt", "/outside.txt", "network/data.bin", "network/../outside.txt", "network\\file.txt").forEach { path ->
                val out = ByteArrayOutputStream()
                ZipOutputStream(out).use { it.putNextEntry(ZipEntry(path)); it.write("bad".toByteArray()); it.closeEntry() }
                try { HistoryFiles.stage(root, ByteArrayInputStream(out.toByteArray()), stage); fail("Accepted $path") } catch (_: IllegalArgumentException) { }
            }
            assertTrue(HistoryFiles.list(root).isEmpty())
        } finally { root.deleteRecursively(); stage.deleteRecursively() }
    }

    @Test fun dreamsMembershipEventsKeepFullMasksWithoutFabricatingUnknownHosts() {
        assertEquals("→ JOIN DemoNick01 (demo@2001:db8::1234)", stripIrcFormatting(membershipEventLine(MembershipEvent.JOIN, "DemoNick01", "demo@2001:db8::1234", "#demo")))
        assertEquals("← PART DemoNick01 (demo@host.example.invalid) [Hasta luego 🙂]", stripIrcFormatting(membershipEventLine(MembershipEvent.PART, "DemoNick01", "demo@host.example.invalid", "#demo", "Hasta luego 🙂")))
        assertEquals("← QUIT DemoNick01", stripIrcFormatting(membershipEventLine(MembershipEvent.QUIT, "DemoNick01", null)))
        assertEquals("→ JOIN DemoNick01", membershipEventLine(MembershipEvent.JOIN, "DemoNick01", "demo@host.invalid", "#demo", hideHostnames = true, colors = false))
    }

    @Test fun dreamsMembershipEventsColorOnlyTheArrowAndKeepReasonsGrey() {
        for (event in MembershipEvent.entries) {
            val text = membershipEventLine(event, "DemoNick01", "demo@192.0.2.1", reason = "\u000304Motivo\u000f")
            assertTrue(text.startsWith("\u0003${event.color}${event.arrow}\u000314 "))
            assertTrue(text.endsWith("[Motivo]\u000f"))
            assertEquals(listOf(3, 6, 4), MembershipEvent.entries.map { it.color })
        }
    }

    @Test fun dreamsDuplicateNetworkCreatesIndependentDraftAndUniqueNames() {
        val original = com.boxlabs.hexdroid.data.NetworkProfile(
            id = "demo-source", name = "Red demo", host = "irc.example.invalid", port = 6697,
            useTls = true, allowInvalidCerts = false, nick = "OwnDemoNick", altNick = "OwnDemoNick_",
            username = "demo", realname = "Identidad de prueba", saslEnabled = true,
            saslMechanism = SaslMechanism.PLAIN, caps = CapPrefs(), autoJoin = emptyList(),
            autoConnect = true, tlsClientCertId = "source-cert", tlsTofuFingerprint = "source-pin",
        )
        val copy = duplicateNetworkProfile(original, listOf("Red demo", "Red demo — copia", "Red demo — copia 2"))
        assertEquals("Red demo — copia 3", copy.name)
        assertNotEquals(original.id, copy.id)
        assertNotEquals(copy.id, duplicateNetworkProfile(original, emptyList()).id)
        assertEquals(original.host, copy.host); assertEquals(original.nick, copy.nick)
        assertEquals(original.caps, copy.caps); assertEquals(original.autoJoin, copy.autoJoin)
        assertFalse(copy.autoConnect); assertNull(copy.tlsClientCertId); assertNull(copy.tlsTofuFingerprint)
        assertTrue(original.autoConnect); assertEquals("source-cert", original.tlsClientCertId)
    }

    @Test fun dreamsExplicitlyImportedPrivateScriptsKeepTheirOwnPolicyAndCommands() {
        val commands = mutableMapOf<String, Any>()
        val events = mutableMapOf<String, MutableList<Any>>()
        val capabilities = mutableListOf<String>()
        val echoes = mutableListOf<String>()
        val launchers = mutableListOf<List<String>>()
        val callbacks = java.lang.reflect.Proxy.newProxyInstance(
            com.boxlabs.hexdroid.script.EngineCallbacks::class.java.classLoader,
            arrayOf(com.boxlabs.hexdroid.script.EngineCallbacks::class.java),
        ) { _, method, args ->
            when (method.name) {
                "registerCommand" -> { commands[args[0] as String] = args[1]; Unit }
                "registerEvent" -> { events.getOrPut(args[0] as String) { mutableListOf() }.add(args[1]); Unit }
                "capability" -> { capabilities += args[0] as String; "true" }
                "uiIntent" -> { if (args[0] == "sidebar") launchers += (args[1] as List<*>).map { it.toString() }; Unit }
                "echo" -> { echoes += args[2] as String; Unit }
                "log" -> Unit
                else -> null
            }
        } as com.boxlabs.hexdroid.script.EngineCallbacks
        val backend = com.boxlabs.hexdroid.script.HexScriptBackend()
        backend.start(callbacks, com.boxlabs.hexdroid.script.Budget(100_000, 1000))
        val source = listOf(File("src/androidTest/assets/retired-private-requests.hex"), File("app/src/androidTest/assets/retired-private-requests.hex")).first { it.exists() }.readText()
        assertNull(backend.loadScript("imported.hex", "alias privados {\n echo -a IMPORTADO_CONSERVADO\n}"))
        assertNull(backend.loadScript("solicitudes-pv.hex", source))
        backend.dispatchNotify(events.getValue("LOAD"), com.boxlabs.hexdroid.script.EventData(network = "demo-network", buffer = "#demo"))
        assertEquals(2, launchers.size)
        assertEquals(listOf("dreamspv", "dreamspvconfig"), launchers.map { it[2] })
        val request = com.boxlabs.hexdroid.script.EventData(network = "demo-network", buffer = "DemoNick01", from = "DemoNick01", text = "Prueba 🙂", isPrivate = true)
        assertTrue(backend.dispatchTransform(events.getValue("PRIVATE_REQUEST"), request).halted)
        backend.runCommand(commands.getValue("dreamspv"), "", "demo-network", "#demo")
        backend.runCommand(commands.getValue("dreamspvconfig"), "", "demo-network", "#demo")
        assertEquals(listOf("private.open", "private.settings"), capabilities)
        backend.runCommand(commands.getValue("privados"), "", "demo-network", "#demo")
        assertEquals(listOf("IMPORTADO_CONSERVADO"), echoes)
        backend.reset(); events.clear()
        val dollar = '$'
        assertNull(backend.loadScript("custom-policy.hex", "on PRIVATE_REQUEST {\n if (${dollar}nick == AllowedDemoNick) { return }\n halt\n}"))
        assertFalse(backend.dispatchTransform(events.getValue("PRIVATE_REQUEST"), request.copy(from = "AllowedDemoNick")).halted)
        assertTrue(backend.dispatchTransform(events.getValue("PRIVATE_REQUEST"), request).halted)
    }
}
