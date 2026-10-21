package com.example.embylite;

import org.junit.Test;
import static org.junit.Assert.*;
import com.example.embylite.BrowseState.LibraryMode;
import com.example.embylite.BrowseState.RandomScope;

public class BrowseStateTest {
    @Test public void searchHasSameRandomScopeRegardlessOfStartingCategory() {
        for (LibraryMode mode : LibraryMode.values()) {
            BrowseState state = new BrowseState();
            int request = state.navigate(mode, null, "movie");
            state.finish(request, "");
            assertEquals(RandomScope.CURRENT, state.randomScope());
            assertTrue(state.canPlay());
        }
    }

    @Test public void enteringFavoritesCannotPlayPreviousCategoryWhileLoading() {
        BrowseState state = new BrowseState();
        state.finish(state.navigate(LibraryMode.ALL, null, ""), "");
        assertTrue(state.canPlay());
        int request = state.navigate(LibraryMode.FAVORITES, null, "");
        assertFalse(state.canPlay());
        assertFalse(state.ready);
        assertEquals(RandomScope.FAVORITES, state.randomScope());
        state.finish(request, "");
        assertTrue(state.canPlay());
    }

    @Test public void lateSearchCannotReplaceNewCategory() {
        BrowseState state = new BrowseState();
        int old = state.navigate(LibraryMode.ALL, null, "first");
        int current = state.navigate(LibraryMode.FAVORITES, null, "");
        assertFalse(state.finish(old, ""));
        assertTrue(state.loading);
        assertTrue(state.finish(current, ""));
        assertEquals(LibraryMode.FAVORITES, state.mode);
    }

    @Test public void refreshFailureRetainsContentAndScrollForRetry() {
        BrowseState state = new BrowseState();
        state.finish(state.navigate(LibraryMode.ADDED, null, ""), "");
        state.position = 18;
        state.offset = -35;
        state.finish(state.beginLoad(), "offline");
        assertTrue(state.ready);
        assertEquals(18, state.position);
        assertEquals(-35, state.offset);
        assertEquals("offline", state.error);
        state.finish(state.beginLoad(), "");
        assertTrue(state.error.isEmpty());
    }

    @Test public void failedInitialRequestDoesNotEnableRandomPlayback() {
        BrowseState state = new BrowseState();
        state.finish(state.navigate(LibraryMode.FAVORITES, null, ""), "offline");
        assertFalse(state.canPlay());
    }

    @Test public void existingNonSearchRandomRulesArePreserved() {
        BrowseState state = new BrowseState();
        for (LibraryMode mode : new LibraryMode[]{LibraryMode.RECENT, LibraryMode.ADDED,
                LibraryMode.COLLECTIONS, LibraryMode.COLLECTION_ITEMS}) {
            state.navigate(mode, null, "");
            assertEquals(RandomScope.GLOBAL, state.randomScope());
        }
    }

    @Test public void leavingSearchResetsQueryAndScroll() {
        BrowseState state = new BrowseState();
        state.navigate(LibraryMode.ALL, null, "film");
        state.position = 40;
        state.offset = -17;
        state.navigate(LibraryMode.ALL, null, "");
        assertFalse(state.isSearching());
        assertEquals(0, state.position);
        assertEquals(0, state.offset);
    }
}
