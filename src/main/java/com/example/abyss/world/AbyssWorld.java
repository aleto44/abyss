package com.example.abyss.world;

import com.example.abyss.WorldConstants;
import com.example.abyss.world.gen.GenConfigResolver;
import com.example.abyss.world.gen.ProceduralGenerator;
import com.example.abyss.world.meshing.MeshCoordinator;
import com.example.abyss.world.render.CylinderStreamer;
import com.example.abyss.world.render.NearChunkStreamer;
import com.example.abyss.world.render.WorldRenderSnapshot;
import com.example.abyss.world.render.WorldRenderSource;
import com.example.abyss.world.render.lod.LodProxyGenerator;
import com.example.abyss.world.render.lod.LodProxyManager;
import com.example.abyss.world.render.lod.LodStreamer;

import java.util.function.Supplier;

/**
 * Top-level façade for the Abyss world.
 * <p>
 * Constructs and wires together the four collaborator objects:
 * <ul>
 *   <li>{@link ChunkManager} — chunk storage, async generation, and mesh queuing</li>
 *   <li>{@link WorldVoxelAccess} — world-to-chunk coordinate math and voxel read/write</li>
 *   <li>{@link MeshCoordinator} — mesh dirty/queue/poll lifecycle and render queries</li>
 *   <li>{@link ChunkLoader} — load/unload scheduling around the player</li>
 *   <li>{@link NearChunkStreamer} — near-field streaming policy</li>
 * </ul>
 * {@link LodStreamer} is constructed from the same shared cylinder configuration,
 * so the LOD exclusion zone is always derived from the actual near-field radius — it is
 * not possible to accidentally configure them with mismatched radii.
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
  private LodProxyManager lodProxyManager;
  private NearChunkStreamer chunkStreamer;
  private LodStreamer lodStreamer;
  private CylinderStreamer cylinderStreamer;

  public AbyssWorld(long seed) {
    this(seed, GenConfigResolver.DEFAULT);
  }

  public AbyssWorld(long seed, GenConfigResolver resolver) {
    this.seed = seed;
    this.resolver = resolver;
  }

  /**
   * Initialises the generation pipeline and all collaborator objects. Call once.
   */
  public void init() {
    Supplier<ProceduralGenerator> generatorFactory = () -> new ProceduralGenerator(seed, resolver);
    chunkManager = new ChunkManager(generatorFactory);
    meshCoordinator = new MeshCoordinator(chunkManager);
    voxelAccess = new WorldVoxelAccess(chunkManager);
    voxelAccess.setEditListener(meshCoordinator::invalidateMeshForEdit);
    chunkLoader = new ChunkLoader(chunkManager, meshCoordinator);
    cylinderStreamer = new CylinderStreamer(
        WorldConstants.NEAR_FIELD_H_RADIUS,
        WorldConstants.NEAR_FIELD_V_RADIUS,
        WorldConstants.LOD_BAND_H_RADIUS / WorldConstants.REGION_CHUNKS,
        WorldConstants.LOD_BAND_V_RADIUS / WorldConstants.REGION_CHUNKS
    );
    chunkStreamer = new NearChunkStreamer(chunkLoader, cylinderStreamer);
    lodProxyManager = new LodProxyManager(() -> new LodProxyGenerator(generatorFactory));
    lodStreamer = new LodStreamer(lodProxyManager, cylinderStreamer);
    worldDebugStatsProvider = new WorldDebugStatsProvider(chunkManager, lodProxyManager, lodStreamer);
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

  public LodProxyManager getLodProxyManager() {
    return lodProxyManager;
  }

  public LodStreamer getLodStreamer() {
    return lodStreamer;
  }

  /** Returns the shared cylinder streamer used by both near-field and LOD streaming. */
  public CylinderStreamer getCylinderStreamer() {
    return cylinderStreamer;
  }

  // ----------------------------------------------------------------
  // Lifecycle
  // ----------------------------------------------------------------

  public long getSeed() {
    return seed;
  }

  /**
   * Shuts down background generation threads and releases resources.
   */
  public void dispose() {
    if (chunkManager != null) {
      chunkManager.shutdown();
    }
    if (lodProxyManager != null) {
      lodProxyManager.shutdown();
    }
  }

  @Override
  public WorldRenderSnapshot getRenderSnapshot() {
    return new WorldRenderSnapshot(meshCoordinator.getRenderEntries(), lodProxyManager.getRenderEntries());
  }
}