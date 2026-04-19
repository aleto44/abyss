package com.example.abyss.world.render.distant;

import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.VertexAttribute;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.example.abyss.WorldConstants;
import com.example.abyss.util.BudgetedDrainQueue;
import com.example.abyss.util.KeyedTaskQueue;
import com.example.abyss.world.gen.Layer;
import com.example.abyss.world.meshing.MeshData;
import com.example.abyss.world.meshing.MeshSectionData;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
/**
 * Owns the macro-chunk heightmap cache, per-band-slab mesh state map, and async generation queue.
 * <p>
 * Each {@link MacroChunkKey} now identifies a single XYZ band-slab, so there is no separate
 * band-index bookkeeping. Heightmaps arrive from background tasks; mesh building fires immediately
 * on arrival since the streamer only requests slabs within the player's load window.
 * </p>
 * <p>
 * Per-frame call order (GL thread):
 * <ol>
 *   <li>{@link #pollCompletedMeshes()} — drains finished heightmaps and queues mesh builds</li>
 *   <li>{@link #uploadPendingMeshes(ShaderProgram)} — promotes pending data to GPU meshes, budget-capped</li>
 *   <li>{@link #getRenderEntries()} — read-only snapshot for the renderer</li>
 * </ol>
 * </p>
 */
public class DistantTerrainManager implements DistantTerrainPort {

    private static final long UPLOAD_BUDGET_NANOS = 4_000_000L; // 4 ms

    private final HeightmapCache heightmapCache = new InMemoryHeightmapCache();
    private final Map<MacroChunkKey, DistantMeshState> meshStateMap = new LinkedHashMap<>();
    private final KeyedTaskQueue<MacroChunkKey, MacroChunkHeightmap> taskQueue;
    private final ThreadLocal<DistantTerrainGenerator> generatorLocal;
    /** Runs heightmap generation tasks — feeds the taskQueue. */
    private final ThreadPoolExecutor heightmapPool;
    /** Runs CPU mesh-build tasks — separate so heightmap generation never starves mesh building. */
    private final ThreadPoolExecutor meshBuildPool;
    private final BudgetedDrainQueue<PendingSectionUpload> promotionQueue = new BudgetedDrainQueue<>();

    /** Accumulated heightmap generation time across all completed tasks, in nanoseconds. */
    private final AtomicLong totalGenerationNanos = new AtomicLong(0L);
    /** Total heightmap generation tasks that have completed and contributed timing data. */
    private final AtomicLong totalGenerationCount = new AtomicLong(0L);

    /** Pending mesh data keyed by slab key, populated by background mesh-build tasks. */
    private final ConcurrentHashMap<MacroChunkKey, MeshData> pendingMeshResults = new ConcurrentHashMap<>();

    /**
     * One GPU-upload unit: a single mesh section destined for a specific state.
     * Splitting at section granularity lets the budget timer fire between individual
     * {@code glBufferData} calls instead of after an entire multi-section slab.
     *
     * @param isLast when true, the memory estimate is written to the target state after upload
     * @param estimatedTotalBytes total byte footprint of the originating {@link MeshData}
     */
    private record PendingSectionUpload(
            DistantMeshState target,
            MeshSectionData  section,
            boolean          opaque,
            boolean          isLast,
            long             estimatedTotalBytes
    ) {}

    public DistantTerrainManager(Supplier<DistantTerrainGenerator> generatorFactory) {
        this.generatorLocal = ThreadLocal.withInitial(generatorFactory);
        this.heightmapPool  = createPool(DistantTerrainConfig.WORKER_THREADS, "DistantTerrainGen-");
        this.meshBuildPool  = createPool(DistantTerrainConfig.WORKER_THREADS, "DistantMeshBuild-");
        this.taskQueue = KeyedTaskQueue.<MacroChunkKey, MacroChunkHeightmap>builder()
                .executor(heightmapPool)
                .build();
    }

    private static ThreadPoolExecutor createPool(int threads, String namePrefix) {
        AtomicInteger counter = new AtomicInteger();
        return new ThreadPoolExecutor(
                threads, threads,
                0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(),
                r -> {
                    Thread t = new Thread(r, namePrefix + counter.getAndIncrement());
                    t.setDaemon(true);
                    t.setPriority(Thread.MIN_PRIORITY);
                    return t;
                }
        );
    }

    // ----------------------------------------------------------------
    // DistantTerrainPort — async generation
    // ----------------------------------------------------------------

    @Override
    public boolean requestMacroChunkAsync(MacroChunkKey key) {
        boolean alreadyKnown = heightmapCache.get(key) != null || taskQueue.isKnown(key);
        if (alreadyKnown) {
            return false;
        }
        return taskQueue.submit(key, () -> {
            long start = System.nanoTime();
            MacroChunkHeightmap result = generatorLocal.get().generate(key);
            long elapsed = System.nanoTime() - start;
            totalGenerationNanos.addAndGet(elapsed);
            totalGenerationCount.incrementAndGet();
            return result;
        });
    }

    @Override
    public boolean isMacroChunkKnown(MacroChunkKey key) {
        boolean isCached  = heightmapCache.get(key) != null;
        boolean isPending = taskQueue.isKnown(key);
        return isCached || isPending;
    }

    @Override
    public int getPendingCount() {
        return taskQueue.pendingCount();
    }

    @Override
    public void pollCompletedMeshes() {
        drainCompletedHeightmaps();
        drainPendingMeshResults();
        // GPU promotion is intentionally NOT done here — it requires a ShaderProgram and
        // must happen in uploadPendingMeshes(), called by the renderer before getRenderSnapshot().
    }

    private void drainCompletedHeightmaps() {
        List<KeyedTaskQueue.Result<MacroChunkKey, MacroChunkHeightmap>> results = taskQueue.pollCompleted();
        for (KeyedTaskQueue.Result<MacroChunkKey, MacroChunkHeightmap> result : results) {
            MacroChunkHeightmap heightmap = result.value();
            boolean hasData = heightmap != null;
            if (hasData) {
                heightmapCache.put(result.key(), heightmap);
                submitMeshBuildTask(result.key(), heightmap.band());
            }
        }
    }

    private void submitMeshBuildTask(MacroChunkKey key, HeightmapBand band) {
        boolean noMeshYet = !meshStateMap.containsKey(key);
        if (!noMeshYet) {
            return;
        }
        meshStateMap.put(key, new DistantMeshState());
        float worldBaseX = key.macroX() * (float) DistantTerrainConfig.MACRO_CHUNK_SIZE_VOXELS;
        float worldBaseZ = key.macroZ() * (float) DistantTerrainConfig.MACRO_CHUNK_SIZE_VOXELS;
        meshBuildPool.execute(() -> {
            MeshData meshData = DistantMeshBuilder.build(band, worldBaseX, worldBaseZ);
            pendingMeshResults.put(key, meshData);
        });
    }

    private void drainPendingMeshResults() {
        for (Map.Entry<MacroChunkKey, MeshData> entry : new ArrayList<>(pendingMeshResults.entrySet())) {
            MacroChunkKey key      = entry.getKey();
            MeshData      meshData = entry.getValue();
            pendingMeshResults.remove(key);
            DistantMeshState meshState = meshStateMap.get(key);
            if (meshState == null) {
                continue;
            }
            enqueueSectionsIndividually(meshState, meshData);
        }
    }

    /** Splits {@code meshData} into per-section upload items so the budget timer fires between {@code glBufferData} calls. */
    private void enqueueSectionsIndividually(DistantMeshState meshState, MeshData meshData) {
        long estimatedBytes = estimateMeshBytes(meshData);
        List<PendingSectionUpload> uploads = new ArrayList<>();
        for (MeshSectionData section : meshData.opaqueSections()) {
            boolean isEmpty = section == null || section.isEmpty();
            if (!isEmpty) {
                uploads.add(new PendingSectionUpload(meshState, section, true, false, estimatedBytes));
            }
        }
        for (MeshSectionData section : meshData.transparentSections()) {
            boolean isEmpty = section == null || section.isEmpty();
            if (!isEmpty) {
                uploads.add(new PendingSectionUpload(meshState, section, false, false, estimatedBytes));
            }
        }
        if (uploads.isEmpty()) {
            return;
        }
        // Mark the last upload so memory accounting fires exactly once.
        PendingSectionUpload last = uploads.get(uploads.size() - 1);
        uploads.set(uploads.size() - 1,
                new PendingSectionUpload(last.target(), last.section(), last.opaque(), true, estimatedBytes));
        for (PendingSectionUpload upload : uploads) {
            promotionQueue.enqueue(upload);
        }
    }

    /**
     * Promotes pending CPU mesh data to GPU meshes on the GL thread, budget-capped.
     * Processes one {@link MeshSectionData} per budget tick so that a single large
     * macro-chunk mesh cannot exceed the frame time budget.
     * Must be called once per frame before {@link #getRenderEntries()}.
     */
    public void uploadPendingMeshes(ShaderProgram shader) {
        promotionQueue.drain(UPLOAD_BUDGET_NANOS, upload -> {
            Mesh mesh = buildSectionMesh(upload.section(), shader);
            if (upload.opaque()) {
                upload.target().getOpaqueMeshes().add(mesh);
            } else {
                upload.target().getTransparentMeshes().add(mesh);
            }
            if (upload.isLast()) {
                upload.target().setApproximateMeshMemoryBytes(upload.estimatedTotalBytes());
            }
        });
    }

    private static Mesh buildSectionMesh(MeshSectionData section, ShaderProgram shader) {
        Mesh mesh = new Mesh(true,
                section.vertices().length / MeshData.FLOATS_PER_VERTEX,
                section.indices().length,
                VertexAttribute.Position(),
                VertexAttribute.Normal(),
                new VertexAttribute(VertexAttributes.Usage.ColorUnpacked, 4, GL20.GL_FLOAT, false, "a_color"));
        mesh.setVertices(section.vertices());
        mesh.setIndices(section.indices());
        // Bind immediately so glBufferData fires now rather than mid-render.
        // This call is what the budget timer gates at section granularity.
        if (shader != null) {
            mesh.bind(shader);
            mesh.unbind(shader);
        }
        return mesh;
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
    // DistantTerrainPort — macro-chunk lifecycle
    // ----------------------------------------------------------------

    @Override
    public void removeMacroChunk(MacroChunkKey key) {
        taskQueue.forget(key);
        heightmapCache.remove(key);
        meshStateMap.remove(key);
        pendingMeshResults.remove(key);
    }

    @Override
    public List<MacroChunkKey> getLoadedKeys() {
        return heightmapCache.getAllKeys();
    }

    @Override
    public List<DistantRenderEntry> getRenderEntries() {
        List<DistantRenderEntry> entries = new ArrayList<>();
        int chunkSize = WorldConstants.CHUNK_SIZE;
        for (Map.Entry<MacroChunkKey, DistantMeshState> entry : meshStateMap.entrySet()) {
            MacroChunkKey    key       = entry.getKey();
            DistantMeshState meshState = entry.getValue();
            boolean hasGeometry = !meshState.getOpaqueMeshes().isEmpty()
                                  || !meshState.getTransparentMeshes().isEmpty();
            if (!hasGeometry) {
                continue;
            }
            float worldX = key.macroX() * (float) DistantTerrainConfig.MACRO_CHUNK_SIZE_VOXELS;
            float worldZ = key.macroZ() * (float) DistantTerrainConfig.MACRO_CHUNK_SIZE_VOXELS;
            float worldY = (Math.floorDiv(Layer.DEEP_EXTREMES.getYEnd(), WorldConstants.CHUNK_SIZE)
                           + key.macroY() * DistantTerrainConfig.VERTICAL_DISTANT_TERRAIN_HEIGHT)
                           * (float) chunkSize;
            entries.add(new DistantRenderEntry(
                    worldX, worldY, worldZ,
                    List.copyOf(meshState.getOpaqueMeshes()),
                    List.copyOf(meshState.getTransparentMeshes())
            ));
        }
        return List.copyOf(entries);
    }

    // ----------------------------------------------------------------
    // Direct access (tests and renderer bootstrapping)
    // ----------------------------------------------------------------

    /** Inserts a single-band heightmap directly. Intended for tests. */
    public void putHeightmap(MacroChunkHeightmap heightmap) {
        heightmapCache.put(heightmap.key(), heightmap);
    }

    /** Inserts a mesh state directly for the given slab key. Intended for tests. */
    public void putMeshState(MacroChunkKey key, DistantMeshState meshState) {
        meshStateMap.put(key, meshState);
    }

    // ----------------------------------------------------------------
    // Queries
    // ----------------------------------------------------------------

    public int getLoadedMacroChunkCount() {
        return heightmapCache.getAllKeys().size();
    }

    public int getActiveBandMeshCount() {
        return meshStateMap.size();
    }

    public long getApproximateMeshMemoryBytes() {
        long totalBytes = 0L;
        for (DistantMeshState meshState : meshStateMap.values()) {
            totalBytes += meshState.getApproximateMeshMemoryBytes();
        }
        return totalBytes;
    }

    /** Task not done after this many nanoseconds is considered stuck. */
    public static final long STUCK_TASK_THRESHOLD_NANOS = 10_000_000_000L;


    public DistantTerrainDebugStats getDebugStats(long evictionCount) {
        long count = totalGenerationCount.get();
        long avgNanos = count > 0 ? totalGenerationNanos.get() / count : 0L;
        return new DistantTerrainDebugStats(
                getLoadedMacroChunkCount(),
                getPendingCount(),
                getActiveBandMeshCount(),
                getApproximateMeshMemoryBytes(),
                evictionCount,
                taskQueue.countStuck(STUCK_TASK_THRESHOLD_NANOS),
                avgNanos,
                promotionQueue.size()
        );
    }

    // ----------------------------------------------------------------
    // Lifecycle
    // ----------------------------------------------------------------

    public void shutdown() {
        heightmapPool.shutdownNow();
        meshBuildPool.shutdownNow();
    }
}
