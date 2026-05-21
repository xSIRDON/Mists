package io.github.xsirdon.mists.boundary;

public enum BoundaryBand {
    SAFE,      // dist < r - (HOSTILE + WALL inset)
    HOSTILE,   // inside the debuff band, before the hard wall
    WALL,      // inside the hard-wall zone (server clamps movement here)
    VISUAL,    // inside the visual mist band (purely cosmetic)
    BEYOND     // far past the mist; reachable only if wall failed (sanity)
}
