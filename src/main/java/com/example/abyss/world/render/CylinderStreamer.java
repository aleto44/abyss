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
   * The exclusion boundary is derived from the near-field chunk radii converted to region-space.
   * <p>
   * A region is excluded only when it is <em>fully</em> covered by the near-field cylinder.
   * Strict less-than ({@code <}) is used so that the first region touching the near-field
   * boundary is included in the LOD band, closing the gap between the two streaming
   * representations. With {@code <=} the region at {@code adx == exclusionHRadius} would be
   * excluded even though near-field only covers up to chunk {@code hRadius - 1}, leaving a
   * full region-width strip with no coverage on either side.
   * </p>
   *
   * @param regionSize voxels per region side, used to convert near-field chunk radii to region-space
   */
  public boolean isInLodBand(int dx, int dy, int dz, int regionSize) {
    int adx = Math.abs(dx);
    int adz = Math.abs(dz);
    boolean isInsideOuterCylinder = adx <= bandHRadius && adz <= bandHRadius
        && dy >= -bandVRadius && dy <= bandVRadius;
    if (!isInsideOuterCylinder) {
      return false;
    }
    int exclusionHRadius = (int) Math.ceil((double) hRadius * WorldConstants.CHUNK_SIZE / regionSize);
    int exclusionVRadius = (int) Math.ceil((double) vRadius * WorldConstants.CHUNK_SIZE / regionSize);
    // Strict less-than: exclude only regions fully inside the near-field cylinder.
    boolean isInsideExclusionCylinder = adx < exclusionHRadius && adz < exclusionHRadius
        && dy > -exclusionVRadius && dy < exclusionVRadius;
    return !isInsideExclusionCylinder;
  }

  /**
   * Returns {@code true} if the region-space offset is outside the LOD band keep-zone
   * (band cylinder + 1 region padding on all sides).
   */
  public boolean isOutsideLodBand(int dx, int dy, int dz) {
    return Math.abs(dx) > bandHRadius + 1 || Math.abs(dz) > bandHRadius + 1
        || dy > bandVRadius + 1 || dy < -bandVRadius - 1;
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