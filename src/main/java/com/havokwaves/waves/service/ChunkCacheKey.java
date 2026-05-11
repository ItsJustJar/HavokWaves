package com.havokwaves.waves.service;

import java.util.UUID;

record ChunkCacheKey(UUID worldId, int chunkX, int chunkZ) {
}
