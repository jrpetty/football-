/**
 * Hard capability gates.
 *
 * These run BEFORE estimation and short-circuit it. A mesh-shader failure
 * returns WILL_NOT_RUN, not a low number — the distinction is the whole point.
 * Answering "will it even launch" is something no competing tool does.
 */
import type {
  CpuRecord,
  GameRecord,
  GateFailure,
  GpuRecord,
  RamConfig,
  Resolution,
} from './types.ts';

/** Compare dotted/underscored version strings like '12_2' vs '12_1'. */
export function compareLevel(a: string, b: string): number {
  const pa = a.split(/[._]/).map(Number);
  const pb = b.split(/[._]/).map(Number);
  for (let i = 0; i < Math.max(pa.length, pb.length); i++) {
    const d = (pa[i] ?? 0) - (pb[i] ?? 0);
    if (d !== 0) return d;
  }
  return 0;
}

export interface GateInput {
  gpu: GpuRecord;
  cpu: CpuRecord;
  game: GameRecord;
  ram: RamConfig;
  resolution: Resolution;
}

/**
 * Returns every failure rather than the first, so the UI can say "this build
 * fails on three counts" instead of making the user fix them one at a time.
 */
export function runGates({ gpu, cpu, game, ram, resolution }: GateInput): GateFailure[] {
  const fails: GateFailure[] = [];
  const req = game.requirements;

  if (req.meshShaders && !gpu.caps.meshShaders) {
    fails.push({
      code: 'MESH_SHADER_REQUIRED',
      detail: `${game.name} requires mesh shader support. ${gpu.fullName} (${gpu.architecture}) has none — the game will not launch, regardless of raw performance.`,
      required: 'mesh shaders',
      actual: `${gpu.architecture}: not supported`,
    });
  }

  if (req.rayTracingRequired && !gpu.caps.rayTracing) {
    fails.push({
      code: 'RAY_TRACING_REQUIRED',
      detail: `${game.name} requires hardware ray tracing. ${gpu.fullName} has no RT hardware.`,
      required: 'hardware ray tracing',
      actual: `${gpu.architecture}: not supported`,
    });
  }

  if (req.minDxFeatureLevel && compareLevel(gpu.caps.dxFeatureLevel, req.minDxFeatureLevel) < 0) {
    fails.push({
      code: 'DX_FEATURE_LEVEL',
      detail: `${game.name} requires DirectX feature level ${req.minDxFeatureLevel}; ${gpu.fullName} caps at ${gpu.caps.dxFeatureLevel}.`,
      required: req.minDxFeatureLevel,
      actual: gpu.caps.dxFeatureLevel,
    });
  }

  if (req.minShaderModel && compareLevel(gpu.caps.shaderModel, req.minShaderModel) < 0) {
    fails.push({
      code: 'SHADER_MODEL',
      detail: `${game.name} requires shader model ${req.minShaderModel}; ${gpu.fullName} caps at ${gpu.caps.shaderModel}.`,
      required: req.minShaderModel,
      actual: gpu.caps.shaderModel,
    });
  }

  // VRAM below the published minimum is a launch failure. VRAM merely below
  // demand is a performance cliff, handled in the engine — a different thing.
  if (req.minVramGB != null && gpu.vramGB != null && gpu.vramGB < req.minVramGB) {
    fails.push({
      code: 'VRAM_CEILING',
      detail: `${game.name} requires ${req.minVramGB}GB VRAM minimum; ${gpu.fullName} has ${gpu.vramGB}GB. At ${resolution} this is below the launch floor.`,
      required: `${req.minVramGB}GB`,
      actual: `${gpu.vramGB}GB`,
    });
  }

  if (req.minThreads != null && cpu.threads < req.minThreads) {
    fails.push({
      code: 'THREAD_FLOOR',
      detail: `${game.name} requires ${req.minThreads} hardware threads; ${cpu.fullName} has ${cpu.threads}.`,
      required: `${req.minThreads} threads`,
      actual: `${cpu.threads} threads`,
    });
  }

  if (req.minCores != null && cpu.cores < req.minCores) {
    fails.push({
      code: 'CORE_FLOOR',
      detail: `${game.name} requires ${req.minCores} cores; ${cpu.fullName} has ${cpu.cores}.`,
      required: `${req.minCores} cores`,
      actual: `${cpu.cores} cores`,
    });
  }

  if (req.minSystemRamGB != null && ram.totalGB < req.minSystemRamGB) {
    fails.push({
      code: 'SYSTEM_RAM_FLOOR',
      detail: `${game.name} requires ${req.minSystemRamGB}GB system RAM; build has ${ram.totalGB}GB.`,
      required: `${req.minSystemRamGB}GB`,
      actual: `${ram.totalGB}GB`,
    });
  }

  return fails;
}

/** Whether an upscaling setting is actually available on this GPU + game pair. */
export function upscalingAvailable(
  gpu: GpuRecord,
  game: GameRecord,
  tech: string,
): { available: boolean; reason?: string } {
  if (tech === 'none') return { available: true };
  if (!game.upscalingSupport.includes(tech as never)) {
    return { available: false, reason: `${game.name} does not implement ${tech.toUpperCase()}.` };
  }
  if (!gpu.caps.upscaling.includes(tech as never)) {
    return { available: false, reason: `${gpu.fullName} does not support ${tech.toUpperCase()}.` };
  }
  return { available: true };
}
