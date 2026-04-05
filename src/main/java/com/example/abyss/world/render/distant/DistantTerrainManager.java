package com.example.abyss.world.render.distant;

import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.VertexAttribute;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.example.abyss.util.BudgetedDrainQueue;
import com.example.abyss.util.KeyedTaskQueue;
import com.example.abyss.world.meshing.MeshData;
import com.example.abyss.world.meshing.MeshSectionData;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Owns the permanent distant terrain background region map and their async mesh generation queue.
 * <p>
 * Regions are loaded monotonically — they are never removed during normal play. The only removal
 * path is the safety-eviction call from {@link DistantTerrainStreamer} for regions far beyond
 * the hard horizon limit.
 * </p>
 * <p>
 * Per-frame call order (GL thread):
 * <ol>
 *   <li>{@link #pollCompletedMeshes()} — drains finished CPU mesh data into pending state</li>
 *   <li>{@link #uploadPendingMeshes(ShaderProgram)} — promotes pending data to GPU meshes, budget-capped</li>
 *   <li>{@link #getRenderEntries()} — read-only snapshot for the renderer</li>
 * </ol>
 * </p>
 */
public class DistantTerrainManager implements DistantTerrainPort {

    /**
     * Maximum wall-clock time spent on distant terrain mesh uploads per frame.
     * Sized smaller than near-field (8 ms) since distant terrain is lower priority.
     */
    private static final long UPLOAD_BUDGET_NANOS = 4_000_000L; // 4 ms

    /**
     * Priority base added to all distant terrain generation tasks in the shared worker pool.
     * Ensures distant terrain always executes after near-field chunk generation (priority 0..~5000)
     * and near-field meshing ({@code MeshManager.MESH_PRIORITY} = 10_000).
     * Distance-based ordering within distant terrain is preserved as a relative offset.
     */
    public static final int DISTANT_TERRAIN_PRIORITY_OFFSET = 100_000_000;

    private final Map<DistantRegionKey, DistantRegion> loadedRegions = new LinkedHashMap<>();
    private final KeyedTaskQueue<DistantRegionKey, MeshData> taskQueue;
    private final ThreadLocal<DistantTerrainGenerator> generatorLocal;

    /**
     * Regions whose {@link MeshData} is ready but deferred due to budget exhaustion.
     * Drained before newly completed results each frame so nearest-first ordering is preserved.
     */
    private final BudgetedDrainQueue<DistantMeshState> promotionQueue = new BudgetedDrainQueue<>();

    public DistantTerrainManager(Supplier<DistantTerrainGenerator> generatorFactory,
                                 ThreadPoolExecutor sharedPool) {
        this.generatorLocal = ThreadLocal.withInitial(generatorFactory);
        this.taskQueue = KeyedTaskQueue.<DistantRegionKey, MeshData>builder()
                .executor(sharedPool)
                .build();
    }

    /** Convenience overload for tests — creates a private minimal thread pool. */
    public DistantTerrainManager(Supplier<DistantTerrainGenerator> generatorFactory) {
        this(generatorFactory, new ThreadPoolExecutor(
                2, 2, 0L, TimeUnit.MILLISECONDS,
                new PriorityBlockingQueue<>(),
                r -> {
                    Thread t = new Thread(r, "DistantTerrainGen-test");
                    t.setDaemon(true);
                    return t;
                }
        ));
    }

    // ----------------------------------------------------------------
    // DistantTerrainPort — async generation
    // ----------------------------------------------------------------

    @Override
    public boolean requestRegionAsync(DistantRegionKey key, int priority) {
        boolean alreadyLoaded = loadedRegions.containsKey(key);
        if (alreadyLoaded) {
            return false;
        }
        int adjustedPriority = DISTANT_TERRAIN_PRIORITY_OFFSET + priority;
        boolean submitted = taskQueue.submit(key, () -> generatorLocal.get().generate(key), adjustedPriority);
        if (submitted) {
            float worldX = key.regionX() * DistantTerrainConfig.REGION_SIZE_VOXELS;
            float worldY = key.regionY() * DistantTerrainConfig.REGION_SIZE_VOXELS;
            float worldZ = key.regionZ() * DistantTerrainConfig.REGION_SIZE_VOXELS;
            loadedRegions.put(key, new DistantRegion(key, worldX, worldY, worldZ, new DistantMeshState()));
        }
        return submitted;
    }

    @Override
    public boolean isRegionKnown(DistantRegionKey key) {
        boolean isLoaded = loadedRegions.containsKey(key);
        boolean isPending = taskQueue.isKnown(key);
        return isLoaded || isPending;
    }

    @Override
    public int getPendingRegionCount() {
        return taskQueue.pendingCount();
    }

    @Override
    public void pollCompletedMeshes() {
        List<KeyedTaskQueue.Result<DistantRegionKey, MeshData>> results = taskQueue.pollCompleted();
        for (KeyedTaskQueue.Result<DistantRegionKey, MeshData> result : results) {
            DistantRegionKey key = result.key();
            DistantRegion existing = loadedRegions.get(key);
            boolean stillWanted = existing != null;
            if (!stillWanted) {
                continue;
            }
            DistantMeshState meshState = existing.meshState();
            meshState.setMeshQueued(false);
            meshState.setMeshDirty(false);
            meshState.setPendingMeshData(result.value());
            promotionQueue.enqueue(meshState);
        }
    }

    /**
     * Promotes pending CPU mesh data to GPU meshes on the GL thread, budget-capped to
     * {@link #UPLOAD_BUDGET_NANOS} per frame. Must be called once per frame before
     * {@link #getRenderEntries()}.
     */
    public void uploadPendingMeshes(ShaderProgram shader) {
        promotionQueue.drain(UPLOAD_BUDGET_NANOS, meshState -> {
            MeshData pending = meshState.getPendingMeshData();
            boolean hasPending = pending != null;
            if (!hasPending) {
                return;
            }
            meshState.setOpaqueMeshes(buildMeshList(pending.opaqueSections(), shader));
            meshState.setTransparentMeshes(buildMeshList(pending.transparentSections(), shader));
            meshState.setPendingMeshData(null);
            meshState.setApproximateMeshMemoryBytes(estimateMeshBytes(pending));
        });
    }

    private static List<Mesh> buildMeshList(List<MeshSectionData> sections, ShaderProgram shader) {
        List<Mesh> meshes = new ArrayList<>();
        for (MeshSectionData section : sections) {
            boolean isEmpty = section == null || section.isEmpty();
            if (isEmpty) {
                continue;
            }
            Mesh mesh = new Mesh(true,
                    section.vertices().length / MeshData.FLOATS_PER_VERTEX,
                    section.indices().length,
                    VertexAttribute.Position(),
                    VertexAttribute.Normal(),
                    new VertexAttribute(VertexAttributes.Usage.ColorUnpacked, 4, GL20.GL_FLOAT, false, "a_color"));
            mesh.setVertices(section.vertices());
            mesh.setIndices(section.indices());
            boolean canPreUpload = shader != null;
            if (canPreUpload) {
                mesh.bind(shader);
                mesh.unbind(shader);
            }
            meshes.add(mesh);
        }
        return meshes;
    }

    private static long estimateMeshBytes(MeshData data) {
        long bytes = 0L;
        for (MeshSectionData s : data.opaqueSections()) {
            bytes += (long) s.vertices().length * Float.BYTES + (long) s.indices().length * Short.BYTES;
        }
        for (MeshSectionData s : data.transparentSections()) {
            bytes += (long) s.vertices().length * Float.BYTES + (long) s.indices().length * Short.BYTES;
        }
        return bytes;
    }

    // ----------------------------------------------------------------
    // DistantTerrainPort — region lifecycle
    // ----------------------------------------------------------------

    @Override
    public DistantRegion removeRegion(DistantRegionKey key) {
        taskQueue.forget(key);
        return loadedRegions.remove(key);
    }

    @Override
    public List<DistantRegionKey> getLoadedRegionKeys() {
        return List.copyOf(loadedRegions.keySet());
    }

    @Override
    public List<DistantRenderEntry> getRenderEntries() {
        List<DistantRenderEntry> renderEntries = new ArrayList<>();
        for (DistantRegion region : loadedRegions.values()) {
            renderEntries.add(new DistantRenderEntry(
                    region.worldX(),
                    region.worldY(),
                    region.worldZ(),
                    List.copyOf(region.meshState().getOpaqueMeshes()),
                    List.copyOf(region.meshState().getTransparentMeshes())
            ));
        }
        return List.copyOf(renderEntries);
    }

    // ----------------------------------------------------------------
    // Direct region access (tests and renderer bootstrapping)
    // ----------------------------------------------------------------

    public DistantRegion getRegion(DistantRegionKey key) {
        return loadedRegions.get(key);
    }

    /** Inserts a region directly. Intended for tests and renderer bootstrapping. */
    public void putRegion(DistantRegion region) {
        loadedRegions.put(region.key(), region);
    }

    // ----------------------------------------------------------------
    // Queries
    // ----------------------------------------------------------------

    public int getLoadedRegionCount() {
        return loadedRegions.size();
    }

    public int getPendingMeshCount() {
        int count = 0;
        for (DistantRegion region : loadedRegions.values()) {
            if (region.meshState().isMeshQueued()) {
                count++;
            }
        }
        return count;
    }

    public long getApproximateLoadedMeshMemoryBytes() {
        long totalBytes = 0L;
        for (DistantRegion region : loadedRegions.values()) {
            totalBytes += region.meshState().getApproximateMeshMemoryBytes();
        }
        return totalBytes;
    }

    public DistantTerrainDebugStats getDebugStats(long evictionCount) {
        return new DistantTerrainDebugStats(
                getLoadedRegionCount(),
                getPendingMeshCount(),
                getApproximateLoadedMeshMemoryBytes(),
                evictionCount
        );
    }

    // ----------------------------------------------------------------
    // Lifecycle
    // ----------------------------------------------------------------

    public void shutdown() {
        taskQueue.shutdown();
    }
}
