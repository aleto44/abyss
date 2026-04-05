package com.example.abyss.world.render.lod;

import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.VertexAttribute;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.example.abyss.util.BudgetedDrainQueue;
import com.example.abyss.util.KeyedTaskQueue;
import com.example.abyss.world.meshing.MeshData;
import com.example.abyss.world.meshing.MeshSectionData;
import com.example.abyss.world.render.LodRenderEntry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Owns render-only far-field proxy regions.
 * <p>
 * Proxy mesh generation runs on a pool of background threads via an internal
 * {@link KeyedTaskQueue}. Each worker thread gets its own {@link LodProxyGenerator}
 * instance via a {@code ThreadLocal} factory, keeping far-field work separate from
 * the near-field chunk and meshing thread pools without sharing mutable noise state.
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
public class LodProxyManager implements LodProxyPort {

    /**
     * Maximum wall-clock time spent on LOD mesh uploads per frame.
     * Sized smaller than near-field (8ms) since LOD regions are less urgent.
     */
    private static final long UPLOAD_BUDGET_NANOS = 4_000_000L; // 4 ms

    /**
     * Priority base added to all LOD generation tasks in the shared worker pool.
     * Ensures LOD generation always executes after near-field mesh tasks
     * ({@link com.example.abyss.world.meshing.MeshManager#MESH_PRIORITY} = 10_000)
     * and well after near-field chunk generation (priority 0..~5000).
     * Distance-based ordering within the LOD tier is preserved as a relative offset.
     */
    public static final int LOD_PRIORITY_OFFSET = 100_000_000;

    private final Map<LodRegionKey, LodProxyRegion> loadedRegions = new LinkedHashMap<>();
    private final KeyedTaskQueue<LodRegionKey, MeshData> taskQueue;
    private final ThreadLocal<LodProxyGenerator> generatorLocal;

    /**
     * Regions whose {@link MeshData} is ready but deferred due to budget exhaustion.
     * Drained before newly completed results each frame so nearest-first ordering is preserved.
     */
    private final BudgetedDrainQueue<LodProxyMeshState> promotionQueue = new BudgetedDrainQueue<>();

    public LodProxyManager(Supplier<LodProxyGenerator> generatorFactory, ThreadPoolExecutor sharedPool) {
        this.generatorLocal = ThreadLocal.withInitial(generatorFactory);
        this.taskQueue = KeyedTaskQueue.<LodRegionKey, MeshData>builder()
                .executor(sharedPool)
                .build();
    }

    /** Convenience overload for tests — creates a private minimal thread pool. */
    public LodProxyManager(Supplier<LodProxyGenerator> generatorFactory) {
        this(generatorFactory, new ThreadPoolExecutor(
                2, 2, 0L, TimeUnit.MILLISECONDS,
                new PriorityBlockingQueue<>(),
                r -> { Thread t = new Thread(r, "LodProxyGen-test"); t.setDaemon(true); return t; }
        ));
    }

    // ----------------------------------------------------------------
    // LodProxyPort — async generation
    // ----------------------------------------------------------------

    @Override
    public boolean requestProxyMeshAsync(LodRegionKey key, int priority) {
        boolean alreadyLoaded = loadedRegions.containsKey(key);
        if (alreadyLoaded) {
            return false;
        }
        int adjustedPriority = LOD_PRIORITY_OFFSET + priority;
        boolean submitted = taskQueue.submit(key, () -> generatorLocal.get().generate(key), adjustedPriority);
        if (submitted) {
            LodTierConfig tier = LodConfig.TIERS.get(key.lodLevel());
            float worldX = key.regionX() * tier.regionSizeVoxels();
            float worldY = key.regionY() * tier.regionSizeVoxels();
            float worldZ = key.regionZ() * tier.regionSizeVoxels();
            loadedRegions.put(key, new LodProxyRegion(key, worldX, worldY, worldZ, new LodProxyMeshState()));
        }
        return submitted;
    }

    @Override
    public void reprioritiseProxyMeshAsync(LodRegionKey key, int newPriority) {
        taskQueue.reprioritise(key, LOD_PRIORITY_OFFSET + newPriority);
    }

    @Override
    public boolean isRegionKnown(LodRegionKey key) {
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
        List<KeyedTaskQueue.Result<LodRegionKey, MeshData>> results = taskQueue.pollCompleted();
        for (KeyedTaskQueue.Result<LodRegionKey, MeshData> result : results) {
            LodRegionKey key = result.key();
            LodProxyRegion existing = loadedRegions.get(key);
            boolean stillWanted = existing != null;
            if (!stillWanted) {
                continue;
            }
            LodProxyMeshState meshState = existing.meshState();
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
    // LodProxyPort — region lifecycle
    // ----------------------------------------------------------------

    @Override
    public LodProxyRegion removeRegion(LodRegionKey key) {
        taskQueue.forget(key);
        return loadedRegions.remove(key);
    }

    @Override
    public List<LodRegionKey> getLoadedRegionKeys() {
        return List.copyOf(loadedRegions.keySet());
    }

    @Override
    public List<LodRenderEntry> getRenderEntries() {
        List<LodRenderEntry> renderEntries = new ArrayList<>();
        for (LodProxyRegion region : loadedRegions.values()) {
            renderEntries.add(new LodRenderEntry(
                    region.worldX(),
                    region.worldY(),
                    region.worldZ(),
                    region.key().lodLevel(),
                    List.copyOf(region.meshState().getOpaqueMeshes()),
                    List.copyOf(region.meshState().getTransparentMeshes())
            ));
        }
        return List.copyOf(renderEntries);
    }

    // ----------------------------------------------------------------
    // Direct region access (tests)
    // ----------------------------------------------------------------

    public LodProxyRegion getRegion(LodRegionKey key) {
        return loadedRegions.get(key);
    }

    /** Inserts a region directly. Intended for tests and renderer bootstrapping. */
    public void putRegion(LodProxyRegion region) {
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
        for (LodProxyRegion region : loadedRegions.values()) {
            if (region.meshState().isMeshQueued()) {
                count++;
            }
        }
        return count;
    }

    public long getApproximateLoadedMeshMemoryBytes() {
        long totalBytes = 0L;
        for (LodProxyRegion region : loadedRegions.values()) {
            totalBytes += region.meshState().getApproximateMeshMemoryBytes();
        }
        return totalBytes;
    }

    public LodDebugStats getDebugStats(long demotionCount) {
        return new LodDebugStats(
                getLoadedRegionCount(),
                getPendingMeshCount(),
                getApproximateLoadedMeshMemoryBytes(),
                demotionCount
        );
    }

    // ----------------------------------------------------------------
    // Lifecycle
    // ----------------------------------------------------------------

    public void shutdown() {
        taskQueue.shutdown();
    }
}