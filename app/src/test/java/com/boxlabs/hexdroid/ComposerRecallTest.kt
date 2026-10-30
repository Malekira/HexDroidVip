/* HexDroidIRC — Copyright (C) 2026 boxlabs. GPL-3.0-or-later. */
package com.boxlabs.hexdroid

import org.junit.Assert.*
import org.junit.Test

class ComposerRecallTest {
    @Test fun successivePressesVisitExactlyTheLastTenAndStopWithoutWrapping() {
        val history = (1..15).map { "Mensaje de prueba $it" }
        val recall = ComposerRecall()
        var draft = RecallDraft("")
        for (number in 15 downTo 6) {
            draft = recall.previous(history, draft)!!
            assertEquals("Mensaje de prueba $number", draft.text)
            assertEquals(draft.text.length, draft.start)
        }
        repeat(3) { assertEquals(draft, recall.previous(history, draft)) }
    }

    @Test fun keyboardUsesEmptyFieldThenCyclesAndEditingRestoresNormalDeletion() {
        val recall = ComposerRecall()
        val history = listOf("Anterior á 🙂", "Último mensaje")
        var result = recall.keyboardDelete(history, RecallDraft(""), 1000)
        assertTrue(result.consumed); assertEquals("Último mensaje", result.draft!!.text)
        result = recall.keyboardDelete(history, result.draft!!, 1300)
        assertEquals("Anterior á 🙂", result.draft!!.text)
        recall.edited()
        assertFalse(recall.keyboardDelete(history, result.draft!!, 1600).consumed)
    }

    @Test fun draftsIncludingSelectionCanBeRestoredWithoutSendingOrChangingHistory() {
        val recall = ComposerRecall()
        val history = listOf("Primero", "Segundo")
        val original = RecallDraft("Texto que no envié 🙂", 3, 8)
        val last = recall.previous(history, original)!!
        assertEquals("Segundo", last.text)
        assertEquals("Primero", recall.previous(history, last)!!.text)
        assertEquals(original, recall.restoreDraft())
        assertNull(recall.restoreDraft())
        assertEquals(listOf("Primero", "Segundo"), history)
        assertEquals("Segundo", recall.previous(history, original)!!.text)
    }

    @Test fun holdingDeleteToEraseTypedTextNeverReintroducesHistory() {
        val recall = ComposerRecall()
        val history = listOf("Mensaje anterior")
        assertFalse(recall.keyboardDelete(history, RecallDraft("abc"), 1000).consumed)
        recall.edited()
        assertFalse(recall.keyboardDelete(history, RecallDraft(""), 1100).consumed)
        assertFalse(recall.keyboardDelete(history, RecallDraft(""), 1400).consumed)
        assertFalse(recall.keyboardDelete(history, RecallDraft(""), 1700, repeat = true).consumed)
        val result = recall.keyboardDelete(history, RecallDraft(""), 2100)
        assertTrue(result.consumed); assertEquals("Mensaje anterior", result.draft!!.text)
        assertTrue(recall.keyboardDelete(history, result.draft!!, 2150, repeat = true).consumed)
    }

    @Test fun emptyHistoryIsSafeAndConversationsKeepTheirOwnRecallState() {
        val room = ComposerRecall()
        val privateChat = ComposerRecall()
        assertNull(room.previous(emptyList(), RecallDraft("")))
        assertFalse(room.keyboardDelete(emptyList(), RecallDraft(""), 1000).consumed)
        assertEquals("Sala", room.previous(listOf("Sala"), RecallDraft("borrador sala"))!!.text)
        assertEquals("Privado", privateChat.previous(listOf("Privado"), RecallDraft("borrador PV"))!!.text)
        assertEquals("borrador sala", room.restoreDraft()!!.text)
        assertEquals("borrador PV", privateChat.restoreDraft()!!.text)
        room.reset()
        assertNull(room.restoreDraft())
    }
}
