package com.example.abyss.world.render;

import com.badlogic.gdx.math.Vector3;
import com.example.abyss.WorldConstants;

import java.util.Comparator;

/**
 * Shared cylinder-streaming configuration and helpers used by near-field chunk streaming
 * and far-field LOD streaming.
 * <p>
 * Owns both cylinder shapes:
 * <ul>
 *   <li>Near-field ({@code hRadius}/{@code vRadius}) — the inner exclusion zone for LOD and
 *       the load/unload cylinder for {@code NearChunkStreamer}.</li>
 *   <li>LOD band ({@code bandHRadius}/{@code bandVRadius}) — the outer cylinder streamed by
 *       {@code LodStreamer}, with the near-field cylinder hollowed out of it.</li>
 * </ul>
 * </p>
 */
public final class CylinderStreamer {

  /** Horizontal radius within which LOD is suppressed, in chunks. Rounded up to the nearest whole region.
   *  Equals ceil(NEAR_FIELD_H_RADIUS / REGION_CHUNKS) * REGION_CHUNKS so that the first LOD region
   *  starts exactly where the near-field border shell ends (hRadius + 1 == LOD_EXCLUSION_H_CHUNKS). */
  public static final int LOD_EXCLUSION_H_CHUNKS =
      (int) Math.ceil((double) WorldConstants.NEAR_FIELD_H_RADIUS
          / WorldConstants.REGION_CHUNKS) * WorldConstants.REGION_CHUNKS;

  /** Vertical radius within which LOD is suppressed, in chunks. Same derivation as LOD_EXCLUSION_H_CHUNKS. */
  public static final int LOD_EXCLUSION_V_CHUNKS =
      (int) Math.ceil((double) WorldConstants.NEAR_FIELD_V_RADIUS
          / WorldConstants.REGION_CHUNKS) * WorldConstants.REGION_CHUNKS;

  static {
    boolean hBandTooSmall = WorldConstants.LOD_BAND_H_RADIUS <= LOD_EXCLUSION_H_CHUNKS;
    boolean vBandTooSmall = WorldConstants.LOD_BAND_V_RADIUS <= LOD_EXCLUSION_V_CHUNKS;
    if (hBandTooSmall) {
      throw new IllegalStateException(
          "LOD_BAND_H_RADIUS (" + WorldConstants.LOD_BAND_H_RADIUS
          + ") must be greater than LOD_EXCLUSION_H_CHUNKS (" + LOD_EXCLUSION_H_CHUNKS
          + "). Increase LOD_BAND_H_RADIUS or decrease NEAR_FIELD_H_RADIUS.");
    }
    if (vBandTooSmall) {
      throw new IllegalStateException(
          "LOD_BAND_V_RADIUS (" + WorldConstants.LOD_BAND_V_RADIUS
          + ") must be greater than LOD_EXCLUSION_V_CHUNKS (" + LOD_EXCLUSION_V_CHUNKS
          + "). Increase LOD_BAND_V_RADIUS or decrease NEAR_FIELD_V_RADIUS.");
    }
  }

  private final int hRadius;
  private final int vRadius;
  private final int bandHRadius;
  private final int bandVRadius;
  private final Vector3 lastPosition = new Vector3(Float.NaN, Float.NaN, Float.NaN);

  public CylinderStreamer(int hRadius, int vRadius, int bandHRadius, int bandVRadius) {
    this.hRadius     = hRadius;
    this.vRadius     = vRadius;
    this.bandHRadius = bandHRadius;
    this.bandVRadius = bandVRadius;
  }

  public int getHRadius() {
    return hRadius;
  }

  public int getVRadius() {
    return vRadius;
  }

  public int getBandHRadius() {
    return bandHRadius;
  }

  public int getBandVRadius() {
    return bandVRadius;
  }

  /**
   * Returns {@code true} if the region-space offset falls within the LOD band cylinder —
   * i.e. inside the outer band cylinder but outside the near-field exclusion cylinder.
   */
  public boolean isInLodBand(int dx, int dy, int dz) {
    int adx = Math.abs(dx);
    int adz = Math.abs(dz);
    boolean isInsideOuterCylinder = adx <= bandHRadius && adz <= bandHRadius
        && dy >= -bandVRadius && dy <= bandVRadius;
    if (!isInsideOuterCylinder) {
      return false;
    }
    int exclusionHRadius = LOD_EXCLUSION_H_CHUNKS / WorldConstants.REGION_CHUNKS;
    int exclusionVRadius = LOD_EXCLUSION_V_CHUNKS / WorldConstants.REGION_CHUNKS;
    boolean isInsideExclusionCylinder = adx < exclusionHRadius && adz < exclusionHRadius
        && dy > -exclusionVRadius && dy < exclusionVRadius;
    return !isInsideExclusionCylinder;
  }

  public boolean isOutsideKeepZone(int dx, int dy, int dz, int horizontalPadding, int verticalPadding) {
    int horizontalDistanceX = Math.abs(dx);
    int horizontalDistanceZ = Math.abs(dz);
    boolean isOutsideHorizontal = horizontalDistanceX > hRadius + horizontalPadding
        || horizontalDistanceZ > hRadius + horizontalPadding;
    if (isOutsideHorizontal) {
      return true;
    }
    boolean isTooFarAbove = dy > vRadius + verticalPadding;
    boolean isTooFarBelow = dy < -vRadius - verticalPadding;
    return isTooFarAbove || isTooFarBelow;
  }

  public boolean updateAndCheckMovement(Vector3 currentPosition, float deltaTime, float movementSweepThreshold) {
    boolean lastPositionKnown = !Float.isNaN(lastPosition.x);
    float previousX = lastPosition.x;
    float previousY = lastPosition.y;
    float previousZ = lastPosition.z;
    lastPosition.set(currentPosition);
    if (!lastPositionKnown) {
      return false;
    }
    float deltaX = currentPosition.x - previousX;
    float deltaY = currentPosition.y - previousY;
    float deltaZ = currentPosition.z - previousZ;
    float distanceMoved = (float) Math.sqrt(deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ);
    float speedThisFrame = deltaTime > 0f ? distanceMoved / deltaTime : 0f;
    return speedThisFrame > movementSweepThreshold;
  }

  public Comparator<int[]> createNearestFirstComparator(float unitSize, Vector3 cameraPosition) {
    float cameraX = cameraPosition.x;
    float cameraY = cameraPosition.y;
    float cameraZ = cameraPosition.z;
    return Comparator.comparingDouble(candidate -> {
      float centerX = candidate[0] * unitSize + unitSize * 0.5f;
      float centerY = candidate[1] * unitSize + unitSize * 0.5f;
      float centerZ = candidate[2] * unitSize + unitSize * 0.5f;
      float deltaX = centerX - cameraX;
      float deltaY = centerY - cameraY;
      float deltaZ = centerZ - cameraZ;
      return deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ;
    });
  }
}