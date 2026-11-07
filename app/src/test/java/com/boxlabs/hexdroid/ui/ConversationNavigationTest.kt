package com.boxlabs.hexdroid.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConversationNavigationTest {
    private val tabs = listOf("net::*server*", "net::#one", "net::#two", "net::whois")

    @Test fun horizontalSwipesFollowTheVisibleTabOrderInBothDirections() {
        assertEquals("net::#two", adjacentConversation(tabs, "net::#one", -80f, 56f))
        assertEquals("net::#one", adjacentConversation(tabs, "net::#two", 80f, 56f))
        assertEquals("net::whois", adjacentConversation(tabs, "net::#two", -56f, 56f))
    }

    @Test fun shortDragsAndTapsDoNotChangeConversations() {
        for (distance in listOf(0f, -55f, 55f, Float.NaN)) {
            assertNull(adjacentConversation(tabs, "net::#one", distance, 56f))
        }
    }

    @Test fun edgesEmptyTabsAndRemovedSelectionDoNotWrapOrCrash() {
        assertNull(adjacentConversation(tabs, tabs.first(), 100f, 56f))
        assertNull(adjacentConversation(tabs, tabs.last(), -100f, 56f))
        assertNull(adjacentConversation(emptyList(), "net::#one", -100f, 56f))
        assertNull(adjacentConversation(tabs, "net::#closed", -100f, 56f))
    }
}
