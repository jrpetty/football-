// Neural voices the coach can speak with. They run on this computer (sherpa-onnx)
// once the player downloads the voice model; nothing is sent anywhere.

export type TtsModelKey = 'jenny' | 'kokoro'

export interface NeuralVoice {
  /** Stored in settings as the voice id; always starts with "neural:". */
  readonly id: string
  readonly model: TtsModelKey
  /** Speaker index inside the model. */
  readonly sid: number
  readonly name: string
  readonly accent: 'British' | 'American'
  readonly gender: 'female' | 'male'
}

export const NEURAL_VOICES: readonly NeuralVoice[] = [
  { id: 'neural:jenny', model: 'jenny', sid: 0, name: 'Jenny', accent: 'British', gender: 'female' },
  // Kokoro v0.19 speaker order: af, af_bella, af_nicole, af_sarah, af_sky, am_adam, am_michael, bf_emma, bf_isabella, bm_george, bm_lewis.
  { id: 'neural:kokoro:bella', model: 'kokoro', sid: 1, name: 'Bella', accent: 'American', gender: 'female' },
  { id: 'neural:kokoro:sarah', model: 'kokoro', sid: 3, name: 'Sarah', accent: 'American', gender: 'female' },
  { id: 'neural:kokoro:sky', model: 'kokoro', sid: 4, name: 'Sky', accent: 'American', gender: 'female' },
  { id: 'neural:kokoro:adam', model: 'kokoro', sid: 5, name: 'Adam', accent: 'American', gender: 'male' },
  { id: 'neural:kokoro:michael', model: 'kokoro', sid: 6, name: 'Michael', accent: 'American', gender: 'male' },
  { id: 'neural:kokoro:emma', model: 'kokoro', sid: 7, name: 'Emma', accent: 'British', gender: 'female' },
  { id: 'neural:kokoro:isabella', model: 'kokoro', sid: 8, name: 'Isabella', accent: 'British', gender: 'female' },
  { id: 'neural:kokoro:george', model: 'kokoro', sid: 9, name: 'George', accent: 'British', gender: 'male' },
  { id: 'neural:kokoro:lewis', model: 'kokoro', sid: 10, name: 'Lewis', accent: 'British', gender: 'male' },
]

export function neuralVoice(id: string | null | undefined): NeuralVoice | null {
  return NEURAL_VOICES.find((v) => v.id === id) ?? null
}

export interface ModelState {
  readonly installed: boolean
  readonly installing: boolean
  /** 0..1 while installing. */
  readonly progress: number
  readonly error: string | null
  readonly sizeMb: number
}

export interface TtsStatus {
  readonly available: boolean
  readonly models: Readonly<Record<TtsModelKey, ModelState>>
  /** Measured speed on this computer (seconds of compute per second of speech), once known. */
  readonly realTimeFactor: Readonly<Partial<Record<TtsModelKey, number>>>
}

export interface SynthResult {
  readonly samples: Float32Array
  readonly sampleRate: number
}

/** Longest text synthesised in one call; longer text is split into sentences first. */
export const MAX_SYNTH_CHARS = 400
