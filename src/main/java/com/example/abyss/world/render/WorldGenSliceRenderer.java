package com.example.abyss.world.render;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.example.abyss.world.VoxelChunk;

/**
 * Renders a simple deterministic X/Y slice of the generated world at a fixed Z.
 * <p>
 * Optimized for debug readability and low overhead rather than smooth detail.
 * </p>
 */
public class WorldGenSliceRenderer {

    private static final int DEFAULT_SLICE_Z = 0;
    private static final int DEFAULT_WORLD_CENTER_X = 0;
    private static final int DEFAULT_WORLD_CENTER_Y = -1200;
    private static final int DEFAULT_SAMPLES_WIDE = 96;
    private static final int DEFAULT_SAMPLES_TALL = 64;
    private static final int DEFAULT_WORLD_STEP = 16;

    private final WorldGenSampler sampler;
    private final WorldGenColorResolver colorResolver;

    private int sliceWorldZ = DEFAULT_SLICE_Z;
    private int centerWorldX = DEFAULT_WORLD_CENTER_X;
    private int centerWorldY = DEFAULT_WORLD_CENTER_Y;
    private final int samplesWide = DEFAULT_SAMPLES_WIDE;
    private final int samplesTall = DEFAULT_SAMPLES_TALL;
    private int worldStep = DEFAULT_WORLD_STEP;
    private boolean showChunkGrid;
    private float[] cachedRgba;
    private boolean cacheDirty = true;

    public WorldGenSliceRenderer(WorldGenSampler sampler, WorldGenColorResolver colorResolver) {
        this.sampler = sampler;
        this.colorResolver = colorResolver;
    }

    public void render(ShapeRenderer shapeRenderer,
                       float originX,
                       float originY,
                       float width,
                       float height) {
        ensureColorCache();

        float cellWidth = width / samplesWide;
        float cellHeight = height / samplesTall;
        int colorIndex = 0;

        shapeRenderer.begin(ShapeRenderer.ShapeType.Filled);
        for (int sampleY = 0; sampleY < samplesTall; sampleY++) {
            for (int sampleX = 0; sampleX < samplesWide; sampleX++) {
                shapeRenderer.setColor(
                        cachedRgba[colorIndex],
                        cachedRgba[colorIndex + 1],
                        cachedRgba[colorIndex + 2],
                        cachedRgba[colorIndex + 3]
                );
                colorIndex += 4;
                shapeRenderer.rect(
                        originX + sampleX * cellWidth,
                        originY + (samplesTall - sampleY - 1) * cellHeight,
                        cellWidth + 1f,
                        cellHeight + 1f
                );
            }
        }
        shapeRenderer.end();

        if (showChunkGrid) {
            int startWorldX = centerWorldX - (samplesWide / 2) * worldStep;
            int startWorldY = centerWorldY + (samplesTall / 2) * worldStep;
            renderChunkGrid(shapeRenderer, originX, originY, width, height, cellWidth, cellHeight, startWorldX, startWorldY);
        }
    }

    public String buildLegend() {
        return String.format(
                "Slice X/Y @ Z=%d | Center=(%d,%d,%d) | Step=%d | Samples=%dx%d | Mode=blocky | Chunk grid=%s",
                sliceWorldZ,
                centerWorldX,
                centerWorldY,
                sliceWorldZ,
                worldStep,
                samplesWide,
                samplesTall,
                showChunkGrid ? "on" : "off"
        );
    }

    public void moveCenter(int deltaWorldX, int deltaWorldY) {
        centerWorldX += deltaWorldX;
        centerWorldY += deltaWorldY;
        markCacheDirty();
    }

    public void moveSliceZ(int deltaWorldZ) {
        sliceWorldZ += deltaWorldZ;
        markCacheDirty();
    }

    public void zoom(int direction) {
        if (direction > 0) {
            worldStep = Math.max(4, worldStep / 2);
            markCacheDirty();
            return;
        }
        if (direction < 0) {
            worldStep = Math.min(64, worldStep * 2);
            markCacheDirty();
        }
    }

    public void toggleChunkGrid() {
        showChunkGrid = !showChunkGrid;
    }

    public int getSliceWorldZ() {
        return sliceWorldZ;
    }

    public int getCenterWorldY() {
        return centerWorldY;
    }

    public int getWorldStep() {
        return worldStep;
    }

    public boolean isShowChunkGrid() {
        return showChunkGrid;
    }

    public boolean isCacheDirty() {
        return cacheDirty;
    }

    private void ensureColorCache() {
        if (!cacheDirty && cachedRgba != null) {
            return;
        }

        int sampleCount = samplesWide * samplesTall;
        if (cachedRgba == null || cachedRgba.length != sampleCount * 4) {
            cachedRgba = new float[sampleCount * 4];
        }

        int startWorldX = centerWorldX - (samplesWide / 2) * worldStep;
        int startWorldY = centerWorldY + (samplesTall / 2) * worldStep;
        int colorIndex = 0;
        Color reusableColor = new Color();

        for (int sampleY = 0; sampleY < samplesTall; sampleY++) {
            int worldY = startWorldY - sampleY * worldStep;
            for (int sampleX = 0; sampleX < samplesWide; sampleX++) {
                int worldX = startWorldX + sampleX * worldStep;
                sampler.sampleColor(worldX, worldY, sliceWorldZ, colorResolver, reusableColor);
                cachedRgba[colorIndex++] = reusableColor.r;
                cachedRgba[colorIndex++] = reusableColor.g;
                cachedRgba[colorIndex++] = reusableColor.b;
                cachedRgba[colorIndex++] = reusableColor.a;
            }
        }
        cacheDirty = false;
    }

    private void markCacheDirty() {
        cacheDirty = true;
    }

    private void renderChunkGrid(ShapeRenderer shapeRenderer,
                                 float originX,
                                 float originY,
                                 float width,
                                 float height,
                                 float cellWidth,
                                 float cellHeight,
                                 int startWorldX,
                                 int startWorldY) {
        shapeRenderer.begin(ShapeRenderer.ShapeType.Line);
        shapeRenderer.setColor(colorResolver.getOutlineColor());

        for (int sampleX = 0; sampleX <= samplesWide; sampleX++) {
            int worldX = startWorldX + sampleX * worldStep;
            if (Math.floorMod(worldX, VoxelChunk.SIZE) == 0) {
                float drawX = originX + sampleX * cellWidth;
                shapeRenderer.line(drawX, originY, drawX, originY + height);
            }
        }

        for (int sampleY = 0; sampleY <= samplesTall; sampleY++) {
            int worldY = startWorldY - sampleY * worldStep;
            if (Math.floorMod(worldY, VoxelChunk.SIZE) == 0) {
                float drawY = originY + (samplesTall - sampleY) * cellHeight;
                shapeRenderer.line(originX, drawY, originX + width, drawY);
            }
        }
        shapeRenderer.end();
    }
}