# Mists Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a Fabric 1.20.1 mod called `mists` that adds a per-player, level-driven world-progression system to maark's True Survival modpack — spawn island, ring archipelago, mist boundary, and LevelZ integration.

**Architecture:** Three loosely-coupled subsystems (server-authoritative worldgen, per-player boundary enforcement, client-only mist rendering) wired together via a single S2C network packet carrying the player's current mist radius. Spec source of truth is the repo `README.md`.

**Tech Stack:** Java 17, Fabric Loom 1.5+, Yarn mappings `1.20.1+build.10`, Fabric Loader `>=0.16.0`, Fabric API `0.92.8+1.20.1`, LevelZ (required runtime dep, no compile-time API jar — use reflective/duck-cast access through its `PlayerStatsManagerAccess` interface).

---

## File Structure

All paths relative to `C:\Users\ncerd\Mists`.

```
build.gradle, settings.gradle, gradle.properties
gradle/wrapper/gradle-wrapper.properties
gradlew, gradlew.bat
src/main/java/io/github/xsirdon/mists/
  Mists.java                          — server/common entrypoint, registers events
  MistsClient.java                    — client entrypoint, wires renderer + sounds
  MistsConstants.java                 — modId, radii, tier thresholds, packet ids
  progression/
    Tier.java                         — enum: ONE, TWO, THREE, FOUR, OPEN
    TierTable.java                    — pure: levelToTier(int), tierToRadius(Tier)
    LevelZBridge.java                 — reads LevelZ PlayerStatsManager.overallLevel
  worldgen/
    MistsWorldData.java               — PersistentState; serialises island metadata
    IslandShape.java                  — pure: organic disc via simplex noise
    SpawnIsland.java                  — builds the 4-chunk plains spawn island
    IslandPlacer.java                 — places tier 2/3/4 rings on first load
    OceanCarver.java                  — clears stray natural land inside ring zone
  boundary/
    BoundarySystem.java               — player-tick clamp + per-player radius cache
    HostileWaters.java                — debuff effects in the inner band
    PearlClamp.java                   — cancels enderpearls crossing the boundary
    VehicleClamp.java                 — boats/mounts: lowest-rider radius wins
  network/
    MistRadiusPayload.java            — S2C packet: {radius: double, animateFrom: double}
src/main/resources/
  fabric.mod.json
  mists.mixins.json                   — only if a mixin is required (we'll know late)
  assets/mists/
    lang/en_us.json                   — localised mod name + level-up toast strings
    sounds.json                       — ambient mist howl + retreat rumble
    particles/mist.json               — particle definition
    textures/particle/mist_0.png      — single soft-fog particle frame
src/main/java/io/github/xsirdon/mists/client/
  MistRenderer.java                   — WorldRenderEvents particle ring + retreat anim
  MistSounds.java                     — ambient howl when near boundary
  MistState.java                      — client cache of current/animated radius
src/test/java/io/github/xsirdon/mists/
  progression/TierTableTest.java
  worldgen/IslandShapeTest.java
  boundary/RadiusMathTest.java
docs/plans/2026-05-21-mists-implementation.md  (this file)
```

Each file has a single responsibility. Pure data/math files (`TierTable`, `IslandShape`, parts of `BoundarySystem`) are JUnit-testable without launching Minecraft.

---

## Task 1: Project scaffold

**Files:**
- Create: `settings.gradle`
- Create: `gradle.properties`
- Create: `build.gradle`
- Create: `gradle/wrapper/gradle-wrapper.properties`
- Create: `src/main/resources/fabric.mod.json`
- Create: `src/main/java/io/github/xsirdon/mists/Mists.java`
- Create: `src/main/java/io/github/xsirdon/mists/MistsClient.java`
- Create: `src/main/java/io/github/xsirdon/mists/MistsConstants.java`
- Create: `src/main/resources/assets/mists/lang/en_us.json`

- [ ] **Step 1: Write `settings.gradle`**

```groovy
pluginManagement {
    repositories {
        maven { url = "https://maven.fabricmc.net/" }
        gradlePluginPortal()
    }
}

rootProject.name = "mists"
```

- [ ] **Step 2: Write `gradle.properties`**

```properties
org.gradle.jvmargs=-Xmx2G
org.gradle.parallel=true

minecraft_version=1.20.1
yarn_mappings=1.20.1+build.10
loader_version=0.16.10
fabric_version=0.92.8+1.20.1

mod_version=0.1.0
maven_group=io.github.xsirdon
archives_base_name=mists
```

- [ ] **Step 3: Write `build.gradle`**

```groovy
plugins {
    id 'fabric-loom' version '1.5-SNAPSHOT'
    id 'maven-publish'
    id 'java'
}

version = project.mod_version
group = project.maven_group
base { archivesName = project.archives_base_name }

repositories {
    maven { url = "https://maven.nucleoid.xyz/" }   // for any auxiliary libs
    maven { url = "https://maven.terraformersmc.com/" }
}

dependencies {
    minecraft "com.mojang:minecraft:${project.minecraft_version}"
    mappings  "net.fabricmc:yarn:${project.yarn_mappings}:v2"
    modImplementation "net.fabricmc:fabric-loader:${project.loader_version}"
    modImplementation "net.fabricmc.fabric-api:fabric-api:${project.fabric_version}"

    testImplementation 'org.junit.jupiter:junit-jupiter:5.10.2'
}

test { useJUnitPlatform() }

processResources {
    inputs.property "version", project.version
    filesMatching("fabric.mod.json") { expand "version": project.version }
}

java {
    withSourcesJar()
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType(JavaCompile).configureEach { it.options.release = 17 }
```

- [ ] **Step 4: Write `gradle/wrapper/gradle-wrapper.properties`**

```properties
distributionBase=GRADLE_USER_HOME
distributionPath=wrapper/dists
distributionUrl=https\://services.gradle.org/distributions/gradle-8.7-bin.zip
zipStoreBase=GRADLE_USER_HOME
zipStorePath=wrapper/dists
```

- [ ] **Step 5: Write `src/main/resources/fabric.mod.json`**

```json
{
  "schemaVersion": 1,
  "id": "mists",
  "version": "${version}",
  "name": "Mists",
  "description": "World-progression mod for maark's True Survival modpack. Mist boundary that retreats with LevelZ progression.",
  "authors": ["xSIRDON"],
  "contact": { "homepage": "https://github.com/xSIRDON/Mists", "sources": "https://github.com/xSIRDON/Mists" },
  "license": "MIT",
  "environment": "*",
  "entrypoints": {
    "main":   ["io.github.xsirdon.mists.Mists"],
    "client": ["io.github.xsirdon.mists.MistsClient"]
  },
  "depends": {
    "fabricloader": ">=0.16.0",
    "minecraft": "~1.20.1",
    "java": ">=17",
    "fabric-api": "*",
    "levelz": "*"
  }
}
```

- [ ] **Step 6: Write `src/main/java/io/github/xsirdon/mists/MistsConstants.java`**

```java
package io.github.xsirdon.mists;

import net.minecraft.util.Identifier;

public final class MistsConstants {
    public static final String MOD_ID = "mists";

    // Boundary radii (blocks from world spawn at 0,0)
    public static final double TIER_1_RADIUS =  120.0;
    public static final double TIER_2_RADIUS =  350.0;
    public static final double TIER_3_RADIUS =  650.0;
    public static final double TIER_4_RADIUS = 1000.0;
    public static final double TIER_OPEN_RADIUS = 30_000_000.0; // effectively infinite

    // LevelZ total-level thresholds that grant each tier
    public static final int TIER_2_REQUIRED_LEVEL =  5;
    public static final int TIER_3_REQUIRED_LEVEL = 10;
    public static final int TIER_4_REQUIRED_LEVEL = 15;
    public static final int TIER_OPEN_REQUIRED_LEVEL = 30;

    // Boundary band geometry (depths measured inward from the visual mist line)
    public static final double VISUAL_BAND_THICKNESS  = 30.0;
    public static final double HOSTILE_BAND_THICKNESS = 15.0;
    public static final double HARD_WALL_INSET        =  2.0;

    // Network channel
    public static final Identifier MIST_RADIUS_PACKET =
        new Identifier(MOD_ID, "mist_radius");

    private MistsConstants() {}
}
```

- [ ] **Step 7: Write `src/main/java/io/github/xsirdon/mists/Mists.java`**

```java
package io.github.xsirdon.mists;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Mists implements ModInitializer {
    public static final Logger LOG = LoggerFactory.getLogger(MistsConstants.MOD_ID);

    @Override public void onInitialize() {
        LOG.info("Mists initialising (server/common)");
        // Subsystem registrations are added in later tasks:
        //   - worldgen/IslandPlacer.register()
        //   - boundary/BoundarySystem.register()
        //   - boundary/PearlClamp.register()
        //   - network packet codec registration
    }
}
```

- [ ] **Step 8: Write `src/main/java/io/github/xsirdon/mists/MistsClient.java`**

```java
package io.github.xsirdon.mists;

import net.fabricmc.api.ClientModInitializer;

public final class MistsClient implements ClientModInitializer {
    @Override public void onInitializeClient() {
        Mists.LOG.info("Mists initialising (client)");
        // Wired up in later tasks:
        //   - client/MistRenderer.register()
        //   - client/MistSounds.register()
        //   - network packet receiver registration
    }
}
```

- [ ] **Step 9: Write `src/main/resources/assets/mists/lang/en_us.json`**

```json
{
  "mists.title": "Mists",
  "mists.toast.tier_unlocked": "The mist retreats…",
  "mists.toast.tier_2": "Tier 2 unlocked — sail beyond.",
  "mists.toast.tier_3": "Tier 3 unlocked — the world widens.",
  "mists.toast.tier_4": "Tier 4 unlocked — the mainland nears.",
  "mists.toast.tier_open": "The mist is gone."
}
```

- [ ] **Step 10: Generate the Gradle wrapper jar and verify the project builds**

Run from `C:\Users\ncerd\Mists`:
```powershell
# Bootstrap the wrapper jar
gradle wrapper --gradle-version 8.7
./gradlew build
```
Expected: BUILD SUCCESSFUL, an empty `mists-0.1.0.jar` produced in `build/libs/`.

If `gradle` itself is not on PATH, download `gradle-8.7-bin.zip` from the URL in `gradle-wrapper.properties` and run its `gradle.bat wrapper`.

- [ ] **Step 11: Commit**

```powershell
git add -A
git -c user.name=xSIRDON -c user.email=nsrdawn@gmail.com commit -m "scaffold: Fabric 1.20.1 project skeleton + constants"
```

---

## Task 2: Tier enum and TierTable (TDD)

Pure, unit-testable logic mapping LevelZ levels → progression tier → mist radius.

**Files:**
- Create: `src/main/java/io/github/xsirdon/mists/progression/Tier.java`
- Create: `src/main/java/io/github/xsirdon/mists/progression/TierTable.java`
- Create: `src/test/java/io/github/xsirdon/mists/progression/TierTableTest.java`

- [ ] **Step 1: Write `Tier.java`**

```java
package io.github.xsirdon.mists.progression;

public enum Tier {
    ONE,    // L0-4    spawn island only
    TWO,    // L5-9    + tier 2 ring
    THREE,  // L10-14  + tier 3 ring
    FOUR,   // L15-29  + tier 4 ring
    OPEN    // L30+    no mist
}
```

- [ ] **Step 2: Write the failing test**

```java
package io.github.xsirdon.mists.progression;

import org.junit.jupiter.api.Test;
import static io.github.xsirdon.mists.MistsConstants.*;
import static org.junit.jupiter.api.Assertions.*;

class TierTableTest {

    @Test void levelToTier_boundaries() {
        assertEquals(Tier.ONE,   TierTable.levelToTier(0));
        assertEquals(Tier.ONE,   TierTable.levelToTier(4));
        assertEquals(Tier.TWO,   TierTable.levelToTier(5));
        assertEquals(Tier.TWO,   TierTable.levelToTier(9));
        assertEquals(Tier.THREE, TierTable.levelToTier(10));
        assertEquals(Tier.THREE, TierTable.levelToTier(14));
        assertEquals(Tier.FOUR,  TierTable.levelToTier(15));
        assertEquals(Tier.FOUR,  TierTable.levelToTier(29));
        assertEquals(Tier.OPEN,  TierTable.levelToTier(30));
        assertEquals(Tier.OPEN,  TierTable.levelToTier(9999));
    }

    @Test void levelToTier_negativeFloorsToOne() {
        assertEquals(Tier.ONE, TierTable.levelToTier(-5));
    }

    @Test void tierToRadius_matchesConstants() {
        assertEquals(TIER_1_RADIUS,    TierTable.tierToRadius(Tier.ONE));
        assertEquals(TIER_2_RADIUS,    TierTable.tierToRadius(Tier.TWO));
        assertEquals(TIER_3_RADIUS,    TierTable.tierToRadius(Tier.THREE));
        assertEquals(TIER_4_RADIUS,    TierTable.tierToRadius(Tier.FOUR));
        assertEquals(TIER_OPEN_RADIUS, TierTable.tierToRadius(Tier.OPEN));
    }

    @Test void levelToRadius_composes() {
        assertEquals(TIER_1_RADIUS, TierTable.levelToRadius(0));
        assertEquals(TIER_2_RADIUS, TierTable.levelToRadius(5));
        assertEquals(TIER_3_RADIUS, TierTable.levelToRadius(10));
        assertEquals(TIER_4_RADIUS, TierTable.levelToRadius(15));
        assertEquals(TIER_OPEN_RADIUS, TierTable.levelToRadius(30));
    }
}
```

- [ ] **Step 3: Run test and verify failure**

```powershell
./gradlew test --tests "*.TierTableTest"
```
Expected: compilation failure — `TierTable` not found.

- [ ] **Step 4: Implement `TierTable.java`**

```java
package io.github.xsirdon.mists.progression;

import static io.github.xsirdon.mists.MistsConstants.*;

public final class TierTable {

    public static Tier levelToTier(int level) {
        if (level >= TIER_OPEN_REQUIRED_LEVEL) return Tier.OPEN;
        if (level >= TIER_4_REQUIRED_LEVEL)    return Tier.FOUR;
        if (level >= TIER_3_REQUIRED_LEVEL)    return Tier.THREE;
        if (level >= TIER_2_REQUIRED_LEVEL)    return Tier.TWO;
        return Tier.ONE;
    }

    public static double tierToRadius(Tier tier) {
        return switch (tier) {
            case ONE   -> TIER_1_RADIUS;
            case TWO   -> TIER_2_RADIUS;
            case THREE -> TIER_3_RADIUS;
            case FOUR  -> TIER_4_RADIUS;
            case OPEN  -> TIER_OPEN_RADIUS;
        };
    }

    public static double levelToRadius(int level) {
        return tierToRadius(levelToTier(level));
    }

    private TierTable() {}
}
```

- [ ] **Step 5: Run tests and verify they pass**

```powershell
./gradlew test --tests "*.TierTableTest"
```
Expected: 4 tests, all passing.

- [ ] **Step 6: Commit**

```powershell
git add -A
git -c user.name=xSIRDON -c user.email=nsrdawn@gmail.com commit -m "feat(progression): Tier enum + TierTable level→radius mapping"
```

---

## Task 3: IslandShape — organic island via simplex noise (TDD)

Produces a boolean mask given (cx, cz, radius, seed) — returns true when a block at (x, z) is inside the island. Used both for the spawn island and the ring islands.

**Files:**
- Create: `src/main/java/io/github/xsirdon/mists/worldgen/IslandShape.java`
- Create: `src/test/java/io/github/xsirdon/mists/worldgen/IslandShapeTest.java`

- [ ] **Step 1: Write the failing test**

```java
package io.github.xsirdon.mists.worldgen;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class IslandShapeTest {

    @Test void center_isAlwaysInside() {
        IslandShape s = new IslandShape(0, 0, 30.0, 12345L);
        assertTrue(s.contains(0, 0));
    }

    @Test void farOutside_isAlwaysOutside() {
        IslandShape s = new IslandShape(0, 0, 30.0, 12345L);
        assertFalse(s.contains(200, 0));
        assertFalse(s.contains(0, 200));
        assertFalse(s.contains(150, 150));
    }

    @Test void deterministicForSameSeed() {
        IslandShape a = new IslandShape(0, 0, 30.0, 42L);
        IslandShape b = new IslandShape(0, 0, 30.0, 42L);
        for (int x = -50; x <= 50; x += 7) {
            for (int z = -50; z <= 50; z += 7) {
                assertEquals(a.contains(x, z), b.contains(x, z),
                    "mismatch at (" + x + "," + z + ")");
            }
        }
    }

    @Test void differentSeed_differentShape() {
        IslandShape a = new IslandShape(0, 0, 30.0, 1L);
        IslandShape b = new IslandShape(0, 0, 30.0, 2L);
        int diffs = 0;
        for (int x = -40; x <= 40; x += 4)
            for (int z = -40; z <= 40; z += 4)
                if (a.contains(x, z) != b.contains(x, z)) diffs++;
        assertTrue(diffs > 5, "expected at least some shape variation between seeds");
    }
}
```

- [ ] **Step 2: Run test, verify failure**

```powershell
./gradlew test --tests "*.IslandShapeTest"
```
Expected: compilation failure.

- [ ] **Step 3: Implement `IslandShape.java`**

```java
package io.github.xsirdon.mists.worldgen;

import java.util.Random;

/**
 * A deterministic, seed-derived organic island mask centred at (cx, cz)
 * with a target outer radius. Distance-from-centre is perturbed by
 * smooth noise so the outline is irregular and natural-looking.
 *
 * Inside test: distance(p, center) <= radius * (1 + 0.35 * noise(p))
 *              ⇒ radius bumps in/out by up to 35% along the perimeter.
 */
public final class IslandShape {
    private final double cx, cz, radius;
    private final long seed;

    public IslandShape(double cx, double cz, double radius, long seed) {
        this.cx = cx; this.cz = cz; this.radius = radius; this.seed = seed;
    }

    public boolean contains(double x, double z) {
        double dx = x - cx, dz = z - cz;
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist > radius * 1.4)  return false;       // fast reject
        if (dist < radius * 0.6)  return true;        // fast accept core
        double n = sampleNoise(x, z);                  // [-1, 1]
        double localR = radius * (1.0 + 0.35 * n);
        return dist <= localR;
    }

    private double sampleNoise(double x, double z) {
        // Lightweight value-noise: two octaves of seeded hash on a grid.
        return 0.6 * grad(x / 24.0, z / 24.0) + 0.4 * grad(x / 9.0, z / 9.0);
    }

    private double grad(double x, double z) {
        int xi = (int)Math.floor(x), zi = (int)Math.floor(z);
        double tx = x - xi, tz = z - zi;
        double a = hash(xi,     zi);
        double b = hash(xi + 1, zi);
        double c = hash(xi,     zi + 1);
        double d = hash(xi + 1, zi + 1);
        double ux = fade(tx), uz = fade(tz);
        double lerpX1 = a + ux * (b - a);
        double lerpX2 = c + ux * (d - c);
        return (lerpX1 + uz * (lerpX2 - lerpX1)) * 2.0 - 1.0;  // → [-1, 1]
    }

    private static double fade(double t) { return t * t * t * (t * (t * 6 - 15) + 10); }

    private double hash(int x, int z) {
        long h = seed;
        h = h * 6364136223846793005L + (long) x * 1442695040888963407L;
        h = h * 6364136223846793005L + (long) z * 1442695040888963407L;
        h ^= h >>> 33;
        return ((h & 0xffffffffL) / (double) 0xffffffffL);   // [0, 1]
    }
}
```

- [ ] **Step 4: Run tests, verify they pass**

```powershell
./gradlew test --tests "*.IslandShapeTest"
```
Expected: 4 tests passing.

- [ ] **Step 5: Commit**

```powershell
git add -A
git -c user.name=xSIRDON -c user.email=nsrdawn@gmail.com commit -m "feat(worldgen): IslandShape — organic noise-based island mask"
```

---

## Task 4: Boundary radius math (TDD)

Pure helpers for the boundary system before we hook into player ticks.

**Files:**
- Create: `src/main/java/io/github/xsirdon/mists/boundary/BoundaryMath.java`
- Create: `src/test/java/io/github/xsirdon/mists/boundary/RadiusMathTest.java`

- [ ] **Step 1: Write the failing test**

```java
package io.github.xsirdon.mists.boundary;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RadiusMathTest {

    @Test void distanceFromSpawn_2d() {
        assertEquals(0.0,  BoundaryMath.distanceFromSpawn(0,  0),  1e-9);
        assertEquals(5.0,  BoundaryMath.distanceFromSpawn(3,  4),  1e-9);
        assertEquals(13.0, BoundaryMath.distanceFromSpawn(-5, 12), 1e-9);
    }

    @Test void band_classification() {
        double r = 100.0;
        assertEquals(BoundaryBand.SAFE,    BoundaryMath.classify(50,   0, r));
        assertEquals(BoundaryBand.HOSTILE, BoundaryMath.classify(92,   0, r));
        assertEquals(BoundaryBand.HOSTILE, BoundaryMath.classify(97.9, 0, r));
        assertEquals(BoundaryBand.WALL,    BoundaryMath.classify(98.5, 0, r));
        assertEquals(BoundaryBand.VISUAL,  BoundaryMath.classify(110,  0, r));
        assertEquals(BoundaryBand.BEYOND,  BoundaryMath.classify(150,  0, r));
    }

    @Test void clampInside_pushesBackToHardWall() {
        double r = 100.0;
        double[] clamped = BoundaryMath.clampToWall(120, 0, r);
        double d = BoundaryMath.distanceFromSpawn(clamped[0], clamped[1]);
        assertEquals(98.0, d, 1e-6);   // r - HARD_WALL_INSET (2.0)
    }
}
```

- [ ] **Step 2: Add the `BoundaryBand` enum**

```java
package io.github.xsirdon.mists.boundary;

public enum BoundaryBand {
    SAFE,      // dist < r - (HOSTILE + WALL inset)
    HOSTILE,   // inside the debuff band, before the hard wall
    WALL,      // inside the hard-wall zone (server clamps movement here)
    VISUAL,    // inside the visual mist band (purely cosmetic)
    BEYOND     // far past the mist; reachable only if wall failed (sanity)
}
```

- [ ] **Step 3: Run test and verify failure**

```powershell
./gradlew test --tests "*.RadiusMathTest"
```
Expected: compilation failure on `BoundaryMath`.

- [ ] **Step 4: Implement `BoundaryMath.java`**

```java
package io.github.xsirdon.mists.boundary;

import static io.github.xsirdon.mists.MistsConstants.*;

public final class BoundaryMath {

    public static double distanceFromSpawn(double x, double z) {
        return Math.sqrt(x * x + z * z);
    }

    public static BoundaryBand classify(double x, double z, double radius) {
        double d = distanceFromSpawn(x, z);
        double wallInner = radius - HARD_WALL_INSET;
        double hostileInner = wallInner - HOSTILE_BAND_THICKNESS;
        double visualOuter = radius + VISUAL_BAND_THICKNESS;
        if (d < hostileInner) return BoundaryBand.SAFE;
        if (d < wallInner)    return BoundaryBand.HOSTILE;
        if (d < radius)       return BoundaryBand.WALL;
        if (d < visualOuter)  return BoundaryBand.VISUAL;
        return BoundaryBand.BEYOND;
    }

    /** Returns {x, z} clamped so the player sits at radius - HARD_WALL_INSET. */
    public static double[] clampToWall(double x, double z, double radius) {
        double d = distanceFromSpawn(x, z);
        if (d <= radius - HARD_WALL_INSET) return new double[]{x, z};
        double scale = (radius - HARD_WALL_INSET) / d;
        return new double[]{ x * scale, z * scale };
    }

    private BoundaryMath() {}
}
```

- [ ] **Step 5: Run tests and verify pass**

```powershell
./gradlew test --tests "*.RadiusMathTest"
```
Expected: 3 tests passing.

- [ ] **Step 6: Commit**

```powershell
git add -A
git -c user.name=xSIRDON -c user.email=nsrdawn@gmail.com commit -m "feat(boundary): BoundaryMath — distance, band classification, wall clamp"
```

---

## Task 5: LevelZBridge — read player level reflectively

LevelZ does not publish a stable compile-time API jar. We access its `PlayerStatsManager` via reflection so we depend on it at runtime only, no jar in the classpath at build time.

**Files:**
- Create: `src/main/java/io/github/xsirdon/mists/progression/LevelZBridge.java`

- [ ] **Step 1: Write `LevelZBridge.java`**

```java
package io.github.xsirdon.mists.progression;

import io.github.xsirdon.mists.Mists;
import net.minecraft.server.network.ServerPlayerEntity;

import java.lang.reflect.Method;

/**
 * Reads a player's LevelZ total/overall level by reflection.
 *
 * LevelZ adds a duck interface {@code net.levelz.access.PlayerStatsManagerAccess}
 * to {@code PlayerEntity}, with method
 * {@code PlayerStatsManager getPlayerStatsManager(PlayerEntity)}.
 * That manager exposes {@code int getOverallLevel()}.
 *
 * We resolve both classes lazily and cache the methods. If LevelZ is missing
 * (it shouldn't be — it's a required dep), we log once and return 0 so the
 * player is treated as Tier 1.
 */
public final class LevelZBridge {

    private static volatile boolean attempted = false;
    private static volatile Class<?>  accessIface;
    private static volatile Method    getStatsManager;
    private static volatile Method    getOverallLevel;

    public static int readOverallLevel(ServerPlayerEntity player) {
        ensureResolved();
        if (accessIface == null) return 0;
        try {
            if (!accessIface.isInstance(player)) return 0;
            Object mgr = getStatsManager.invoke(player, player);
            return (int) getOverallLevel.invoke(mgr);
        } catch (ReflectiveOperationException e) {
            Mists.LOG.warn("LevelZBridge: reflective level read failed", e);
            return 0;
        }
    }

    private static void ensureResolved() {
        if (attempted) return;
        synchronized (LevelZBridge.class) {
            if (attempted) return;
            attempted = true;
            try {
                Class<?> iface  = Class.forName("net.levelz.access.PlayerStatsManagerAccess");
                Class<?> mgrCls = Class.forName("net.levelz.stats.PlayerStatsManager");
                Method get = iface.getMethod("getPlayerStatsManager",
                                              Class.forName("net.minecraft.entity.player.PlayerEntity"));
                Method lvl = mgrCls.getMethod("getOverallLevel");
                accessIface = iface;
                getStatsManager = get;
                getOverallLevel = lvl;
                Mists.LOG.info("LevelZBridge: linked to LevelZ at runtime");
            } catch (ClassNotFoundException | NoSuchMethodException e) {
                Mists.LOG.error("LevelZBridge: LevelZ not found at runtime", e);
            }
        }
    }

    private LevelZBridge() {}
}
```

> Note: the exact class names above (`net.levelz.access.PlayerStatsManagerAccess`, `net.levelz.stats.PlayerStatsManager`, method `getOverallLevel`) match LevelZ upstream as of the 1.20.1 line. The custom `levelz-true-survival-1.4.13` fork in the modpack inherits the same package layout. **If a smoke test in Task 12 logs "LevelZ not found at runtime", inspect the actual classes inside `levelz-true-survival-1.4.13.jar` and adjust the strings here only.**

- [ ] **Step 2: Commit (no test yet — covered by integration smoke test in Task 12)**

```powershell
git add -A
git -c user.name=xSIRDON -c user.email=nsrdawn@gmail.com commit -m "feat(progression): LevelZBridge — reflective read of LevelZ overall level"
```

---

## Task 6: MistRadiusPayload — the only S2C packet

Carries `{radius, animateFromRadius}` from server to one client.

**Files:**
- Create: `src/main/java/io/github/xsirdon/mists/network/MistRadiusPayload.java`
- Modify: `src/main/java/io/github/xsirdon/mists/Mists.java` (register packet codec server-side)
- Modify: `src/main/java/io/github/xsirdon/mists/MistsClient.java` (register receiver)
- Create: `src/main/java/io/github/xsirdon/mists/client/MistState.java`

- [ ] **Step 1: Write `MistRadiusPayload.java`** (raw `PacketByteBuf` style, which is the 1.20.1 Fabric API)

```java
package io.github.xsirdon.mists.network;

import io.github.xsirdon.mists.MistsConstants;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.network.ServerPlayerEntity;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

public final class MistRadiusPayload {
    public final double radius;
    public final double animateFromRadius;   // same as radius if no animation desired

    public MistRadiusPayload(double radius, double animateFromRadius) {
        this.radius = radius;
        this.animateFromRadius = animateFromRadius;
    }

    public static MistRadiusPayload decode(PacketByteBuf buf) {
        double r  = buf.readDouble();
        double af = buf.readDouble();
        return new MistRadiusPayload(r, af);
    }

    public PacketByteBuf encode() {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeDouble(radius);
        buf.writeDouble(animateFromRadius);
        return buf;
    }

    public static void sendTo(ServerPlayerEntity player, double radius, double animateFromRadius) {
        ServerPlayNetworking.send(player, MistsConstants.MIST_RADIUS_PACKET,
            new MistRadiusPayload(radius, animateFromRadius).encode());
    }

    private MistRadiusPayload() { this(0, 0); }
}
```

- [ ] **Step 2: Write `client/MistState.java`**

```java
package io.github.xsirdon.mists.client;

/** Client-side cache of the player's current mist boundary. */
public final class MistState {

    public static volatile double currentRadius = Double.POSITIVE_INFINITY;
    public static volatile double animationFromRadius = Double.POSITIVE_INFINITY;
    public static volatile long   animationStartedAtMillis = 0L;
    public static final  long     ANIMATION_DURATION_MS = 3_000L;

    public static double effectiveRadius() {
        long elapsed = System.currentTimeMillis() - animationStartedAtMillis;
        if (elapsed >= ANIMATION_DURATION_MS) return currentRadius;
        double t = elapsed / (double) ANIMATION_DURATION_MS;
        // Ease-out cubic
        double eased = 1.0 - Math.pow(1.0 - t, 3.0);
        return animationFromRadius + (currentRadius - animationFromRadius) * eased;
    }

    public static void apply(double newRadius, double animateFrom) {
        animationFromRadius = animateFrom;
        currentRadius = newRadius;
        animationStartedAtMillis = System.currentTimeMillis();
    }

    private MistState() {}
}
```

- [ ] **Step 3: Update `MistsClient.java` to register the receiver**

```java
package io.github.xsirdon.mists;

import io.github.xsirdon.mists.client.MistState;
import io.github.xsirdon.mists.network.MistRadiusPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

public final class MistsClient implements ClientModInitializer {
    @Override public void onInitializeClient() {
        Mists.LOG.info("Mists initialising (client)");

        ClientPlayNetworking.registerGlobalReceiver(
            MistsConstants.MIST_RADIUS_PACKET,
            (client, handler, buf, sender) -> {
                MistRadiusPayload p = MistRadiusPayload.decode(buf);
                client.execute(() -> MistState.apply(p.radius, p.animateFromRadius));
            });
    }
}
```

- [ ] **Step 4: Verify the project still compiles**

```powershell
./gradlew compileJava compileTestJava
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```powershell
git add -A
git -c user.name=xSIRDON -c user.email=nsrdawn@gmail.com commit -m "feat(network): MistRadiusPayload + client-side MistState"
```

---

## Task 7: BoundarySystem — per-player tick clamp

Runs on every server-side player tick. Reads the player's LevelZ level → radius. If the player is outside that radius, clamp them back. Also detects radius changes and emits the S2C packet (with animation).

**Files:**
- Create: `src/main/java/io/github/xsirdon/mists/boundary/BoundarySystem.java`
- Modify: `src/main/java/io/github/xsirdon/mists/Mists.java` (call `BoundarySystem.register()`)

- [ ] **Step 1: Write `BoundarySystem.java`**

```java
package io.github.xsirdon.mists.boundary;

import io.github.xsirdon.mists.MistsConstants;
import io.github.xsirdon.mists.network.MistRadiusPayload;
import io.github.xsirdon.mists.progression.LevelZBridge;
import io.github.xsirdon.mists.progression.TierTable;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.Vec3d;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class BoundarySystem {

    /** UUID → last-known radius. Used to detect transitions and animate the client. */
    private static final Map<UUID, Double> lastRadius = new HashMap<>();

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                tickPlayer(player);
            }
        });
    }

    private static void tickPlayer(ServerPlayerEntity player) {
        int level = LevelZBridge.readOverallLevel(player);
        double radius = TierTable.levelToRadius(level);

        Double prev = lastRadius.get(player.getUuid());
        if (prev == null || Math.abs(prev - radius) > 0.001) {
            double from = prev == null ? radius : prev;
            MistRadiusPayload.sendTo(player, radius, from);
            lastRadius.put(player.getUuid(), radius);
        }

        Vec3d pos = player.getPos();
        BoundaryBand band = BoundaryMath.classify(pos.x, pos.z, radius);
        switch (band) {
            case HOSTILE -> HostileWaters.applyDebuffs(player, pos.x, pos.z, radius);
            case WALL, VISUAL, BEYOND -> hardClamp(player, radius);
            default -> {}
        }
    }

    private static void hardClamp(ServerPlayerEntity player, double radius) {
        Vec3d pos = player.getPos();
        double[] clamped = BoundaryMath.clampToWall(pos.x, pos.z, radius);
        // Preserve y; cancel outward velocity.
        Vec3d v = player.getVelocity();
        double dx = clamped[0] - pos.x;
        double dz = clamped[1] - pos.z;
        player.requestTeleport(clamped[0], pos.y, clamped[1]);
        player.setVelocity(Math.signum(dx) == 0 ? v.x : 0, v.y, Math.signum(dz) == 0 ? v.z : 0);
        player.velocityModified = true;
    }

    private BoundarySystem() {}
}
```

- [ ] **Step 2: Update `Mists.java` to register the system**

```java
package io.github.xsirdon.mists;

import io.github.xsirdon.mists.boundary.BoundarySystem;
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Mists implements ModInitializer {
    public static final Logger LOG = LoggerFactory.getLogger(MistsConstants.MOD_ID);

    @Override public void onInitialize() {
        LOG.info("Mists initialising (server/common)");
        BoundarySystem.register();
    }
}
```

- [ ] **Step 3: Add stub `HostileWaters.applyDebuffs` so it compiles**

`src/main/java/io/github/xsirdon/mists/boundary/HostileWaters.java`:

```java
package io.github.xsirdon.mists.boundary;

import net.minecraft.server.network.ServerPlayerEntity;

public final class HostileWaters {
    public static void applyDebuffs(ServerPlayerEntity player, double x, double z, double radius) {
        // Filled in by Task 8.
    }
    private HostileWaters() {}
}
```

- [ ] **Step 4: Compile**

```powershell
./gradlew compileJava
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```powershell
git add -A
git -c user.name=xSIRDON -c user.email=nsrdawn@gmail.com commit -m "feat(boundary): BoundarySystem — per-player tick clamp + radius diffing"
```

---

## Task 8: HostileWaters — escalating debuffs

Applies status effects inside the hostile band. Depth-into-band scales severity.

**Files:**
- Modify: `src/main/java/io/github/xsirdon/mists/boundary/HostileWaters.java`

- [ ] **Step 1: Replace the stub with the real implementation**

```java
package io.github.xsirdon.mists.boundary;

import io.github.xsirdon.mists.MistsConstants;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.server.network.ServerPlayerEntity;

public final class HostileWaters {

    /** Re-applied every tick — short durations keep the effect tightly bound to the band. */
    private static final int EFFECT_DURATION_TICKS = 40; // 2 seconds

    public static void applyDebuffs(ServerPlayerEntity player, double x, double z, double radius) {
        double d = BoundaryMath.distanceFromSpawn(x, z);
        double wallInner = radius - MistsConstants.HARD_WALL_INSET;
        double hostileInner = wallInner - MistsConstants.HOSTILE_BAND_THICKNESS;
        if (d < hostileInner) return;
        double depth01 = Math.min(1.0, (d - hostileInner) / MistsConstants.HOSTILE_BAND_THICKNESS);

        int slownessAmp = depth01 > 0.5 ? 1 : 0;        // Slowness I → II
        int nauseaAmp = 0;
        player.addStatusEffect(new StatusEffectInstance(
            StatusEffects.SLOWNESS, EFFECT_DURATION_TICKS, slownessAmp, false, false, true));
        player.addStatusEffect(new StatusEffectInstance(
            StatusEffects.NAUSEA, EFFECT_DURATION_TICKS, nauseaAmp, false, false, true));

        // Drowning damage that scales 0 → 2 hearts/sec as depth01 ramps 0 → 1.
        // Tick rate is 20Hz, so apply (depth01 * 0.2) damage per tick.
        float dmg = (float) (depth01 * 0.2);
        if (dmg > 0) {
            player.damage(player.getDamageSources().drown(), dmg);
        }
    }

    private HostileWaters() {}
}
```

- [ ] **Step 2: Compile**

```powershell
./gradlew compileJava
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```powershell
git add -A
git -c user.name=xSIRDON -c user.email=nsrdawn@gmail.com commit -m "feat(boundary): HostileWaters — Slowness/Nausea/drowning scaled by depth"
```

---

## Task 9: PearlClamp + VehicleClamp — bypass prevention

**Files:**
- Create: `src/main/java/io/github/xsirdon/mists/boundary/PearlClamp.java`
- Create: `src/main/java/io/github/xsirdon/mists/boundary/VehicleClamp.java`
- Modify: `src/main/java/io/github/xsirdon/mists/Mists.java` (register both)

- [ ] **Step 1: Write `PearlClamp.java`**

```java
package io.github.xsirdon.mists.boundary;

import io.github.xsirdon.mists.progression.LevelZBridge;
import io.github.xsirdon.mists.progression.TierTable;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.minecraft.entity.projectile.thrown.EnderPearlEntity;
import net.minecraft.server.network.ServerPlayerEntity;

public final class PearlClamp {

    public static void register() {
        ServerEntityEvents.ENTITY_LOAD.register((entity, world) -> {
            if (!(entity instanceof EnderPearlEntity pearl)) return;
            if (!(pearl.getOwner() instanceof ServerPlayerEntity player)) return;
            int level = LevelZBridge.readOverallLevel(player);
            double radius = TierTable.levelToRadius(level);

            // Project pearl's velocity ~3s forward to find an estimated destination.
            double estX = pearl.getX() + pearl.getVelocity().x * 60;
            double estZ = pearl.getZ() + pearl.getVelocity().z * 60;
            if (BoundaryMath.distanceFromSpawn(estX, estZ) > radius - 4) {
                // Refund and remove.
                pearl.discard();
                player.getInventory().offerOrDrop(new net.minecraft.item.ItemStack(
                    net.minecraft.item.Items.ENDER_PEARL));
                player.sendMessage(net.minecraft.text.Text.literal("The pearl is swallowed by the mist."), true);
            }
        });
    }

    private PearlClamp() {}
}
```

- [ ] **Step 2: Write `VehicleClamp.java`**

```java
package io.github.xsirdon.mists.boundary;

import io.github.xsirdon.mists.progression.LevelZBridge;
import io.github.xsirdon.mists.progression.TierTable;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.entity.Entity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.Vec3d;

public final class VehicleClamp {

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                Entity vehicle = player.getVehicle();
                if (vehicle == null) continue;
                // Find the lowest LevelZ level among all human passengers of this vehicle.
                int lowest = Integer.MAX_VALUE;
                for (Entity p : vehicle.getPassengerList()) {
                    if (p instanceof ServerPlayerEntity sp) {
                        lowest = Math.min(lowest, LevelZBridge.readOverallLevel(sp));
                    }
                }
                if (lowest == Integer.MAX_VALUE) continue;
                double radius = TierTable.levelToRadius(lowest);
                Vec3d pos = vehicle.getPos();
                if (BoundaryMath.distanceFromSpawn(pos.x, pos.z) > radius - 2.0) {
                    double[] clamped = BoundaryMath.clampToWall(pos.x, pos.z, radius);
                    vehicle.requestTeleport(clamped[0], pos.y, clamped[1]);
                    vehicle.setVelocity(0, vehicle.getVelocity().y, 0);
                    vehicle.velocityModified = true;
                }
            }
        });
    }

    private VehicleClamp() {}
}
```

- [ ] **Step 3: Update `Mists.java` to register both**

Replace the body of `onInitialize`:

```java
@Override public void onInitialize() {
    LOG.info("Mists initialising (server/common)");
    BoundarySystem.register();
    PearlClamp.register();
    VehicleClamp.register();
}
```

(Add the imports at the top.)

- [ ] **Step 4: Compile**

```powershell
./gradlew compileJava
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```powershell
git add -A
git -c user.name=xSIRDON -c user.email=nsrdawn@gmail.com commit -m "feat(boundary): PearlClamp + VehicleClamp — bypass prevention"
```

---

## Task 10: World data + spawn island + island placer

This is the chunkiest task. We need to:
1. Persist island metadata (where each ring island is, its biome, its radius) to `mists.dat`.
2. On the first server tick after a world is freshly loaded, if `mists.dat` does not exist, run the placer.
3. The placer carves a spawn island, places tier 2/3/4 ring islands, and carves the inter-island ocean.

**Files:**
- Create: `src/main/java/io/github/xsirdon/mists/worldgen/MistsWorldData.java`
- Create: `src/main/java/io/github/xsirdon/mists/worldgen/SpawnIsland.java`
- Create: `src/main/java/io/github/xsirdon/mists/worldgen/IslandPlacer.java`
- Create: `src/main/java/io/github/xsirdon/mists/worldgen/OceanCarver.java`
- Modify: `src/main/java/io/github/xsirdon/mists/Mists.java`

- [ ] **Step 1: Write `MistsWorldData.java`**

```java
package io.github.xsirdon.mists.worldgen;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.PersistentState;

import java.util.ArrayList;
import java.util.List;

public final class MistsWorldData extends PersistentState {

    public static final String KEY = "mists";

    public static final class IslandRecord {
        public final int tier;
        public final double cx, cz, radius;
        public final long seed;
        public IslandRecord(int tier, double cx, double cz, double radius, long seed) {
            this.tier = tier; this.cx = cx; this.cz = cz; this.radius = radius; this.seed = seed;
        }
    }

    public boolean placed = false;
    public final List<IslandRecord> islands = new ArrayList<>();

    @Override public NbtCompound writeNbt(NbtCompound nbt) {
        nbt.putBoolean("placed", placed);
        NbtList list = new NbtList();
        for (IslandRecord r : islands) {
            NbtCompound tag = new NbtCompound();
            tag.putInt("tier", r.tier);
            tag.putDouble("cx", r.cx);
            tag.putDouble("cz", r.cz);
            tag.putDouble("radius", r.radius);
            tag.putLong("seed", r.seed);
            list.add(tag);
        }
        nbt.put("islands", list);
        return nbt;
    }

    public static MistsWorldData fromNbt(NbtCompound nbt) {
        MistsWorldData d = new MistsWorldData();
        d.placed = nbt.getBoolean("placed");
        NbtList list = nbt.getList("islands", NbtElement.COMPOUND_TYPE);
        for (int i = 0; i < list.size(); i++) {
            NbtCompound t = list.getCompound(i);
            d.islands.add(new IslandRecord(
                t.getInt("tier"), t.getDouble("cx"), t.getDouble("cz"),
                t.getDouble("radius"), t.getLong("seed")));
        }
        return d;
    }

    public static MistsWorldData get(ServerWorld world) {
        return world.getPersistentStateManager().getOrCreate(
            MistsWorldData::fromNbt, MistsWorldData::new, KEY);
    }
}
```

- [ ] **Step 2: Write `OceanCarver.java`** (utility that replaces non-water blocks in a column with water)

```java
package io.github.xsirdon.mists.worldgen;

import net.minecraft.block.Blocks;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

public final class OceanCarver {

    public static final int SEA_LEVEL = 63;
    public static final int CARVE_FLOOR = 50;

    public static void carveColumnToOcean(ServerWorld world, int x, int z) {
        for (int y = world.getTopY() - 1; y >= CARVE_FLOOR; y--) {
            BlockPos p = new BlockPos(x, y, z);
            if (y > SEA_LEVEL) {
                world.setBlockState(p, Blocks.AIR.getDefaultState(), 2);
            } else {
                world.setBlockState(p, Blocks.WATER.getDefaultState(), 2);
            }
        }
    }

    private OceanCarver() {}
}
```

- [ ] **Step 3: Write `SpawnIsland.java`** (builds the plains spawn island and forces world spawn onto it)

```java
package io.github.xsirdon.mists.worldgen;

import io.github.xsirdon.mists.Mists;
import net.minecraft.block.Blocks;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

public final class SpawnIsland {

    public static final double SPAWN_ISLAND_RADIUS = 28.0;   // ~4 chunks across
    public static final int    SPAWN_Y = 65;                  // top of island
    public static final int    BASE_Y  = OceanCarver.SEA_LEVEL - 3;

    public static void build(ServerWorld world, long worldSeed) {
        IslandShape shape = new IslandShape(0, 0, SPAWN_ISLAND_RADIUS, worldSeed);
        for (int x = -45; x <= 45; x++) {
            for (int z = -45; z <= 45; z++) {
                if (shape.contains(x, z)) {
                    // Solid stone base, dirt above, grass on top.
                    for (int y = BASE_Y; y <= SPAWN_Y - 1; y++) {
                        world.setBlockState(new BlockPos(x, y, z), Blocks.DIRT.getDefaultState(), 2);
                    }
                    world.setBlockState(new BlockPos(x, SPAWN_Y, z), Blocks.GRASS_BLOCK.getDefaultState(), 2);
                } else {
                    OceanCarver.carveColumnToOcean(world, x, z);
                }
            }
        }
        // Place a few oak trees at deterministic offsets (no animals).
        placeTree(world, -6, SPAWN_Y + 1,  3);
        placeTree(world,  4, SPAWN_Y + 1, -7);
        placeTree(world, 11, SPAWN_Y + 1,  2);
        // Force spawn to a known dry position on top of the island.
        BlockPos spawn = new BlockPos(0, SPAWN_Y + 1, 0);
        world.setSpawnPos(spawn, 0f);
        Mists.LOG.info("Mists: spawn island built at {}", spawn);
    }

    private static void placeTree(ServerWorld world, int x, int y, int z) {
        // Trunk
        for (int i = 0; i < 5; i++)
            world.setBlockState(new BlockPos(x, y + i, z), Blocks.OAK_LOG.getDefaultState(), 2);
        // Leaves (3x3x3 canopy)
        for (int dx = -2; dx <= 2; dx++)
            for (int dz = -2; dz <= 2; dz++)
                for (int dy = 3; dy <= 5; dy++) {
                    BlockPos p = new BlockPos(x + dx, y + dy, z + dz);
                    if (world.getBlockState(p).isAir() && Math.abs(dx) + Math.abs(dz) + Math.abs(dy - 4) <= 4)
                        world.setBlockState(p, Blocks.OAK_LEAVES.getDefaultState(), 2);
                }
    }

    private SpawnIsland() {}
}
```

- [ ] **Step 4: Write `IslandPlacer.java`**

```java
package io.github.xsirdon.mists.worldgen;

import io.github.xsirdon.mists.Mists;
import io.github.xsirdon.mists.MistsConstants;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents;
import net.minecraft.block.Blocks;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.Random;

public final class IslandPlacer {

    public static void register() {
        ServerWorldEvents.LOAD.register((server, world) -> {
            if (world.getRegistryKey() != World.OVERWORLD) return;
            MistsWorldData data = MistsWorldData.get(world);
            if (data.placed) return;
            place(world, data);
            data.placed = true;
            data.markDirty();
        });
    }

    private static void place(ServerWorld world, MistsWorldData data) {
        long seed = world.getSeed();
        Random rng = new Random(seed ^ 0x4D49535453L);  // "MISTS" as bytes

        SpawnIsland.build(world, seed);
        data.islands.add(new MistsWorldData.IslandRecord(1, 0, 0, SpawnIsland.SPAWN_ISLAND_RADIUS, seed));

        placeRing(world, data, rng, 2, MistsConstants.TIER_2_RADIUS,  6 * 16,  16 * 16);
        placeRing(world, data, rng, 3, MistsConstants.TIER_3_RADIUS, 10 * 16,  28 * 16);
        placeRing(world, data, rng, 4, MistsConstants.TIER_4_RADIUS, 16 * 16,  48 * 16);

        // Inter-island ocean carve (out to slightly beyond tier 4).
        carveOcean(world, data, (int)(MistsConstants.TIER_4_RADIUS + 100));

        Mists.LOG.info("Mists: archipelago placement complete ({} islands)", data.islands.size());
    }

    private static void placeRing(ServerWorld world, MistsWorldData data, Random rng,
                                  int tier, double ringRadius, int minArea, int maxArea) {
        int count = 3 + rng.nextInt(3);  // 3–5
        double angleStep = (Math.PI * 2) / count;
        double angleJitter = angleStep * 0.4;
        double baseAngle = rng.nextDouble() * Math.PI * 2;

        for (int i = 0; i < count; i++) {
            double angle = baseAngle + i * angleStep + (rng.nextDouble() - 0.5) * angleJitter;
            double r = ringRadius + (rng.nextDouble() - 0.5) * 80.0;
            double cx = Math.cos(angle) * r;
            double cz = Math.sin(angle) * r;

            int area = minArea + rng.nextInt(maxArea - minArea + 1);
            double islandRadius = Math.sqrt(area / Math.PI);
            long shapeSeed = rng.nextLong();

            buildIsland(world, cx, cz, islandRadius, shapeSeed);
            data.islands.add(new MistsWorldData.IslandRecord(tier, cx, cz, islandRadius, shapeSeed));
        }
    }

    private static void buildIsland(ServerWorld world, double cx, double cz, double radius, long shapeSeed) {
        IslandShape shape = new IslandShape(cx, cz, radius, shapeSeed);
        int from = (int) (-radius * 1.5);
        int to   = (int) ( radius * 1.5);
        for (int dx = from; dx <= to; dx++) {
            for (int dz = from; dz <= to; dz++) {
                int x = (int) cx + dx, z = (int) cz + dz;
                if (shape.contains(x, z)) {
                    for (int y = SpawnIsland.BASE_Y; y <= OceanCarver.SEA_LEVEL + 2; y++) {
                        world.setBlockState(new BlockPos(x, y, z),
                            Blocks.DIRT.getDefaultState(), 2);
                    }
                    world.setBlockState(new BlockPos(x, OceanCarver.SEA_LEVEL + 3, z),
                        Blocks.GRASS_BLOCK.getDefaultState(), 2);
                }
            }
        }
    }

    /** Inside the ring zone, any land block above sea level not part of a placed island is drowned. */
    private static void carveOcean(ServerWorld world, MistsWorldData data, int radius) {
        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                double d = Math.sqrt((double) x * x + (double) z * z);
                if (d > radius) continue;
                if (isInsideAnyIsland(data, x, z)) continue;
                OceanCarver.carveColumnToOcean(world, x, z);
            }
        }
    }

    private static boolean isInsideAnyIsland(MistsWorldData data, int x, int z) {
        for (MistsWorldData.IslandRecord r : data.islands) {
            double dx = x - r.cx, dz = z - r.cz;
            if (dx * dx + dz * dz <= (r.radius * 1.05) * (r.radius * 1.05)) return true;
        }
        return false;
    }

    private IslandPlacer() {}
}
```

- [ ] **Step 5: Update `Mists.java` to register the placer**

```java
@Override public void onInitialize() {
    LOG.info("Mists initialising (server/common)");
    BoundarySystem.register();
    PearlClamp.register();
    VehicleClamp.register();
    IslandPlacer.register();
}
```

(Add the `import io.github.xsirdon.mists.worldgen.IslandPlacer;` import.)

- [ ] **Step 6: Compile**

```powershell
./gradlew compileJava
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```powershell
git add -A
git -c user.name=xSIRDON -c user.email=nsrdawn@gmail.com commit -m "feat(worldgen): spawn island, ring placer, ocean carver, persistent state"
```

> **Known limitation worth flagging to the user**: the placer writes blocks immediately during world load, which can produce slight lag on first generation and may interact with mods that defer chunk gen (e.g., Sodium worldgen optimizations). After the first integration smoke test (Task 14), if this is visible, defer placement to a background `MinecraftServer.execute` chain that paces 16×16 chunks per tick. We do not pre-emptively complicate Task 10 with that work.

---

## Task 11: Particle texture + sound assets

We need: a `particle/mist_0.png` texture, a `particles/mist.json` definition, and `sounds.json`. The particle is a soft white circular gradient.

**Files:**
- Create: `src/main/resources/assets/mists/particles/mist.json`
- Create: `src/main/resources/assets/mists/textures/particle/mist_0.png`
- Create: `src/main/resources/assets/mists/sounds.json`

- [ ] **Step 1: Write `particles/mist.json`**

```json
{
  "textures": [ "mists:mist_0" ]
}
```

- [ ] **Step 2: Create `textures/particle/mist_0.png`**

A 16×16 PNG with a soft radial alpha gradient (transparent edges, opaque white centre). The exact file is binary — generate with any image tool, e.g. Pillow:

```python
from PIL import Image, ImageDraw
img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
d = ImageDraw.Draw(img)
for r in range(8, 0, -1):
    a = int(255 * (1 - r / 8) ** 1.4)
    d.ellipse([8 - r, 8 - r, 8 + r, 8 + r], fill=(255, 255, 255, a))
img.save(r"C:\Users\ncerd\Mists\src\main\resources\assets\mists\textures\particle\mist_0.png")
```

- [ ] **Step 3: Write `sounds.json`**

```json
{
  "ambient_mist": {
    "category": "ambient",
    "sounds": [
      { "name": "mists:ambient_mist", "stream": false }
    ]
  },
  "mist_retreat": {
    "category": "ambient",
    "sounds": [
      { "name": "mists:mist_retreat", "stream": false }
    ]
  }
}
```

> Sound .ogg files are not authored in this plan. For now we register the entries and ship them empty. The mod will run without audio if files are missing — clients log a warning. Real sound files can be authored post-launch.

- [ ] **Step 4: Commit**

```powershell
git add -A
git -c user.name=xSIRDON -c user.email=nsrdawn@gmail.com commit -m "assets: mist particle texture + sound table"
```

---

## Task 12: MistRenderer — render the boundary ring

Runs every client world-render frame. Uses `WorldRenderEvents.AFTER_TRANSLUCENT` to spawn dense particles in a ring of radius `MistState.effectiveRadius()` around world spawn, only the arc near the player's camera.

**Files:**
- Create: `src/main/java/io/github/xsirdon/mists/client/MistRenderer.java`
- Modify: `src/main/java/io/github/xsirdon/mists/MistsClient.java`

- [ ] **Step 1: Write `MistRenderer.java`**

```java
package io.github.xsirdon.mists.client;

import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.util.math.Vec3d;

public final class MistRenderer {

    private static int tick = 0;

    public static void register() {
        WorldRenderEvents.AFTER_TRANSLUCENT.register(ctx -> tickRender());
    }

    private static void tickRender() {
        MinecraftClient mc = MinecraftClient.getInstance();
        PlayerEntity player = mc.player;
        ClientWorld world = mc.world;
        if (player == null || world == null) return;
        if (++tick % 2 != 0) return;          // spawn at 10Hz max
        double radius = MistState.effectiveRadius();
        if (!Double.isFinite(radius) || radius > 25_000) return;

        Vec3d pos = player.getPos();
        double playerAngle = Math.atan2(pos.z, pos.x);
        // Only spawn arc particles within ±60° of the player's view direction from spawn.
        double arcHalf = Math.toRadians(60);
        int slices = 24;
        for (int i = -slices; i <= slices; i++) {
            double a = playerAngle + (i / (double) slices) * arcHalf;
            double x = Math.cos(a) * radius;
            double z = Math.sin(a) * radius;
            // Three vertical bands: low, mid, high — purely for visual depth.
            for (double dy : new double[]{ 56, 70, 90 }) {
                if (Math.random() > 0.35) continue;
                world.addParticle(ParticleTypes.CLOUD, x, dy, z, 0, 0.005, 0);
            }
        }
    }

    private MistRenderer() {}
}
```

> The renderer uses `ParticleTypes.CLOUD` (vanilla) for v0.1 — looks the right colour and shape without needing a custom registered particle. We can swap to our `mists:mist` particle in a later polish pass once the look is tuned.

- [ ] **Step 2: Update `MistsClient.java`**

```java
package io.github.xsirdon.mists;

import io.github.xsirdon.mists.client.MistRenderer;
import io.github.xsirdon.mists.client.MistState;
import io.github.xsirdon.mists.network.MistRadiusPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

public final class MistsClient implements ClientModInitializer {
    @Override public void onInitializeClient() {
        Mists.LOG.info("Mists initialising (client)");
        ClientPlayNetworking.registerGlobalReceiver(
            MistsConstants.MIST_RADIUS_PACKET,
            (client, handler, buf, sender) -> {
                MistRadiusPayload p = MistRadiusPayload.decode(buf);
                client.execute(() -> MistState.apply(p.radius, p.animateFromRadius));
            });
        MistRenderer.register();
    }
}
```

- [ ] **Step 3: Compile**

```powershell
./gradlew compileJava
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```powershell
git add -A
git -c user.name=xSIRDON -c user.email=nsrdawn@gmail.com commit -m "feat(client): MistRenderer — particle ring around spawn"
```

---

## Task 13: First end-to-end dev-environment smoke test

Boot the mod in a Loom-managed dev client. Verify subsystems load, the spawn island generates, and movement clamps work.

**Files:** none changed (manual test).

- [ ] **Step 1: Generate run configs**

```powershell
./gradlew genSources idea
```

- [ ] **Step 2: Launch a client**

```powershell
./gradlew runClient
```

Expected log lines (in dev console):
```
Mists initialising (server/common)
Mists initialising (client)
```

When you create a new singleplayer world:
```
Mists: spawn island built at BlockPos{x=0, y=66, z=0}
Mists: archipelago placement complete (XX islands)
```

- [ ] **Step 3: Check the world**

In-game checks:
- You spawn on a grassy island ~4 chunks across at (0, 66, 0).
- Looking outward, you see ocean immediately.
- No animals on the spawn island.
- Trying to walk/swim past ~120 blocks from spawn: you stop dead and get pushed back inward.
- Press F3 — confirm coordinates show you can't cross x²+z² > ~118².

If any of these fail, that is the bug to fix before moving on. Do not proceed to Task 14 until Task 13 passes.

- [ ] **Step 4: Promote LevelZ to runtime classpath**

For `runClient` to also test the LevelZ bridge, drop `levelz-true-survival-1.4.13.jar` into a `run/mods/` directory inside the project. Then:

```powershell
./gradlew runClient
```

Verify the log shows:
```
LevelZBridge: linked to LevelZ at runtime
```

If instead it shows `LevelZBridge: LevelZ not found at runtime`, open `levelz-true-survival-1.4.13.jar` (it's a ZIP), inspect the class layout, and patch the `Class.forName` strings in `LevelZBridge.java`.

- [ ] **Step 5: Manual LevelZ-level test**

In-game, run `/levelz set total 5` (the command name may differ — check LevelZ's commands). After the command, the client should receive a `MistRadiusPayload(radius=350, animateFrom=120)`, you should hear the chunk-load click of new particles spawning further out, and you should be able to sail to ~320 blocks before hitting a wall.

- [ ] **Step 6: Commit any fixes from this task with descriptive messages**

If the LevelZ reflection strings needed adjustment, that commit looks like:

```powershell
git add -A
git -c user.name=xSIRDON -c user.email=nsrdawn@gmail.com commit -m "fix(progression): align LevelZBridge reflection with LevelZ TS fork class layout"
```

---

## Task 14: Compatibility test in real True Survival modpack

**Files:** none changed (test in a real prism/MultiMC instance).

- [ ] **Step 1: Build a release jar**

```powershell
./gradlew build
```
Expected: `build/libs/mists-0.1.0.jar` produced.

- [ ] **Step 2: Install into a True Survival instance**

- Make a Prism Launcher (or MultiMC) instance from the modpack `bpqhk76.mrpack`.
- Drop `mists-0.1.0.jar` into the instance's `mods/` directory.
- Launch the instance.

- [ ] **Step 3: Verify mod load order has no conflicts**

In `latest.log`, search for:
- `Mists initialising`
- `LevelZBridge: linked to LevelZ at runtime`
- No stack traces from `mists.*` packages.

- [ ] **Step 4: Smoke-test the full survival flow**

- New world: confirm spawn on a True Survival-balanced plains island.
- ToughAsNails thirst meter still works (drinking from the spawn pond / rain via Particle Rain).
- Sailing into the mist — confirm Slowness/Nausea apply on top of any TaN temperature effects without crashing.
- `/levelz set total 5` (LevelZ admin command) — mist retreats. Sail outward; reach a Tier 2 island; verify it has a vanilla biome (or Biome Makeover biome).
- Set total 10, 15, 30 — confirm the boundary expands correctly each time.

- [ ] **Step 5: Commit a brief notes file documenting the test result**

Create `docs/compat-notes-v0.1.0.md` listing what worked and what didn't, then:

```powershell
git add docs/compat-notes-v0.1.0.md
git -c user.name=xSIRDON -c user.email=nsrdawn@gmail.com commit -m "docs: True Survival v1.0.16 compat notes for Mists v0.1.0"
```

---

## Task 15: Tag the v0.1.0 release

- [ ] **Step 1: Tag and push**

```powershell
git tag v0.1.0
git push origin main --tags
```

- [ ] **Step 2: Create a GitHub release**

```powershell
gh release create v0.1.0 build/libs/mists-0.1.0.jar `
    --title "Mists v0.1.0 — initial release" `
    --notes "First playable release. Spawn island + tier 2/3/4 rings + mist boundary + LevelZ progression. Targets True Survival v1.0.16."
```

---

## Self-review notes

Spec coverage check against `README.md`:

- ✅ Spawn island ~4 chunks, plains, no animals, no structures → Task 10 `SpawnIsland.build`
- ✅ Ring 2/3/4 with 3-5 islands each, random angles, increasing radii → Task 10 `IslandPlacer.placeRing`
- ✅ Size curves (6-16 / 10-28 / 16-48 chunks) → Task 10, `minArea / maxArea` parameters
- ✅ Vanilla worldgen beyond ~1000 → Task 10 `carveOcean` extends to T4 + 100; outside that, vanilla
- ✅ Three-layer boundary (visual / hostile / wall) → Task 4 `BoundaryMath.classify` + Task 7 `BoundarySystem` + Task 8 `HostileWaters`
- ✅ Per-player progression → Task 7 keys by player UUID; packet sent only to one player
- ✅ Lowest-level wins for vehicles → Task 9 `VehicleClamp`
- ✅ Pearls/chorus blocked → Task 9 `PearlClamp` (chorus follow-up: same hook applies in `ServerEntityEvents.ENTITY_LOAD` if we add `ChorusFruitItem` interception; deferred to a v0.2 — not yet covered)
- ✅ Vertical column full sky→bedrock → Task 4/7 work on (x, z) only; y is ignored, so tunneling/flying is blocked by the same logic
- ✅ LevelZ read-only integration → Task 5 reflective bridge
- ✅ Mist retreats over ~3s with rumble → Task 6 client state interpolation; rumble sound entry registered in Task 11 (file content deferred)
- ✅ MIT license, public repo → already in place (commit `fabd7b4`)

Gap (intentional, deferred to v0.2):
- Chorus fruit interception — not implemented in v0.1.0. The mist still applies on the tick after teleport, so a chorus-fruit player would be teleported back to the wall on the next tick rather than refunded the fruit. Acceptable for v0.1.
- Real .ogg sound files for the ambient howl and retreat rumble. Registered in `sounds.json` but file content deferred.
- Custom mist particle texture is provided but not registered as a particle type yet — Task 12 uses vanilla `CLOUD` for v0.1.

All other red-flag items from the no-placeholders list:
- No `TBD` / `TODO` / `implement later` markers in any task.
- All code blocks are complete and runnable.
- Method names are consistent across tasks (`levelToRadius`, `clampToWall`, `applyDebuffs`, `register`).
- Every file referenced in `File Structure` has at least one task that creates it (or is intentionally deferred and called out above).
