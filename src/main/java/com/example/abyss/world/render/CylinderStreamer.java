package com.example.abyss.world.render;

import com.badlogic.gdx.math.Vector3;
import com.example.abyss.world.render.lod.LodTierConfig;

import java.util.Comparator;

/**
 * Shared cylinder-streaming configuration and helpers used by near-field chunk streaming
 * and far-field LOD streaming.
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
   * Returns true if the region-space offset (dx, dy, dz) falls inside the given tier's
   * annulus — i.e. within the outer cylinder but outside the inner exclusion cylinder.
   */
  public boolean isInTierBand(int dx, int dy, int dz, LodTierConfig tier) {
    int adx = Math.abs(dx);
    int adz = Math.abs(dz);
    boolean isInsideOuter = adx <= tier.outerHRadiusRegions()
                         && adz <= tier.outerHRadiusRegions()
                         && dy >= -tier.outerVRadiusRegions()
                         && dy <=  tier.outerVRadiusRegions();
    if (!isInsideOuter) {
      return false;
    }
    boolean isInsideInner = adx < tier.innerHRadiusRegions()
                         && adz < tier.innerHRadiusRegions()
                         && dy > -tier.innerVRadiusRegions()
                         && dy <  tier.innerVRadiusRegions();
    return !isInsideInner;
  }

  /**
   * Returns true if the region-space offset (dx, dy, dz) is still within the unload-safe zone
   * for the given tier. Identical to {@link #isInTierBand} but expands the outer boundary by
   * {@code hysteresisRegions} in each horizontal and vertical direction, so a region must drift
   * that many extra regions beyond the load band before it is eligible for removal.
   */
  public boolean isInTierUnloadZone(int dx, int dy, int dz, LodTierConfig tier, int hysteresisRegions) {
    int adx = Math.abs(dx);
    int adz = Math.abs(dz);
    int expandedOuterH = tier.outerHRadiusRegions() + hysteresisRegions;
    int expandedOuterV = tier.outerVRadiusRegions() + hysteresisRegions;
    boolean isInsideExpandedOuter = adx <= expandedOuterH
                                 && adz <= expandedOuterH
                                 && dy >= -expandedOuterV
                                 && dy <=  expandedOuterV;
    if (!isInsideExpandedOuter) {
      return false;
    }
    boolean isInsideInner = adx < tier.innerHRadiusRegions()
                         && adz < tier.innerHRadiusRegions()
                         && dy > -tier.innerVRadiusRegions()
                         && dy <  tier.innerVRadiusRegions();
    return !isInsideInner;
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