package com.example.embylite;

/** Navigation and request ownership, independent of Android views. */
final class BrowseState {
    enum LibraryMode { ALL, RECENT, FAVORITES, COLLECTIONS, COLLECTION_ITEMS }
    enum RandomScope { CURRENT, FAVORITES, GLOBAL }

    LibraryMode mode = LibraryMode.RECENT;
    Movie collection;
    String query = "";
    int version;
    boolean loading;
    boolean ready;
    String error = "";
    int position;
    int offset;

    boolean isSearching() { return !query.isEmpty(); }

    int navigate(LibraryMode next, Movie parent, String search) {
        mode = next;
        collection = parent;
        query = search;
        position = 0;
        offset = 0;
        ready = false;
        return beginLoad();
    }

    int beginLoad() {
        loading = true;
        error = "";
        return ++version;
    }

    boolean finish(int request, String failure) {
        if (request != version) return false;
        loading = false;
        error = failure;
        if (failure.isEmpty()) ready = true;
        return true;
    }

    RandomScope randomScope() {
        if (isSearching()) return RandomScope.CURRENT;
        if (mode == LibraryMode.FAVORITES) return RandomScope.FAVORITES;
        return mode == LibraryMode.ALL ? RandomScope.CURRENT : RandomScope.GLOBAL;
    }

    boolean canPlay() { return ready && !loading; }
}
