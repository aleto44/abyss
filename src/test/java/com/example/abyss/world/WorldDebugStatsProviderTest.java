package com.example.abyss.world;

import com.example.abyss.world.gen.GenConfigResolver;
import com.example.abyss.world.meshing.MeshData;
import com.example.abyss.world.meshing.MeshSectionData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldDebugStatsProviderTest {

    private ChunkManager chunkManager;
    private WorldDebugStatsProvider statsProvider;

    @BeforeEach
    void setUp() {
        chunkManager = new ChunkManager(42L, GenConfigResolver.DEFAULT);
        statsProvider = new WorldDebugStatsProvider(chunkManager);
    }

    @AfterEach
    void tearDown() {
        chunkManager.shutdown();
    }

    @Test
    void snapshotReportsZeroStateWhenNothingIsLoaded() {
        WorldDebugStats stats = statsProvider.snapshot();

        assertEquals(0, stats.loadedChunkCount());
        assertEquals(0, stats.pendingChunkCount());
        assertEquals(0, stats.pendingMeshCount());
        assertEquals(0L, stats.approximateLoadedVoxelMemoryBytes());
        assertEquals(0L, stats.approximatePendingMeshMemoryBytes());
    }

    @Test
    void snapshotReflectsLoadedChunkCountAndApproximateVoxelMemory() {
        chunkManager.getOrGenerateChunk(0, 0, 0);
        chunkManager.getOrGenerateChunk(1, 0, 0);

        WorldDebugStats stats = statsProvider.snapshot();

        assertEquals(2, stats.loadedChunkCount());
        assertEquals(0, stats.pendingChunkCount());
        assertTrue(stats.approximateLoadedVoxelMemoryBytes() > 0L);
    }

    @Test
    void snapshotIncludesApproximatePendingMeshMemoryWhenPresent() {
        chunkManager.getOrGenerateChunk(0, 0, 0);
        LoadedChunk loadedChunk = chunkManager.getLoadedChunk(new ChunkKey(0, 0, 0));
        MeshSectionData opaqueSection = new MeshSectionData(
                new float[]{0f, 1f, 2f, 3f},
                new short[]{0, 1, 2}
        );
        MeshSectionData transparentSection = new MeshSectionData(
                new float[]{4f, 5f},
                new short[]{0, 1}
        );
        loadedChunk.renderState().setPendingMeshData(new MeshData(
                List.of(opaqueSection),
                List.of(transparentSection)
        ));

        WorldDebugStats stats = statsProvider.snapshot();

        assertTrue(stats.approximatePendingMeshMemoryBytes() > 0L);
    }
}
