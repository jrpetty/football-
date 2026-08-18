
## 2026-08-18T16:36:25.769Z
- fixtures: 139 (9 holdout)
- median APE: 32.0%, p90: 60.4%, spearman: 0.895, sign: n/a
- verdict: FAIL (advisory — recalled fixtures)
  - only 139 fixtures; 150 needed for the metrics to be statistically meaningful
  - median APE 32.0% exceeds 15.0%
  - p90 APE 60.4% exceeds 30.0%
  - spearman 0.895 below 0.9
  - train/holdout median APE gap 11.4% exceeds 8.0% — the model is fitting the fixture set, not reality

## 2026-08-18T16:38:47.783Z
- fixtures: 139 (9 holdout)
- median APE: 20.9%, p90: 44.6%, spearman: 0.895, sign: n/a
- verdict: FAIL (advisory — recalled fixtures)
  - only 139 fixtures; 150 needed for the metrics to be statistically meaningful
  - median APE 20.9% exceeds 15.0%
  - p90 APE 44.6% exceeds 30.0%
  - spearman 0.895 below 0.9

## 2026-08-18T16:39:49.347Z
- fixtures: 143 (10 holdout)
- median APE: 19.4%, p90: 44.6%, spearman: 0.912, sign: n/a
- verdict: FAIL (advisory — recalled fixtures)
  - only 143 fixtures; 150 needed for the metrics to be statistically meaningful
  - median APE 19.4% exceeds 15.0%
  - p90 APE 44.6% exceeds 30.0%

## calibration 2026-08-18T16:42:06.080Z
- fixtures: 143 (ADVISORY — recalled)
- objective 0.1784 -> 0.1202 median APE
- pass 1: GPU_MODEL.computeWeight 0.7 -> 0.55 (objective 0.3374)
- pass 1: GPU_MODEL.sublinearExponent 0.85 -> 0.75 (objective 0.3322)
- pass 1: CPU_MODEL.threadExponent 0.3 -> 0.2 (objective 0.3294)
- pass 1: COMBINE.p 6.58 -> 4 (objective 0.3213)
- pass 1: RT_MODEL.onVsOff 0.55 -> 0.7 (objective 0.3092)
- pass 1: CPU_WEIGHTS.sim-cpu.cacheEndowment 0.42 -> 0.85 (objective 0.3081)
- pass 1: VRAM_MODEL.cliff[2].avgMultiplier 0.62 -> 0.5 (objective 0.3079)
- pass 2: GPU_MODEL.sublinearExponent 0.75 -> 1 (objective 0.2865)
- pass 2: CPU_MODEL.threadExponent 0.2 -> 0.25 (objective 0.2833)
- written: NO (proposed only)

## 2026-08-18T16:43:05.132Z
- fixtures: 143 (10 holdout)
- median APE: 19.4%, p90: 44.6%, spearman: 0.912, sign: n/a
- verdict: FAIL (advisory — recalled fixtures)
  - only 143 fixtures; 150 needed for the metrics to be statistically meaningful
  - median APE 19.4% exceeds 15.0%
  - p90 APE 44.6% exceeds 30.0%

## 2026-08-18T16:45:52.577Z
- fixtures: 143 (10 holdout)
- median APE: 19.4%, p90: 44.6%, spearman: 0.912, sign: n/a
- verdict: FAIL (advisory — recalled fixtures)
  - only 143 fixtures; 150 needed for the metrics to be statistically meaningful
  - median APE 19.4% exceeds 15.0%
  - p90 APE 44.6% exceeds 30.0%
