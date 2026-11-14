// Routes over the ground for any map view: the terrain grid of a map (loaded
// when first needed, about 300 KB, then kept), the gas zones to keep out of,
// and the function the map uses to follow roads and fields.

import { useEffect, useMemo, useState } from 'react'
import type { DayzMap, DayzMapId } from '../../core/dayz/places.ts'
import { decodeTerrain, gasMask, groundRoute, type Terrain, type TerrainData, type Travel } from '../../core/dayz/terrain.ts'
import type { FollowGround } from './dayzMap.tsx'

const TERRAIN: Readonly<Record<DayzMapId, () => Promise<{ default: TerrainData }>>> = {
  chernarus: () => import('../../profiles/dayz/terrain.chernarus.json'),
  livonia: () => import('../../profiles/dayz/terrain.livonia.json'),
  sakhal: () => import('../../profiles/dayz/terrain.sakhal.json'),
}
const cache = new Map<DayzMapId, Terrain>()

/** Follow the ground on this map (on foot, by car), or straight lines (null). */
export function useFollowGround(map: DayzMap, travel: Travel | 'straight'): FollowGround | null {
  const [terrain, setTerrain] = useState<Terrain | null>(cache.get(map.id) ?? null)
  useEffect(() => {
    let live = true
    const cached = cache.get(map.id)
    if (cached) setTerrain(cached)
    else {
      setTerrain(null)
      void TERRAIN[map.id]().then((m) => {
        const t = decodeTerrain(m.default)
        cache.set(map.id, t)
        if (live) setTerrain(t)
      })
    }
    return () => {
      live = false
    }
  }, [map.id])
  // Gas zones are kept out of (on foot as a last resort, by car never).
  const gas = useMemo(() => (terrain ? gasMask(terrain, map.world.contaminated) : null), [terrain, map])
  return useMemo(() => {
    if (!terrain || travel === 'straight') return null
    return (points) => {
      const r = groundRoute(terrain, points, travel, gas)
      return { legs: r.legs.map((l) => l?.points ?? null), lengthM: r.lengthM, road: r.road, travel }
    }
  }, [terrain, travel, gas])
}
