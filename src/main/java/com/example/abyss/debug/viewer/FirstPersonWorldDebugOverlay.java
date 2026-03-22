package com.example.abyss.debug.viewer;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import com.example.abyss.world.AbyssWorld;
import com.example.abyss.world.LoadedChunk;
import com.example.abyss.world.meshing.ChunkMeshState;
import com.example.abyss.world.meshing.MeshData;
import com.example.abyss.world.meshing.MeshSectionData;
import com.example.abyss.world.render.ChunkStreamingStats;
import com.example.abyss.world.voxel.VoxelData;

import java.util.List;

/**
 * Overlay renderer and debug-info formatter for the first-person world debug viewer.
 */
public class FirstPersonWorldDebugOverlay {

    private static final long BYTES_PER_FLOAT = 4L;
    private static final long BYTES_PER_REFERENCE = 8L;
    private static final long BYTES_PER_SHORT = 2L;

    private final SpriteBatch batch;
    private final BitmapFont font;

    public FirstPersonWorldDebugOverlay(SpriteBatch batch, BitmapFont font) {
        this.batch = batch;
        this.font = font;
    }

    public void renderOverlay(String debugInfo) {
        batch.setProjectionMatrix(new Matrix4().setToOrtho2D(0f, 0f, Gdx.graphics.getWidth(), Gdx.graphics.getHeight()));
        batch.setColor(Color.WHITE);
        font.setColor(Color.WHITE);

        batch.begin();
        boolean hasDebugInfo = debugInfo != null;
        if (hasDebugInfo) {
            font.draw(batch, debugInfo, 16f, Gdx.graphics.getHeight() - 16f);
        }
        batch.end();
    }

    public String buildDebugInfo(
            String startupInfo,
            Vector3 cameraPosition,
            Vector3 cameraDirection,
            AbyssWorld world,
            ChunkStreamingStats streamerStats,
            int lastFrustumCulledChunks,
            String controlSummary
    ) {
        long approximateVoxelMemoryBytes = estimateLoadedVoxelMemoryBytes(world);
        long approximatePendingMeshMemoryBytes = estimatePendingMeshMemoryBytes(world);
        String voxelMemoryText = formatByteCount(approximateVoxelMemoryBytes);
        String pendingMeshMemoryText = formatByteCount(approximatePendingMeshMemoryBytes);
        String sweepReason = streamerStats.lastSweepReason().name();
        return String.format(
                "%s\nPos: (%.1f, %.1f, %.1f) | Dir: (%.2f, %.2f, %.2f)\nLoaded chunks: %d | Pending chunks: %d | Pending meshes: %d | Frustum-culled: %d\nApprox memory: voxels=%s | pending mesh CPU=%s\nStreamer: reason=%s | swept=%s | submitted=%d | known=%d | budget=%s | unload=%s\nStreamer totals: sweeps=%d | submitted=%d | known=%d | unload scans=%d | chunks unloaded=%d | pending canceled=%d\n%s\nFPS: %d",
                startupInfo,
                cameraPosition.x,
                cameraPosition.y,
                cameraPosition.z,
                cameraDirection.x,
                cameraDirection.y,
                cameraDirection.z,
                world.getChunkManager().getLoadedCount(),
                world.getChunkManager().getPendingCount(),
                world.getChunkManager().getPendingMeshCount(),
                lastFrustumCulledChunks,
                voxelMemoryText,
                pendingMeshMemoryText,
                sweepReason,
                streamerStats.sweepTriggered(),
                streamerStats.submittedThisUpdate(),
                streamerStats.skippedKnownThisUpdate(),
                streamerStats.budgetExhausted(),
                streamerStats.unloadScanTriggered(),
                streamerStats.totalSweepCount(),
                streamerStats.totalSubmittedCount(),
                streamerStats.totalSkippedKnownCount(),
                streamerStats.totalUnloadScanCount(),
                streamerStats.totalUnloadedChunkCount(),
                streamerStats.totalCancelledPendingCount(),
                controlSummary,
                Gdx.graphics.getFramesPerSecond()
        );
    }

    private long estimateLoadedVoxelMemoryBytes(AbyssWorld world) {
        long totalBytes = 0L;
        for (LoadedChunk loadedChunk : world.getChunkManager().iterateLoadedChunks()) {
            VoxelData data = loadedChunk.chunk().getData();
            totalBytes += (long) data.getDensityField().length * BYTES_PER_FLOAT;
            totalBytes += (long) data.getTypeField().length * BYTES_PER_REFERENCE;
        }
        return totalBytes;
    }

    private long estimatePendingMeshMemoryBytes(AbyssWorld world) {
        long totalBytes = 0L;
        for (LoadedChunk loadedChunk : world.getChunkManager().iterateLoadedChunks()) {
            ChunkMeshState renderState = loadedChunk.renderState();
            MeshData pendingMeshData = renderState.getPendingMeshData();
            boolean hasPendingMeshData = pendingMeshData != null;
            if (hasPendingMeshData) {
                totalBytes += estimateMeshDataBytes(pendingMeshData);
            }
        }
        return totalBytes;
    }

    private long estimateMeshDataBytes(MeshData meshData) {
        long opaqueBytes = estimateMeshSectionBytes(meshData.opaqueSections());
        long transparentBytes = estimateMeshSectionBytes(meshData.transparentSections());
        return opaqueBytes + transparentBytes;
    }

    private long estimateMeshSectionBytes(List<MeshSectionData> sections) {
        long totalBytes = 0L;
        for (MeshSectionData section : sections) {
            totalBytes += (long) section.vertices().length * BYTES_PER_FLOAT;
            totalBytes += (long) section.indices().length * BYTES_PER_SHORT;
        }
        return totalBytes;
    }

    private String formatByteCount(long bytes) {
        double kibibytes = bytes / 1024.0;
        double mebibytes = kibibytes / 1024.0;
        boolean isAtLeastOneMebibyte = mebibytes >= 1.0;
        if (isAtLeastOneMebibyte) {
            return String.format("%.2f MiB", mebibytes);
        }
        boolean isAtLeastOneKibibyte = kibibytes >= 1.0;
        if (isAtLeastOneKibibyte) {
            return String.format("%.1f KiB", kibibytes);
        }
        return bytes + " B";
    }
}