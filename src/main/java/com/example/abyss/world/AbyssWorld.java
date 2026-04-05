package com.example.abyss.world;

import com.example.abyss.WorldConstants;
import com.example.abyss.util.SharedWorkerPool;
import com.example.abyss.world.gen.GenConfigResolver;
import com.example.abyss.world.gen.ProceduralGenerator;
import com.example.abyss.world.meshing.MeshCoordinator;
import com.example.abyss.world.render.CylinderStreamer;
import com.example.abyss.world.render.NearChunkStreamer;
import com.example.abyss.world.render.WorldRenderSnapshot;
import com.example.abyss.world.render.WorldRenderSource;
import com.example.abyss.world.render.distant.DistantTerrainGenerator;
import com.example.abyss.world.render.distant.DistantTerrainManager;
import com.example.abyss.world.render.distant.DistantTerrainStreamer;

import java.util.function.Supplier;

/**
 * Top-level façade for the Abyss world.
 * <p>
 * Constructs and wires together all subsystems:
 * <ul>
 *   <li>{@link ChunkManager} — chunk storage, async generation, and mesh queuing</li>
 *   <li>{@link WorldVoxelAccess} — world-to-chunk coordinate math and voxel read/write</li>
 *   <li>{@link MeshCoordinator} — mesh dirty/queue/poll lifecycle and render queries</li>
 *   <li>{@link ChunkLoader} — load/unload scheduling around the player</li>
 *   <li>{@link NearChunkStreamer} — near-field streaming policy</li>
 *   <li>{@link DistantTerrainStreamer} — permanent distant terrain background layer</li>
 * </ul>
 * </p>
 */
public class AbyssWorld implements WorldRenderSource {

  private final long seed;
  private final GenConfigResolver resolver;
  private ChunkManager chunkManager;
  private WorldVoxelAccess voxelAccess;
  private MeshCoordinator meshCoordinator;
  private ChunkLoader chunkLoader;
  private WorldDebugStatsProvider worldDebugStatsProvider;
  private DistantTerrainManager distantTerrainManager;
  private NearChunkStreamer chunkStreamer;
  private DistantTerrainStreamer distantTerrainStreamer;
  private CylinderStreamer cylinderStreamer;

  public AbyssWorld(long seed) {
    this(seed, GenConfigResolver.DEFAULT);
  }

  public AbyssWorld(long seed, GenConfigResolver resolver) {
    this.seed = seed;
    this.resolver = resolver;
  }

  /** Initialises the generation pipeline and all collaborator objects. Call once. */
  public void init() {
    Supplier<ProceduralGenerator> generatorFactory = () -> new ProceduralGenerator(seed, resolver);
    chunkManager = new ChunkManager(generatorFactory, SharedWorkerPool.get().executor());
    meshCoordinator = new MeshCoordinator(chunkManager);
    voxelAccess = new WorldVoxelAccess(chunkManager);
    voxelAccess.setEditListener(meshCoordinator::invalidateMeshForEdit);
    chunkLoader = new ChunkLoader(chunkManager, meshCoordinator);
    cylinderStreamer = new CylinderStreamer(
        WorldConstants.NEAR_FIELD_H_RADIUS,
        WorldConstants.NEAR_FIELD_V_RADIUS
    );
    chunkStreamer = new NearChunkStreamer(chunkLoader, cylinderStreamer);
    distantTerrainManager = new DistantTerrainManager(
        () -> new DistantTerrainGenerator(generatorFactory),
        SharedWorkerPool.get().executor()
    );
    distantTerrainStreamer = new DistantTerrainStreamer(distantTerrainManager, cylinderStreamer);
    worldDebugStatsProvider = new WorldDebugStatsProvider(
        chunkManager, distantTerrainManager, distantTerrainStreamer);
  }

  // ----------------------------------------------------------------
  // Collaborator accessors
  // ----------------------------------------------------------------

  public ChunkManager getChunkManager() {
    return chunkManager;
  }

  public WorldVoxelAccess getVoxelAccess() {
    return voxelAccess;
  }

  public MeshCoordinator getMeshCoordinator() {
    return meshCoordinator;
  }

  public ChunkLoader getChunkLoader() {
    return chunkLoader;
  }

  public NearChunkStreamer getChunkStreamer() {
    return chunkStreamer;
  }

  public WorldDebugStats getWorldDebugStats() {
    return worldDebugStatsProvider.snapshot();
  }

  public DistantTerrainManager getDistantTerrainManager() {
    return distantTerrainManager;
  }

  public DistantTerrainStreamer getDistantTerrainStreamer() {
    return distantTerrainStreamer;
  }

  /** Returns the shared cylinder streamer used by both near-field and distant terrain streaming. */
  public CylinderStreamer getCylinderStreamer() {
    return cylinderStreamer;
  }

  // ----------------------------------------------------------------
  // Lifecycle
  // ----------------------------------------------------------------

  public long getSeed() {
    return seed;
  }

  /** Shuts down background generation threads and releases resources. */
  public void dispose() {
    if (chunkManager != null) {
      chunkManager.shutdown();
    }
    if (distantTerrainManager != null) {
      distantTerrainManager.shutdown();
    }
    SharedWorkerPool.get().shutdown();
  }

  @Override
  public WorldRenderSnapshot getRenderSnapshot() {
    return new WorldRenderSnapshot(
        meshCoordinator.getRenderEntries(),
        distantTerrainManager.getRenderEntries()
    );
  }
}
