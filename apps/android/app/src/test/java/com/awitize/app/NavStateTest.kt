package com.awitize.app

import com.awitize.app.ui.NavState
import com.awitize.app.ui.PageCache
import com.awitize.app.ui.Screen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// The navigation model: every bottom-bar tab owns its own back stack, so
// "Search → album → back" returns to those exact results and switching tabs
// never wipes what another tab was showing.
class NavStateTest {

    @Test
    fun startsOnHomeWithEmptyStacks() {
        val nav = NavState()
        assertEquals(0, nav.tab)
        assertTrue(nav.current.isEmpty())
        assertFalse(nav.pop())
    }

    @Test
    fun pushAndPopWithinATab() {
        val nav = NavState()
        nav.select(2) // Search tab
        nav.push(Screen.Album("MPREb_album"))
        nav.push(Screen.Artist("UC_artist"))
        assertEquals(2, nav.current.size)
        assertTrue(nav.pop())
        assertEquals(listOf<Screen>(Screen.Album("MPREb_album")), nav.current.toList())
        assertTrue(nav.pop())
        assertTrue(nav.current.isEmpty())
        assertFalse(nav.pop())
    }

    @Test
    fun tabsKeepIndependentStacks() {
        val nav = NavState()
        nav.select(2)
        nav.push(Screen.Album("A"))
        nav.select(1) // Explore tab — untouched
        assertTrue(nav.current.isEmpty())
        nav.push(Screen.Mood("Chill", "params"))
        nav.select(2) // back to Search: its album is still there
        assertEquals(listOf<Screen>(Screen.Album("A")), nav.current.toList())
        nav.select(1)
        assertEquals(listOf<Screen>(Screen.Mood("Chill", "params")), nav.current.toList())
    }

    @Test
    fun reselectingTheSameTabPopsToRoot() {
        val nav = NavState()
        nav.select(3) // Library tab
        nav.push(Screen.LocalPlaylist("pl-1"))
        nav.select(3) // tap Library again
        assertTrue(nav.current.isEmpty())
        assertEquals(3, nav.tab)
    }

    @Test
    fun selectingADifferentTabDoesNotClear() {
        val nav = NavState()
        nav.select(0)
        nav.push(Screen.Liked)
        nav.select(2)
        assertEquals(2, nav.tab)
        nav.select(0)
        assertEquals(listOf<Screen>(Screen.Liked), nav.current.toList())
    }
}

// Pages are cached by key so returning to an album/artist is instant, and the
// caches don't leak across unrelated keys.
class PageCacheTest {

    @Test
    fun storesAndReturnsPagesByKey() {
        val cache = PageCache()
        assertNull(cache.get("album:1"))
        cache.put("album:1", "loaded-album")
        assertEquals("loaded-album", cache.get("album:1"))
        assertNull(cache.get("album:2"))
    }

    @Test
    fun overwritesOnRefetch() {
        val cache = PageCache()
        cache.put("artist:1", 1)
        cache.put("artist:1", 2)
        assertEquals(2, cache.get("artist:1"))
    }
}
