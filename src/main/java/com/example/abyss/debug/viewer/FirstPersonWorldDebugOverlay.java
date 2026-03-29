package com.example.abyss.debug.viewer;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import com.example.abyss.world.WorldDebugStats;
import com.example.abyss.world.render.ChunkStreamingStats;

/**
 * Overlay renderer and debug-info formatter for the first-person world debug viewer.
 */
public class FirstPersonWorldDebugOverlay {

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
            WorldDebugStats worldDebugStats,
            ChunkStreamingStats streamerStats,
            int lastFrustumCulledChunks,
            String controlSummary
    ) {
        String voxelMemoryText = formatByteCount(worldDebugStats.approximateLoadedVoxelMemoryBytes());
        String pendingMeshMemoryText = formatByteCount(worldDebugStats.approximatePendingMeshMemoryBytes());
        String sweepReason = streamerStats.lastSweepReason().name();
        String lodMeshMemoryText = formatByteCount(worldDebugStats.farFieldStats().approximateLoadedProxyMeshMemoryBytes());
        return String.format(
                "%s\nPos: (%.1f, %.1f, %.1f) | Dir: (%.2f, %.2f, %.2f)\nLoaded chunks: %d | Pending chunks: %d | Pending meshes: %d | Frustum-culled: %d\nApprox memory: voxels=%s | pending mesh CPU=%s\nStreamer: reason=%s | swept=%s | submitted=%d | known=%d | budget=%s | unload=%s\nStreamer totals: sweeps=%d | submitted=%d | known=%d | unload scans=%d | chunks unloaded=%d | pending canceled=%d\nLOD: regions=%d | pending=%d | mesh mem=%s | promotions=%d | demotions=%d\n%s\nFPS: %d",
                startupInfo,
                cameraPosition.x,
                cameraPosition.y,
                cameraPosition.z,
                cameraDirection.x,
                cameraDirection.y,
                cameraDirection.z,
                worldDebugStats.loadedChunkCount(),
                worldDebugStats.pendingChunkCount(),
                worldDebugStats.pendingMeshCount(),
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
                worldDebugStats.farFieldStats().loadedProxyRegionCount(),
                worldDebugStats.farFieldStats().pendingProxyMeshCount(),
                lodMeshMemoryText,
                worldDebugStats.farFieldStats().promotionCount(),
                worldDebugStats.farFieldStats().demotionCount(),
                controlSummary,
                Gdx.graphics.getFramesPerSecond()
        );
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