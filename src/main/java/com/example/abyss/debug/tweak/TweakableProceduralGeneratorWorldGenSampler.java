package com.example.abyss.debug.tweak;

import com.badlogic.gdx.graphics.Color;
import com.example.abyss.debug.viewer.WorldGenColorResolver;
import com.example.abyss.debug.viewer.WorldGenDataSampler;
import com.example.abyss.world.AbyssWorld;
import com.example.abyss.world.ChunkKey;
import com.example.abyss.world.VoxelChunk;
import com.example.abyss.world.gen.GenConfigResolver;
import com.example.abyss.world.gen.Layer;
import com.example.abyss.world.gen.LayerGenConfig;
import com.example.abyss.world.gen.LayerRenderConfig;
import com.example.abyss.WorldConstants;
import com.example.abyss.world.voxel.VoxelType;

import java.util.Collection;

/**
 * A {@link WorldGenDataSampler} that uses a {@link GenParamOverrides} store to
 * substitute live-tweaked {@link LayerGenConfig} values in place of the baked
 * {@link Layer} defaults.
 * <p>
 * Delegates chunk storage and async generation to an {@link AbyssWorld} instance
 * whose {@link GenConfigResolver} reads live from the {@link GenParamOverrides} on
 * every chunk generation call. Whenever the overrides revision advances, all loaded
 * and pending chunks are evicted so that subsequent requests regenerate with the
 * updated parameter values.
 * </p>
 */
public class TweakableProceduralGeneratorWorldGenSampler implements WorldGenDataSampler {

    private final AbyssWorld world;
    private final GenParamOverrides overrides;

    /** The overrides revision that the currently loaded chunks were generated from. */
    private long cachedRevision = -1;

    public TweakableProceduralGeneratorWorldGenSampler(long seed, GenParamOverrides overrides) {
        this.overrides = overrides;
        GenConfigResolver liveResolver = this::resolveOverriddenConfig;
        this.world = new AbyssWorld(seed, liveResolver);
        this.world.init();
    }

    @Override
    public boolean sampleColor(int worldX,
                               int worldY,
                               int worldZ,
                               WorldGenColorResolver colorResolver,
                               Color outColor) {
        invalidateCacheIfStale();
        world.getChunkLoader().pollCompletedChunks();

        Layer layer = Layer.forY(worldY);
        Layer.TransitionInfo transition = Layer.getTransition(worldY);

        LayerRenderConfig effectiveRenderConfig;
        boolean inTransition;

        if (transition != null) {
            effectiveRenderConfig = transition.blendedRenderConfig();
            inTransition          = true;
        } else {
            effectiveRenderConfig = layer.getRenderConfig();
            inTransition          = false;
        }

        int chunkX = Math.floorDiv(worldX, WorldConstants.CHUNK_SIZE);
        int chunkY = Math.floorDiv(worldY, WorldConstants.CHUNK_SIZE);
        int chunkZ = Math.floorDiv(worldZ, WorldConstants.CHUNK_SIZE);

        VoxelChunk chunk = world.getChunkManager().getChunk(chunkX, chunkY, chunkZ);
        boolean chunkNotLoaded = chunk == null;
        if (chunkNotLoaded) {
            world.getChunkLoader().requestChunkAsync(chunkX, chunkY, chunkZ);
            return false;
        }

        int localX = Math.floorMod(worldX, WorldConstants.CHUNK_SIZE);
        int localY = Math.floorMod(worldY, WorldConstants.CHUNK_SIZE);
        int localZ = Math.floorMod(worldZ, WorldConstants.CHUNK_SIZE);
        float density       = chunk.getDensity(localX, localY, localZ);
        VoxelType voxelType = chunk.getVoxel(localX, localY, localZ);
        colorResolver.resolveColor(worldX, worldY, density, voxelType, effectiveRenderConfig, inTransition, outColor);
        return true;
    }

    // ---------------------------------------------------------------
    // Cache management
    // ---------------------------------------------------------------

    /**
     * Evicts all loaded and pending chunks from {@link AbyssWorld} when the
     * {@link GenParamOverrides} revision has advanced since the last eviction.
     * Subsequent {@link #sampleColor} calls will re-request the needed chunks
     * asynchronously, regenerating them with the current override values.
     */
    private void invalidateCacheIfStale() {
        long currentRevision = overrides.getRevision();
        boolean cacheIsStale = currentRevision != cachedRevision;
        if (cacheIsStale) {
            evictAllChunks();
            cachedRevision = currentRevision;
        }
    }

    private void evictAllChunks() {
        Collection<VoxelChunk> loaded = world.getChunkManager().getLoadedChunks();
        for (VoxelChunk chunk : loaded) {
            world.getChunkManager().unloadChunk(chunk.getChunkX(), chunk.getChunkY(), chunk.getChunkZ());
        }
        Collection<ChunkKey> pending = world.getChunkManager().getPendingChunkKeys();
        for (ChunkKey key : pending) {
            world.getChunkManager().cancelPendingChunk(key.cx(), key.cy(), key.cz());
        }
    }

    // ---------------------------------------------------------------
    // Config resolution
    // ---------------------------------------------------------------

    /**
     * Resolves the effective {@link LayerGenConfig} for a given world-Y by
     * reading from {@link GenParamOverrides}, blending between adjacent layers
     * when inside a transition zone.
     */
    private LayerGenConfig resolveOverriddenConfig(int worldY, Layer primaryLayer) {
        Layer.TransitionInfo ti = Layer.getTransition(worldY);
        if (ti != null) {
            LayerGenConfig upper = overrides.getConfig(ti.upper());
            LayerGenConfig lower = overrides.getConfig(ti.lower());
            return LayerGenConfig.lerp(upper, lower, ti.blendFactor());
        }
        return overrides.getConfig(primaryLayer);
    }

    /** Shuts down the background generation threads. Call when the debug view closes. */
    public void dispose() {
        world.dispose();
    }
}