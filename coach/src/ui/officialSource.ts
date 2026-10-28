// The official overhead maps the session keeps, as the map views use them.

import { useMemo } from 'react'
import type { OverheadInfo } from '../../shared/bridge.ts'
import type { OfficialSource } from './components/MapView.tsx'
import type { CoachSession } from './session.ts'

export function useOfficialSource(session: CoachSession, kept: Readonly<Record<string, OverheadInfo>>): OfficialSource {
  return useMemo(
    () => ({
      kept,
      load: (id) => session.overhead(id),
      place: (m, id) => session.placeStored(m, id),
      adjust: (m, piece, a) => session.adjustStored(m, piece, a),
    }),
    [session, kept],
  )
}
