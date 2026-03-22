package com.example.abyss.world.render;

public enum ChunkStreamingSweepReason {
  NONE,
  INITIAL,
  CHUNK_CHANGED,
  MOVING,
  IDLE_RESWEEP
}
