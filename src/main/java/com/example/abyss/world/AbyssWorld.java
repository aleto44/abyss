package com.example.abyss.world;

import com.example.abyss.world.meshing.MeshCoordinator;

/**
 * Top-level façade for the Abyss world.
 * <p>
 * Constructs and wires together the four collaborator objects:
 * <ul>
 *   <li>{@link ChunkManager} — chunk storage, async generation, and mesh queuing</li>
 *   <li>{@link WorldVoxelAccess} — world-to-chunk coordinate math and voxel read/write</li>
 *   <li>{@link MeshCoordinator} — mesh dirty/queue/poll lifecycle and render queries</li>
 *   <li>{@link ChunkLoader} — load/unload scheduling around the player</li>
 * </ul>
 * Callers obtain the appropriate collaborator via the getter methods and
 * invoke operations directly on it.
 * </p>
 */
public class AbyssWorld {

    private final long seed;
    private ChunkManager chunkManager;
    private WorldVoxelAccess voxelAccess;
    private MeshCoordinator meshCoordinator;
    private ChunkLoader chunkLoader;

    public AbyssWorld(long seed) {
        this.seed = seed;
    }

    /** Initialises the generation pipeline and all collaborator objects. Call once. */
    public void init() {
        chunkManager = new ChunkManager(seed);
        meshCoordinator = new MeshCoordinator(chunkManager);
        voxelAccess = new WorldVoxelAccess(chunkManager);
        voxelAccess.setEditListener(meshCoordinator::invalidateMeshForEdit);
        chunkLoader = new ChunkLoader(chunkManager, meshCoordinator);
    }

    // ----------------------------------------------------------------
    // Collaborator accessors
    // ----------------------------------------------------------------

    public ChunkManager getChunkManager() { return chunkManager; }
    public WorldVoxelAccess getVoxelAccess() { return voxelAccess; }
    public MeshCoordinator getMeshCoordinator() { return meshCoordinator; }
    public ChunkLoader getChunkLoader() { return chunkLoader; }

    // ----------------------------------------------------------------
    // Lifecycle
    // ----------------------------------------------------------------

    public long getSeed() { return seed; }

    /** Shuts down background generation threads and releases resources. */
    public void dispose() {
        if (chunkManager != null) {
            chunkManager.shutdown();
        }
    }
}
