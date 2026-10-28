// The official overhead maps the session keeps, as the map views use them.

import { useMemo } from 'react'
import type { OverheadInfo } from '../../shared/bridge.ts'
import type { OfficialSource } from './components/MapView.tsx'
import type { CoachSession } from './session.ts'
import { hasLayout, layoutPicture } from './layouts.ts'

/** `pack`: the pack whose maps' real layouts (where it ships them) stand in for maps with no kept picture. */
export function useOfficialSource(session: CoachSession, kept: Readonly<Record<string, OverheadInfo>>, pack?: string): OfficialSource {
  return useMemo(
    () => ({
      kept,
      load: (id) => session.overhead(id),
      place: (m, id) => session.placeStored(m, id),
      adjust: (m, piece, a) => session.adjustStored(m, piece, a),
      ...(pack ? { layout: { has: (id: string) => hasLayout(pack, id), load: (id: string, level: 'upper' | 'lower') => layoutPicture(pack, id, level) } } : {}),
    }),
    [session, kept, pack],
  )
}
