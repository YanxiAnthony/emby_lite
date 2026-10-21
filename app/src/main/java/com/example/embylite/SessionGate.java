package com.example.embylite;

/** Invalidates outstanding work after logout, replacement login or destruction. */
final class SessionGate {
    private volatile int generation;

    int current() { return generation; }
    int invalidate() { return ++generation; }
    boolean accepts(int captured) { return generation == captured; }
}
