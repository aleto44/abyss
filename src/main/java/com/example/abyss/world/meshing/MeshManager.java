package com.example.abyss.world.meshing;

import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.VertexAttribute;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.example.abyss.util.KeyedTaskQueue;
import com.example.abyss.world.ChunkKey;
import com.example.abyss.world.LoadedChunk;
import com.example.abyss.world.VoxelChunk;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Function;

/**
 * Coordinates async chunk meshing and main-thread LibGDX Mesh promotion.
 */
public class MeshManager {

    /**
     * Maximum wall-clock time spent on mesh uploads ({@code glBufferData}) per frame.
     * 8 ms at 60 fps leaves ~8 ms for the rest of the frame. Raise if chunks are
     * loading too slowly; lower if frame pacing feels uneven.
     */
    private static final long UPLOAD_BUDGET_NANOS = 8_000_000L; // 8 ms

    /**
     * Maximum wall-clock time spent on mesh deletions ({@code glDeleteBuffers}) per frame.
     * Kept separate and smaller so deletions never eat into upload time.
     */
    private static final long DISPOSAL_BUDGET_NANOS = 2_000_000L; // 2 ms

    private final KeyedTaskQueue<ChunkKey, MeshData> taskQueue;

    /**
     * Chunks whose {@link MeshData} is ready but was deferred because the upload budget
     * was exhausted in a prior frame. Stored explicitly so we never need to scan all
     * loaded chunks looking for pending data.
     */
    private final Queue<ChunkMeshState> pendingPromotion = new ArrayDeque<>();

    /**
     * Meshes queued for deferred GL deletion, drained at a capped rate each frame
     * so that bulk {@code glDeleteBuffers} calls never stall the main thread.
     */
    private final Queue<Mesh> pendingDispose = new ConcurrentLinkedQueue<>();

    public MeshManager() {
        this.taskQueue = KeyedTaskQueue.<ChunkKey, MeshData>builder()
                .threadNamePrefix("ChunkMesh")
                .build();
    }

    public boolean requestMesh(ChunkKey key,
                               Function<ChunkKey, LoadedChunk> loadedChunkLookup,
                               Function<ChunkKey, VoxelChunk> chunkLookup) {
        LoadedChunk loadedChunk = loadedChunkLookup.apply(key);
        if (loadedChunk == null) {
            return false;
        }

        ChunkMeshState renderState = loadedChunk.renderState();
        VoxelChunk center = loadedChunk.chunk();
        if (!renderState.isMeshDirty() || renderState.isMeshQueued()) {
            return false;
        }
        if (!ChunkMesher.hasRequiredNeighbors(key, chunkLookup)) {
            return false;
        }

        VoxelChunk plusX  = chunkLookup.apply(new ChunkKey(key.cx() + 1, key.cy(), key.cz()));
        VoxelChunk plusY  = chunkLookup.apply(new ChunkKey(key.cx(), key.cy() + 1, key.cz()));
        VoxelChunk plusZ  = chunkLookup.apply(new ChunkKey(key.cx(), key.cy(), key.cz() + 1));
        VoxelChunk minusX = chunkLookup.apply(new ChunkKey(key.cx() - 1, key.cy(), key.cz()));
        VoxelChunk minusY = chunkLookup.apply(new ChunkKey(key.cx(), key.cy() - 1, key.cz()));
        VoxelChunk minusZ = chunkLookup.apply(new ChunkKey(key.cx(), key.cy(), key.cz() - 1));

        boolean submitted = taskQueue.submit(key, () -> ChunkMesher.buildMesh(center, plusX, plusY, plusZ, minusX, minusY, minusZ));
        if (submitted) {
            renderState.setMeshQueued(true);
        }
        return submitted;
    }

    /**
     * Drains completed mesh results on the main thread each frame.
     * <p>
     * Uploads ({@code glBufferData}) are capped to {@link #UPLOAD_BUDGET_NANOS} and
     * deletions ({@code glDeleteBuffers}) to {@link #DISPOSAL_BUDGET_NANOS}, with
     * separate budgets so deletions never steal time from uploads.
     * Deferred promotions from prior frames are held in {@link #pendingPromotion}
     * and drained before newly completed results, so the closest chunks always
     * appear first.
     * </p>
     *
     * @param loadedChunkLookup function to resolve a {@link LoadedChunk} by key
     * @param shader            used to pre-upload VBOs immediately after mesh creation
     */
    public void pollCompleted(Function<ChunkKey, LoadedChunk> loadedChunkLookup,
                              ShaderProgram shader) {
        drainDisposeQueue();

        long uploadDeadlineNanos = System.nanoTime() + UPLOAD_BUDGET_NANOS;

        // Drain deferred promotions from prior frames first — these are nearest-first
        // ordered from when they were originally submitted, so closest chunks always win.
        while (!pendingPromotion.isEmpty()) {
            boolean budgetExhausted = System.nanoTime() >= uploadDeadlineNanos;
            if (budgetExhausted) {
                return;
            }
            ChunkMeshState renderState = pendingPromotion.poll();
            MeshData pending = renderState.getPendingMeshData();
            boolean stillPending = pending != null && !renderState.isMeshQueued();
            if (stillPending) {
                promotePendingMesh(renderState, shader);
                renderState.setMeshDirty(false);
            }
        }

        // Then drain newly completed results from the background meshing queue.
        List<KeyedTaskQueue.Result<ChunkKey, MeshData>> results = taskQueue.pollCompleted();
        for (KeyedTaskQueue.Result<ChunkKey, MeshData> result : results) {
            LoadedChunk loadedChunk = loadedChunkLookup.apply(result.key());
            if (loadedChunk == null) {
                continue;
            }

            ChunkMeshState renderState = loadedChunk.renderState();
            renderState.setMeshQueued(false);
            renderState.setPendingMeshData(result.value());

            boolean budgetExhausted = System.nanoTime() >= uploadDeadlineNanos;
            if (budgetExhausted) {
                pendingPromotion.add(renderState);
                continue;
            }
            promotePendingMesh(renderState, shader);
            renderState.setMeshDirty(false);
        }
    }

    public void promotePendingMesh(ChunkMeshState renderState, ShaderProgram shader) {
        MeshData pending = renderState.getPendingMeshData();
        if (pending == null) {
            return;
        }

        enqueueMeshesForDisposal(renderState.getOpaqueMeshes());
        enqueueMeshesForDisposal(renderState.getTransparentMeshes());

        renderState.setOpaqueMeshes(buildMeshes(pending.opaqueSections(), shader));
        renderState.setTransparentMeshes(buildMeshes(pending.transparentSections(), shader));
        renderState.setPendingMeshData(null);
    }

    public void forget(ChunkKey key) {
        taskQueue.forget(key);
    }

    public int getPendingCount() {
        return taskQueue.pendingCount();
    }

    public void disposeChunkMeshes(ChunkMeshState renderState) {
        enqueueMeshesForDisposal(renderState.getOpaqueMeshes());
        enqueueMeshesForDisposal(renderState.getTransparentMeshes());
        renderState.setOpaqueMeshes(new ArrayList<>());
        renderState.setTransparentMeshes(new ArrayList<>());
        renderState.setPendingMeshData(null);
        renderState.setMeshQueued(false);
    }

    public void shutdown() {
        taskQueue.shutdown();
        // Drain whatever remains — no frame budget on exit.
        Mesh mesh;
        while ((mesh = pendingDispose.poll()) != null) {
            mesh.dispose();
        }
    }

    private static List<Mesh> buildMeshes(List<MeshSectionData> sections, ShaderProgram shader) {
        List<Mesh> meshes = new ArrayList<>();
        for (MeshSectionData section : sections) {
            Mesh mesh = buildMesh(section, shader);
            if (mesh != null) {
                meshes.add(mesh);
            }
        }
        return meshes;
    }

    /**
     * Creates a LibGDX {@link Mesh} from the given section data and immediately
     * forces the GPU upload by calling {@code bind}/{@code unbind}.
     * <p>
     * LibGDX's {@code VertexBufferObject} defers the actual {@code glBufferData}
     * call until the mesh is first bound for rendering.  Doing it here — inside
     * the budget-capped {@code pollCompleted} window — means the render loop never
     * encounters a lazy-upload stall.
     * </p>
     */
    private static Mesh buildMesh(MeshSectionData section, ShaderProgram shader) {
        if (section == null || section.isEmpty()) {
            return null;
        }
        float[] vertices = section.vertices();
        short[] indices = section.indices();
        Mesh mesh = new Mesh(true,
                vertices.length / MeshData.FLOATS_PER_VERTEX,
                indices.length,
                VertexAttribute.Position(),
                VertexAttribute.Normal(),
                new VertexAttribute(VertexAttributes.Usage.ColorUnpacked, 4, GL20.GL_FLOAT, false, "a_color"));
        mesh.setVertices(vertices);
        mesh.setIndices(indices);
        boolean canPreUpload = shader != null;
        if (canPreUpload) {
            mesh.bind(shader);
            mesh.unbind(shader);
        }
        return mesh;
    }

    /**
     * Drains the pending-dispose queue up to {@link #DISPOSAL_BUDGET_NANOS} per frame.
     * Uses its own budget so deletions never compete with uploads for time.
     */
    private void drainDisposeQueue() {
        long disposalDeadlineNanos = System.nanoTime() + DISPOSAL_BUDGET_NANOS;
        while (System.nanoTime() < disposalDeadlineNanos) {
            Mesh mesh = pendingDispose.poll();
            boolean queueEmpty = mesh == null;
            if (queueEmpty) {
                break;
            }
            mesh.dispose();
        }
    }

    private void enqueueMeshesForDisposal(List<Mesh> meshes) {
        if (meshes == null) {
            return;
        }
        for (Mesh mesh : meshes) {
            if (mesh != null) {
                pendingDispose.add(mesh);
            }
        }
    }
}
