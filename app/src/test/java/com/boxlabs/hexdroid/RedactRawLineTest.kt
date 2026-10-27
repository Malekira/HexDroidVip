/*
* HexDroidIRC - An IRC Client for Android
* Copyright (C) 2026 boxlabs
*
* This program is free software: you can redistribute it and/or modify
* it under the terms of the GNU General Public License as published by
* the Free Software Foundation, either version 3 of the License, or
* (at your option) any later version.
*
* This program is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
* GNU General Public License for more details.
*
* You should have received a copy of the GNU General Public License
* along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/

package com.boxlabs.hexdroid

import org.junit.Assert.assertEquals
import org.junit.Test

class RedactRawLineTest {

    @Test
    fun servicesLoginsAreHidden() {
        assertEquals("PRIVMSG NickServ :IDENTIFY <hidden>", redactRawLine("PRIVMSG NickServ :IDENTIFY hunter2"))
        assertEquals("PRIVMSG NickServ :ID <hidden>", redactRawLine("PRIVMSG NickServ :ID hunter2"))
        assertEquals("NS ID <hidden>", redactRawLine("NS ID hunter2"))
        assertEquals("PRIVMSG #chan :ID card", redactRawLine("PRIVMSG #chan :ID card"))
    }

    @Test
    fun channelKeysAreHidden() {
        assertEquals("JOIN #a,#b <hidden>", redactRawLine("JOIN #a,#b k1,k2"))
        assertEquals("JOIN #a", redactRawLine("JOIN #a"))
        // A received extended-join carries an account, not a key.
        assertEquals(":n!u@h JOIN #a acct :Real Name", redactRawLine(":n!u@h JOIN #a acct :Real Name"))
        assertEquals("MODE #a +k <hidden>", redactRawLine("MODE #a +k secret"))
        assertEquals(":op!u@h MODE #a +lk <hidden>", redactRawLine(":op!u@h MODE #a +lk 10 secret"))
        assertEquals("MODE #a +l 10", redactRawLine("MODE #a +l 10"))
        assertEquals("MODE #a", redactRawLine("MODE #a"))
        assertEquals(":srv 324 me #a +knt <hidden>", redactRawLine(":srv 324 me #a +knt secret"))
        assertEquals(":srv 324 me #a +nt", redactRawLine(":srv 324 me #a +nt"))
    }
}
