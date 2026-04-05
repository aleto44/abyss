package com.example.abyss.world.render;

import com.badlogic.gdx.math.Vector3;

import java.util.Comparator;

/**
 * Shared cylinder-streaming configuration and helpers used by near-field chunk streaming
 * and distant terrain background streaming.
 */
public final class CylinderStreamer {

  private final int hRadius;
  private final int vRadius;
  private final Vector3 lastPosition = new Vector3(Float.NaN, Float.NaN, Float.NaN);

  public CylinderStreamer(int hRadius, int vRadius) {
    this.hRadius = hRadius;
    this.vRadius = vRadius;
  }

  public int getHRadius() {
    return hRadius;
  }

  public int getVRadius() {
    return vRadius;
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