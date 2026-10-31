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

    @Test fun editingResetsTheButtonToTheLatestMessage() {
        val recall = ComposerRecall()
        val entries = listOf("Primero", "Segundo")
        recall.previous(entries, RecallDraft("borrador"))
        recall.previous(entries, RecallDraft("Segundo"))
        recall.edited()
        assertEquals("Segundo", recall.previous(entries, RecallDraft("editado"))!!.text)
        assertEquals("editado", recall.restoreDraft()!!.text)
    }

    @Test fun emptyHistoryIsSafeAndConversationsKeepTheirOwnRecallState() {
        val room = ComposerRecall()
        val privateChat = ComposerRecall()
        assertNull(room.previous(emptyList(), RecallDraft("")))
        assertEquals("Sala", room.previous(listOf("Sala"), RecallDraft("borrador sala"))!!.text)
        assertEquals("Privado", privateChat.previous(listOf("Privado"), RecallDraft("borrador PV"))!!.text)
        assertEquals("borrador sala", room.restoreDraft()!!.text)
        assertEquals("borrador PV", privateChat.restoreDraft()!!.text)
        room.reset()
        assertNull(room.restoreDraft())
    }
}
