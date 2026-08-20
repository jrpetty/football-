
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

## 2026-08-18T17:10:17.689Z
- fixtures: 143 (10 holdout)
- median APE: 19.4%, p90: 44.6%, spearman: 0.912, sign: n/a
- verdict: FAIL (advisory — recalled fixtures)
  - only 143 fixtures; 150 needed for the metrics to be statistically meaningful
  - median APE 19.4% exceeds 15.0%
  - p90 APE 44.6% exceeds 30.0%

## calibration 2026-08-18T17:23:28.618Z
- fixtures: 161 (train 150 / holdout 11; ADVISORY — recalled)
- fit consulted the train split only; holdout after: medianAPE 16.1%, p90 54.2%, rho 0.888
- pass 1: GPU_MODEL.sublinearExponent 0.7 -> 0.74 (train objective 0.3212)
- pass 1: GPU_MODEL.computeWeight 0.7 -> 0.6 (train objective 0.3152)
- pass 1: GPU_ARCH_EFFICIENCY.Ampere 1 -> 1.075 (train objective 0.3086)
- pass 1: GPU_ARCH_EFFICIENCY.RDNA 2 1.1 -> 1.265 (train objective 0.3078)
- pass 1: GPU_ARCH_EFFICIENCY.RDNA 3 1.35 -> 1.249 (train objective 0.3074)
- pass 1: GPU_ARCH_EFFICIENCY.Blackwell 1.05 -> 0.892 (train objective 0.3063)
- pass 1: GPU_ARCH_EFFICIENCY.GCN 4.0 0.9 -> 0.833 (train objective 0.3062)
- pass 1: GPU_ARCH_EFFICIENCY.Pascal 1 -> 1.075 (train objective 0.3052)
- pass 1: GPU_ARCH_EFFICIENCY.Turing 1.5 -> 1.275 (train objective 0.3033)
- pass 1: CPU_MODEL.threadExponent 0.3 -> 0.25 (train objective 0.3010)
- pass 1: COMBINE.p 6.58 -> 4 (train objective 0.2981)
- pass 1: CPU_WEIGHTS.sim-cpu.cacheEndowment 0.42 -> 0.55 (train objective 0.2962)
- pass 1: VRAM_MODEL.cliff[2].avgMultiplier 0.62 -> 0.5 (train objective 0.2960)
- pass 2: GPU_MODEL.sublinearExponent 0.74 -> 0.78 (train objective 0.1867)
- pass 2: GPU_ARCH_EFFICIENCY.RDNA 3 1.249 -> 1.147 (train objective 0.1864)
- pass 2: GPU_ARCH_EFFICIENCY.Blackwell 0.892 -> 1.208 (train objective 0.1771)
- pass 2: GPU_ARCH_EFFICIENCY.GCN 4.0 0.833 -> 0.9 (train objective 0.1770)
- pass 2: GPU_ARCH_EFFICIENCY.Pascal 1.075 -> 1.15 (train objective 0.1757)
- pass 2: GPU_ARCH_EFFICIENCY.Turing 1.275 -> 1.612 (train objective 0.1734)
- pass 2: CPU_MODEL.threadExponent 0.25 -> 0.2 (train objective 0.1729)
- ref pass 1: cyberpunk-2077 gpuBound x0.94 cpuBound x1 (38 train fixtures)
- ref pass 1: counter-strike-2 gpuBound x1 cpuBound x1.14 (27 train fixtures)
- ref pass 1: forza-horizon-5 gpuBound x1.45 cpuBound x1 (13 train fixtures)
- ref pass 1: shadow-of-the-tomb-raider gpuBound x1 cpuBound x1.25 (8 train fixtures)
- ref pass 1: red-dead-redemption-2 gpuBound x1.25 cpuBound x1 (6 train fixtures)
- ref pass 1: fortnite gpuBound x0.72 cpuBound x0.94 (7 train fixtures)
- ref pass 1: alan-wake-2 gpuBound x0.72 cpuBound x1.45 (4 train fixtures)
- ref pass 1: black-myth-wukong gpuBound x0.88 cpuBound x1.45 (2 train fixtures)
- ref pass 1: total-war-warhammer-iii gpuBound x1.45 cpuBound x1.45 (8 train fixtures)
- ref pass 1: the-last-of-us-part-i gpuBound x0.94 cpuBound x1.06 (6 train fixtures)
- ref pass 1: hogwarts-legacy gpuBound x1 cpuBound x0.94 (5 train fixtures)
- ref pass 1: rocket-league gpuBound x0.65 cpuBound x1.45 (4 train fixtures)
- ref pass 1: league-of-legends gpuBound x0.72 cpuBound x1 (4 train fixtures)
- ref pass 1: microsoft-flight-simulator-2024 gpuBound x1.45 cpuBound x1.06 (3 train fixtures)
- ref pass 1: hellblade-ii gpuBound x0.94 cpuBound x0.8 (2 train fixtures)
- ref pass 1: valorant gpuBound x1.45 cpuBound x1.25 (7 train fixtures)
- ref pass 1: apex-legends gpuBound x1.14 cpuBound x0.94 (2 train fixtures)
- ref pass 2: cyberpunk-2077 gpuBound x0.94 cpuBound x1.14 (38 train fixtures)
- ref pass 2: counter-strike-2 gpuBound x0.88 cpuBound x0.94 (27 train fixtures)
- ref pass 2: forza-horizon-5 gpuBound x0.94 cpuBound x1.06 (13 train fixtures)
- ref pass 2: shadow-of-the-tomb-raider gpuBound x0.94 cpuBound x0.94 (8 train fixtures)
- ref pass 2: red-dead-redemption-2 gpuBound x0.88 cpuBound x1.14 (6 train fixtures)
- ref pass 2: fortnite gpuBound x1 cpuBound x0.94 (7 train fixtures)
- ref pass 2: alan-wake-2 gpuBound x1 cpuBound x0.65 (4 train fixtures)
- ref pass 2: black-myth-wukong gpuBound x0.88 cpuBound x0.65 (2 train fixtures)
- ref pass 2: the-last-of-us-part-i gpuBound x1.06 cpuBound x1 (6 train fixtures)
- ref pass 2: rocket-league gpuBound x0.88 cpuBound x1 (4 train fixtures)
- ref pass 2: league-of-legends gpuBound x1.06 cpuBound x1 (4 train fixtures)
- ref pass 2: microsoft-flight-simulator-2024 gpuBound x1.45 cpuBound x1 (3 train fixtures)
- ref pass 2: hellblade-ii gpuBound x0.94 cpuBound x1 (2 train fixtures)
- ref pass 2: valorant gpuBound x0.65 cpuBound x1.14 (7 train fixtures)
- ref pass 2: apex-legends gpuBound x1.06 cpuBound x0.94 (2 train fixtures)
- constants.json written: NO (proposed only)

## calibration 2026-08-18T17:25:37.257Z
- fixtures: 161 (train 127 / holdout 34; ADVISORY — recalled)
- fit consulted the train split only; holdout after: medianAPE 13.1%, p90 32.0%, rho 0.911
- pass 1: GPU_ARCH_EFFICIENCY.Ampere 1 -> 1.075 (train objective 0.3275)
- pass 1: GPU_ARCH_EFFICIENCY.RDNA 2 1.1 -> 1.183 (train objective 0.3274)
- pass 1: GPU_ARCH_EFFICIENCY.Ada Lovelace 1.02 -> 1.097 (train objective 0.3264)
- pass 1: GPU_ARCH_EFFICIENCY.RDNA 3 1.35 -> 1.249 (train objective 0.3227)
- pass 1: GPU_ARCH_EFFICIENCY.Turing 1.5 -> 1.388 (train objective 0.3221)
- pass 1: RT_MODEL.onVsOff 0.55 -> 0.6 (train objective 0.3195)
- pass 2: GPU_ARCH_EFFICIENCY.Ampere 1.075 -> 1 (train objective 0.2191)
- pass 2: GPU_ARCH_EFFICIENCY.Ada Lovelace 1.097 -> 1.02 (train objective 0.2185)
- pass 2: GPU_ARCH_EFFICIENCY.RDNA 3 1.249 -> 1.451 (train objective 0.2149)
- pass 2: GPU_ARCH_EFFICIENCY.Turing 1.388 -> 1.5 (train objective 0.2143)
- ref pass 1: cyberpunk-2077 gpuBound x0.944 cpuBound x0.944 (31 train fixtures, shrink 0.94)
- ref pass 1: counter-strike-2 gpuBound x1.055 cpuBound x1.000 (24 train fixtures, shrink 0.92)
- ref pass 1: forza-horizon-5 gpuBound x1.213 cpuBound x1.000 (13 train fixtures, shrink 0.87)
- ref pass 1: red-dead-redemption-2 gpuBound x1.103 cpuBound x0.909 (6 train fixtures, shrink 0.75)
- ref pass 1: fortnite gpuBound x0.791 cpuBound x1.000 (5 train fixtures, shrink 0.71)
- ref pass 1: alan-wake-2 gpuBound x0.862 cpuBound x0.750 (4 train fixtures, shrink 0.67)
- ref pass 1: black-myth-wukong gpuBound x0.970 cpuBound x1.000 (2 train fixtures, shrink 0.50)
- ref pass 1: the-last-of-us-part-i gpuBound x0.913 cpuBound x0.791 (5 train fixtures, shrink 0.71)
- ref pass 1: hogwarts-legacy gpuBound x0.957 cpuBound x0.913 (5 train fixtures, shrink 0.71)
- ref pass 1: rocket-league gpuBound x0.750 cpuBound x1.000 (4 train fixtures, shrink 0.67)
- ref pass 1: league-of-legends gpuBound x0.750 cpuBound x1.000 (4 train fixtures, shrink 0.67)
- ref pass 1: total-war-warhammer-iii gpuBound x1.321 cpuBound x1.321 (6 train fixtures, shrink 0.75)
- ref pass 1: microsoft-flight-simulator-2024 gpuBound x1.250 cpuBound x1.036 (3 train fixtures, shrink 0.60)
- ref pass 1: hellblade-ii gpuBound x0.970 cpuBound x0.806 (2 train fixtures, shrink 0.50)
- ref pass 1: valorant gpuBound x1.281 cpuBound x1.160 (4 train fixtures, shrink 0.67)
- ref pass 2: cyberpunk-2077 gpuBound x1.000 cpuBound x1.056 (31 train fixtures, shrink 0.94)
- ref pass 2: counter-strike-2 gpuBound x0.944 cpuBound x1.000 (24 train fixtures, shrink 0.92)
- ref pass 2: forza-horizon-5 gpuBound x1.052 cpuBound x1.052 (13 train fixtures, shrink 0.87)
- ref pass 2: shadow-of-the-tomb-raider gpuBound x0.955 cpuBound x1.045 (6 train fixtures, shrink 0.75)
- ref pass 2: red-dead-redemption-2 gpuBound x1.000 cpuBound x0.955 (6 train fixtures, shrink 0.75)
- ref pass 2: fortnite gpuBound x0.913 cpuBound x1.000 (5 train fixtures, shrink 0.71)
- ref pass 2: alan-wake-2 gpuBound x0.960 cpuBound x0.918 (4 train fixtures, shrink 0.67)
- ref pass 2: black-myth-wukong gpuBound x0.970 cpuBound x1.000 (2 train fixtures, shrink 0.50)
- ref pass 2: the-last-of-us-part-i gpuBound x1.000 cpuBound x0.957 (5 train fixtures, shrink 0.71)
- ref pass 2: rocket-league gpuBound x0.918 cpuBound x1.160 (4 train fixtures, shrink 0.67)
- ref pass 2: league-of-legends gpuBound x0.918 cpuBound x1.000 (4 train fixtures, shrink 0.67)
- ref pass 2: total-war-warhammer-iii gpuBound x1.321 cpuBound x1.045 (6 train fixtures, shrink 0.75)
- ref pass 2: microsoft-flight-simulator-2024 gpuBound x1.250 cpuBound x1.036 (3 train fixtures, shrink 0.60)
- ref pass 2: hellblade-ii gpuBound x0.970 cpuBound x0.894 (2 train fixtures, shrink 0.50)
- ref pass 2: valorant gpuBound x0.750 cpuBound x1.091 (4 train fixtures, shrink 0.67)
- constants.json written: NO (proposed only)

## calibration 2026-08-18T17:26:11.901Z
- fixtures: 161 (train 127 / holdout 34; ADVISORY — recalled)
- fit consulted the train split only; holdout after: medianAPE 13.1%, p90 32.0%, rho 0.911
- pass 1: GPU_ARCH_EFFICIENCY.Ampere 1 -> 1.075 (train objective 0.3211)
- pass 1: GPU_ARCH_EFFICIENCY.Ada Lovelace 1.02 -> 1.097 (train objective 0.3199)
- pass 1: GPU_ARCH_EFFICIENCY.RDNA 3 1.45 -> 1.341 (train objective 0.3193)
- pass 2: GPU_ARCH_EFFICIENCY.Ampere 1.075 -> 1 (train objective 0.2161)
- pass 2: GPU_ARCH_EFFICIENCY.Ada Lovelace 1.097 -> 1.02 (train objective 0.2157)
- pass 2: GPU_ARCH_EFFICIENCY.RDNA 3 1.341 -> 1.45 (train objective 0.2118)
- ref pass 1: cyberpunk-2077 gpuBound x0.944 cpuBound x0.944 (31 train fixtures, shrink 0.94)
- ref pass 1: forza-horizon-5 gpuBound x1.213 cpuBound x1.000 (13 train fixtures, shrink 0.87)
- ref pass 1: red-dead-redemption-2 gpuBound x1.103 cpuBound x0.909 (6 train fixtures, shrink 0.75)
- ref pass 1: fortnite gpuBound x0.791 cpuBound x1.000 (5 train fixtures, shrink 0.71)
- ref pass 1: alan-wake-2 gpuBound x0.862 cpuBound x0.750 (4 train fixtures, shrink 0.67)
- ref pass 1: black-myth-wukong gpuBound x0.970 cpuBound x1.000 (2 train fixtures, shrink 0.50)
- ref pass 1: the-last-of-us-part-i gpuBound x0.913 cpuBound x0.791 (5 train fixtures, shrink 0.71)
- ref pass 1: hogwarts-legacy gpuBound x0.957 cpuBound x0.913 (5 train fixtures, shrink 0.71)
- ref pass 1: rocket-league gpuBound x0.750 cpuBound x1.000 (4 train fixtures, shrink 0.67)
- ref pass 1: league-of-legends gpuBound x0.750 cpuBound x1.000 (4 train fixtures, shrink 0.67)
- ref pass 1: total-war-warhammer-iii gpuBound x1.321 cpuBound x1.321 (6 train fixtures, shrink 0.75)
- ref pass 1: microsoft-flight-simulator-2024 gpuBound x1.250 cpuBound x1.036 (3 train fixtures, shrink 0.60)
- ref pass 1: hellblade-ii gpuBound x0.970 cpuBound x0.806 (2 train fixtures, shrink 0.50)
- ref pass 1: valorant gpuBound x1.281 cpuBound x1.160 (4 train fixtures, shrink 0.67)
- ref pass 2: cyberpunk-2077 gpuBound x1.000 cpuBound x1.056 (31 train fixtures, shrink 0.94)
- ref pass 2: forza-horizon-5 gpuBound x1.052 cpuBound x1.052 (13 train fixtures, shrink 0.87)
- ref pass 2: shadow-of-the-tomb-raider gpuBound x0.955 cpuBound x1.045 (6 train fixtures, shrink 0.75)
- ref pass 2: red-dead-redemption-2 gpuBound x1.000 cpuBound x0.955 (6 train fixtures, shrink 0.75)
- ref pass 2: fortnite gpuBound x0.913 cpuBound x1.000 (5 train fixtures, shrink 0.71)
- ref pass 2: alan-wake-2 gpuBound x0.960 cpuBound x0.918 (4 train fixtures, shrink 0.67)
- ref pass 2: black-myth-wukong gpuBound x0.970 cpuBound x1.000 (2 train fixtures, shrink 0.50)
- ref pass 2: the-last-of-us-part-i gpuBound x1.000 cpuBound x0.957 (5 train fixtures, shrink 0.71)
- ref pass 2: rocket-league gpuBound x0.918 cpuBound x1.160 (4 train fixtures, shrink 0.67)
- ref pass 2: league-of-legends gpuBound x0.918 cpuBound x1.000 (4 train fixtures, shrink 0.67)
- ref pass 2: total-war-warhammer-iii gpuBound x1.321 cpuBound x1.045 (6 train fixtures, shrink 0.75)
- ref pass 2: microsoft-flight-simulator-2024 gpuBound x1.250 cpuBound x1.036 (3 train fixtures, shrink 0.60)
- ref pass 2: hellblade-ii gpuBound x0.970 cpuBound x0.894 (2 train fixtures, shrink 0.50)
- ref pass 2: valorant gpuBound x0.750 cpuBound x1.091 (4 train fixtures, shrink 0.67)
- constants.json written: NO (proposed only)

## 2026-08-18T17:26:33.775Z
- fixtures: 161 (34 holdout)
- median APE: 13.1%, p90: 32.0%, spearman: 0.911, sign: n/a
- verdict: FAIL (advisory — recalled fixtures)
  - p90 APE 32.0% exceeds 30.0%

## calibration 2026-08-18T17:28:55.546Z
- fixtures: 161 (train 127 / holdout 34; ADVISORY — recalled)
- fit consulted the train split only; holdout after: medianAPE 14.4%, p90 35.1%, rho 0.916
- pass 1: GPU_ARCH_EFFICIENCY.Ampere 1 -> 1.075 (train objective 0.2936)
- pass 1: GPU_ARCH_EFFICIENCY.Ada Lovelace 1.02 -> 1.097 (train objective 0.2920)
- pass 1: GPU_ARCH_EFFICIENCY.Turing 1.5 -> 1.388 (train objective 0.2837)
- pass 2: GPU_ARCH_EFFICIENCY.Ampere 1.075 -> 1 (train objective 0.2037)
- pass 2: GPU_ARCH_EFFICIENCY.Pascal 1 -> 1.075 (train objective 0.1980)
- ref pass 1: cyberpunk-2077 gpuBound x0.944 cpuBound x1.000 (31 train fixtures, shrink 0.94)
- ref pass 1: counter-strike-2 gpuBound x0.814 cpuBound x1.229 (24 train fixtures, shrink 0.92)
- ref pass 1: forza-horizon-5 gpuBound x1.213 cpuBound x1.000 (13 train fixtures, shrink 0.87)
- ref pass 1: red-dead-redemption-2 gpuBound x1.103 cpuBound x0.909 (6 train fixtures, shrink 0.75)
- ref pass 1: fortnite gpuBound x0.791 cpuBound x0.957 (5 train fixtures, shrink 0.71)
- ref pass 1: alan-wake-2 gpuBound x0.862 cpuBound x0.750 (4 train fixtures, shrink 0.67)
- ref pass 1: black-myth-wukong gpuBound x0.970 cpuBound x1.000 (2 train fixtures, shrink 0.50)
- ref pass 1: the-last-of-us-part-i gpuBound x0.913 cpuBound x0.791 (5 train fixtures, shrink 0.71)
- ref pass 1: hogwarts-legacy gpuBound x0.957 cpuBound x0.913 (5 train fixtures, shrink 0.71)
- ref pass 1: rocket-league gpuBound x0.750 cpuBound x1.000 (4 train fixtures, shrink 0.67)
- ref pass 1: league-of-legends gpuBound x0.862 cpuBound x1.000 (4 train fixtures, shrink 0.67)
- ref pass 1: total-war-warhammer-iii gpuBound x1.321 cpuBound x1.321 (6 train fixtures, shrink 0.75)
- ref pass 1: microsoft-flight-simulator-2024 gpuBound x1.250 cpuBound x1.036 (3 train fixtures, shrink 0.60)
- ref pass 1: hellblade-ii gpuBound x0.970 cpuBound x0.806 (2 train fixtures, shrink 0.50)
- ref pass 1: valorant gpuBound x1.281 cpuBound x1.160 (4 train fixtures, shrink 0.67)
- ref pass 2: cyberpunk-2077 gpuBound x1.000 cpuBound x0.944 (31 train fixtures, shrink 0.94)
- ref pass 2: counter-strike-2 gpuBound x0.944 cpuBound x1.000 (24 train fixtures, shrink 0.92)
- ref pass 2: forza-horizon-5 gpuBound x1.052 cpuBound x1.120 (13 train fixtures, shrink 0.87)
- ref pass 2: shadow-of-the-tomb-raider gpuBound x0.955 cpuBound x1.045 (6 train fixtures, shrink 0.75)
- ref pass 2: red-dead-redemption-2 gpuBound x1.000 cpuBound x1.182 (6 train fixtures, shrink 0.75)
- ref pass 2: fortnite gpuBound x0.913 cpuBound x1.000 (5 train fixtures, shrink 0.71)
- ref pass 2: alan-wake-2 gpuBound x0.960 cpuBound x0.862 (4 train fixtures, shrink 0.67)
- ref pass 2: black-myth-wukong gpuBound x0.938 cpuBound x1.204 (2 train fixtures, shrink 0.50)
- ref pass 2: the-last-of-us-part-i gpuBound x1.098 cpuBound x1.000 (5 train fixtures, shrink 0.71)
- ref pass 2: hogwarts-legacy gpuBound x1.000 cpuBound x0.957 (5 train fixtures, shrink 0.71)
- ref pass 2: rocket-league gpuBound x0.918 cpuBound x1.160 (4 train fixtures, shrink 0.67)
- ref pass 2: league-of-legends gpuBound x0.918 cpuBound x1.000 (4 train fixtures, shrink 0.67)
- ref pass 2: total-war-warhammer-iii gpuBound x1.321 cpuBound x1.045 (6 train fixtures, shrink 0.75)
- ref pass 2: microsoft-flight-simulator-2024 gpuBound x1.250 cpuBound x1.036 (3 train fixtures, shrink 0.60)
- ref pass 2: hellblade-ii gpuBound x0.938 cpuBound x0.894 (2 train fixtures, shrink 0.50)
- ref pass 2: valorant gpuBound x0.750 cpuBound x1.091 (4 train fixtures, shrink 0.67)
- constants.json written: NO (proposed only)

## 2026-08-18T17:28:56.345Z
- fixtures: 161 (34 holdout)
- median APE: 12.9%, p90: 35.1%, spearman: 0.920, sign: n/a
- verdict: FAIL (advisory — recalled fixtures)
  - p90 APE 35.1% exceeds 30.0%

## calibration 2026-08-18T17:30:22.467Z
- fixtures: 161 (train 127 / holdout 34; ADVISORY — recalled)
- fit consulted the train split only; holdout after: medianAPE 13.9%, p90 35.1%, rho 0.919
- pass 1: GPU_ARCH_EFFICIENCY.Ampere 1 -> 1.075 (train objective 0.2936)
- pass 1: GPU_ARCH_EFFICIENCY.Ada Lovelace 1.02 -> 1.097 (train objective 0.2920)
- pass 1: GPU_ARCH_EFFICIENCY.Turing 1.5 -> 1.388 (train objective 0.2837)
- pass 2: GPU_ARCH_EFFICIENCY.Ampere 1.075 -> 1 (train objective 0.1926)
- pass 2: GPU_ARCH_EFFICIENCY.Blackwell 1.05 -> 1.129 (train objective 0.1920)
- pass 2: GPU_ARCH_EFFICIENCY.Pascal 1 -> 1.075 (train objective 0.1912)
- ref pass 1: cyberpunk-2077 gpuBound x0.944 cpuBound x1.000 exp 1.04 -> 1.04 (31 train fixtures, shrink 0.94)
- ref pass 1: counter-strike-2 gpuBound x0.814 cpuBound x1.229 exp 0.95 -> 0.996 (24 train fixtures, shrink 0.92)
- ref pass 1: forza-horizon-5 gpuBound x1.213 cpuBound x1.000 exp 0.98 -> 1.041 (13 train fixtures, shrink 0.87)
- ref pass 1: shadow-of-the-tomb-raider gpuBound x1.000 cpuBound x1.000 exp 1 -> 1.038 (6 train fixtures, shrink 0.75)
- ref pass 1: red-dead-redemption-2 gpuBound x1.103 cpuBound x0.909 exp 1 -> 1 (6 train fixtures, shrink 0.75)
- ref pass 1: fortnite gpuBound x0.791 cpuBound x0.957 exp 0.98 -> 0.994 (5 train fixtures, shrink 0.71)
- ref pass 1: alan-wake-2 gpuBound x0.862 cpuBound x0.750 exp 1.05 -> 1.017 (4 train fixtures, shrink 0.67)
- ref pass 1: black-myth-wukong gpuBound x0.970 cpuBound x1.000 exp 1.05 -> 1.025 (2 train fixtures, shrink 0.50)
- ref pass 1: the-last-of-us-part-i gpuBound x0.913 cpuBound x0.791 exp 1 -> 1 (5 train fixtures, shrink 0.71)
- ref pass 1: hogwarts-legacy gpuBound x0.957 cpuBound x0.913 exp 1 -> 0.95 (5 train fixtures, shrink 0.71)
- ref pass 1: rocket-league gpuBound x0.750 cpuBound x1.000 exp 0.93 -> 1.01 (4 train fixtures, shrink 0.67)
- ref pass 1: league-of-legends gpuBound x0.862 cpuBound x1.000 exp 0.9 -> 0.9 (4 train fixtures, shrink 0.67)
- ref pass 1: total-war-warhammer-iii gpuBound x1.321 cpuBound x1.321 exp 0.97 -> 1.03 (6 train fixtures, shrink 0.75)
- ref pass 1: microsoft-flight-simulator-2024 gpuBound x1.250 cpuBound x1.036 exp 0.92 -> 0.92 (3 train fixtures, shrink 0.60)
- ref pass 1: hellblade-ii gpuBound x0.970 cpuBound x0.806 exp 1.07 -> 1.035 (2 train fixtures, shrink 0.50)
- ref pass 1: valorant gpuBound x1.281 cpuBound x1.160 exp 0.93 -> 0.93 (4 train fixtures, shrink 0.67)
- ref pass 2: cyberpunk-2077 gpuBound x1.000 cpuBound x0.944 exp 1.04 -> 1.04 (31 train fixtures, shrink 0.94)
- ref pass 2: counter-strike-2 gpuBound x0.944 cpuBound x1.000 exp 0.996 -> 0.996 (24 train fixtures, shrink 0.92)
- ref pass 2: forza-horizon-5 gpuBound x1.000 cpuBound x1.213 exp 1.041 -> 1.049 (13 train fixtures, shrink 0.87)
- ref pass 2: shadow-of-the-tomb-raider gpuBound x0.955 cpuBound x1.045 exp 1.038 -> 1.038 (6 train fixtures, shrink 0.75)
- ref pass 2: red-dead-redemption-2 gpuBound x1.000 cpuBound x1.182 exp 1 -> 1 (6 train fixtures, shrink 0.75)
- ref pass 2: fortnite gpuBound x0.913 cpuBound x1.000 exp 0.994 -> 0.948 (5 train fixtures, shrink 0.71)
- ref pass 2: alan-wake-2 gpuBound x0.960 cpuBound x1.000 exp 1.017 -> 1.017 (4 train fixtures, shrink 0.67)
- ref pass 2: black-myth-wukong gpuBound x0.970 cpuBound x1.000 exp 1.025 -> 1.025 (2 train fixtures, shrink 0.50)
- ref pass 2: the-last-of-us-part-i gpuBound x1.098 cpuBound x1.000 exp 1 -> 1.036 (5 train fixtures, shrink 0.71)
- ref pass 2: hogwarts-legacy gpuBound x1.000 cpuBound x0.957 exp 0.95 -> 0.986 (5 train fixtures, shrink 0.71)
- ref pass 2: rocket-league gpuBound x0.862 cpuBound x1.160 exp 1.01 -> 1.037 (4 train fixtures, shrink 0.67)
- ref pass 2: league-of-legends gpuBound x0.918 cpuBound x1.000 exp 0.9 -> 0.9 (4 train fixtures, shrink 0.67)
- ref pass 2: total-war-warhammer-iii gpuBound x1.321 cpuBound x1.045 exp 1.03 -> 1.045 (6 train fixtures, shrink 0.75)
- ref pass 2: microsoft-flight-simulator-2024 gpuBound x1.250 cpuBound x1.036 exp 0.92 -> 0.998 (3 train fixtures, shrink 0.60)
- ref pass 2: hellblade-ii gpuBound x0.970 cpuBound x0.894 exp 1.035 -> 1.035 (2 train fixtures, shrink 0.50)
- ref pass 2: valorant gpuBound x0.750 cpuBound x1.091 exp 0.93 -> 0.93 (4 train fixtures, shrink 0.67)
- constants.json written: NO (proposed only)

## 2026-08-18T17:30:23.191Z
- fixtures: 161 (34 holdout)
- median APE: 12.5%, p90: 35.1%, spearman: 0.927, sign: n/a
- verdict: FAIL (advisory — recalled fixtures)
  - p90 APE 35.1% exceeds 30.0%

## calibration 2026-08-18T17:32:18.632Z
- fixtures: 161 (train 127 / holdout 34; ADVISORY — recalled)
- fit consulted the train split only; holdout after: medianAPE 14.0%, p90 38.9%, rho 0.920
- pass 1: GPU_ARCH_EFFICIENCY.Ampere 1 -> 1.075 (train objective 0.3177)
- pass 1: GPU_ARCH_EFFICIENCY.Blackwell 1.05 -> 1.129 (train objective 0.3170)
- pass 1: GPU_ARCH_EFFICIENCY.Turing 1.5 -> 1.388 (train objective 0.3083)
- pass 2: GPU_ARCH_EFFICIENCY.Ampere 1.075 -> 1 (train objective 0.1878)
- pass 2: GPU_ARCH_EFFICIENCY.Blackwell 1.129 -> 1.05 (train objective 0.1875)
- ref pass 1: cyberpunk-2077 gpuBound x1.000 cpuBound x0.811 exp 1.04 -> 1.04 (31 train fixtures, shrink 0.94)
- ref pass 1: counter-strike-2 gpuBound x0.889 cpuBound x1.000 exp 0.65 -> 1.019 (24 train fixtures, shrink 0.92)
- ref pass 1: forza-horizon-5 gpuBound x1.213 cpuBound x1.213 exp 0.98 -> 1.041 (13 train fixtures, shrink 0.87)
- ref pass 1: shadow-of-the-tomb-raider gpuBound x1.000 cpuBound x1.045 exp 1 -> 1.038 (6 train fixtures, shrink 0.75)
- ref pass 1: red-dead-redemption-2 gpuBound x1.103 cpuBound x0.909 exp 1 -> 1.038 (6 train fixtures, shrink 0.75)
- ref pass 1: fortnite gpuBound x0.791 cpuBound x1.000 exp 0.85 -> 0.85 (5 train fixtures, shrink 0.71)
- ref pass 1: alan-wake-2 gpuBound x0.918 cpuBound x0.750 exp 1.05 -> 1.017 (4 train fixtures, shrink 0.67)
- ref pass 1: black-myth-wukong gpuBound x0.970 cpuBound x1.000 exp 1.05 -> 1.025 (2 train fixtures, shrink 0.50)
- ref pass 1: the-last-of-us-part-i gpuBound x0.913 cpuBound x0.791 exp 1 -> 1 (5 train fixtures, shrink 0.71)
- ref pass 1: hogwarts-legacy gpuBound x0.957 cpuBound x0.913 exp 1 -> 0.95 (5 train fixtures, shrink 0.71)
- ref pass 1: rocket-league gpuBound x0.750 cpuBound x1.000 exp 0.65 -> 0.917 (4 train fixtures, shrink 0.67)
- ref pass 1: league-of-legends gpuBound x0.750 cpuBound x1.000 exp 0.65 -> 0.783 (4 train fixtures, shrink 0.67)
- ref pass 1: total-war-warhammer-iii gpuBound x1.321 cpuBound x1.321 exp 0.97 -> 1.03 (6 train fixtures, shrink 0.75)
- ref pass 1: microsoft-flight-simulator-2024 gpuBound x1.250 cpuBound x1.036 exp 0.92 -> 0.92 (3 train fixtures, shrink 0.60)
- ref pass 1: hellblade-ii gpuBound x0.970 cpuBound x0.806 exp 1.07 -> 1.07 (2 train fixtures, shrink 0.50)
- ref pass 1: valorant gpuBound x1.281 cpuBound x1.160 exp 0.65 -> 0.65 (4 train fixtures, shrink 0.67)
- ref pass 2: cyberpunk-2077 gpuBound x1.000 cpuBound x1.000 exp 1.04 -> 1.002 (31 train fixtures, shrink 0.94)
- ref pass 2: counter-strike-2 gpuBound x0.944 cpuBound x1.055 exp 1.019 -> 1.048 (24 train fixtures, shrink 0.92)
- ref pass 2: forza-horizon-5 gpuBound x1.000 cpuBound x1.000 exp 1.041 -> 1.049 (13 train fixtures, shrink 0.87)
- ref pass 2: shadow-of-the-tomb-raider gpuBound x1.000 cpuBound x1.000 exp 1.038 -> 1.047 (6 train fixtures, shrink 0.75)
- ref pass 2: red-dead-redemption-2 gpuBound x1.000 cpuBound x1.321 exp 1.038 -> 1.01 (6 train fixtures, shrink 0.75)
- ref pass 2: fortnite gpuBound x0.913 cpuBound x1.000 exp 0.85 -> 0.907 (5 train fixtures, shrink 0.71)
- ref pass 2: alan-wake-2 gpuBound x0.960 cpuBound x0.918 exp 1.017 -> 1.017 (4 train fixtures, shrink 0.67)
- ref pass 2: black-myth-wukong gpuBound x0.970 cpuBound x1.030 exp 1.025 -> 1.025 (2 train fixtures, shrink 0.50)
- ref pass 2: the-last-of-us-part-i gpuBound x1.098 cpuBound x1.000 exp 1 -> 1.036 (5 train fixtures, shrink 0.71)
- ref pass 2: hogwarts-legacy gpuBound x1.000 cpuBound x0.957 exp 0.95 -> 0.986 (5 train fixtures, shrink 0.71)
- ref pass 2: rocket-league gpuBound x0.918 cpuBound x1.000 exp 0.917 -> 1.006 (4 train fixtures, shrink 0.67)
- ref pass 2: league-of-legends gpuBound x0.862 cpuBound x1.040 exp 0.783 -> 0.783 (4 train fixtures, shrink 0.67)
- ref pass 2: total-war-warhammer-iii gpuBound x1.321 cpuBound x1.000 exp 1.03 -> 1.03 (6 train fixtures, shrink 0.75)
- ref pass 2: microsoft-flight-simulator-2024 gpuBound x1.250 cpuBound x1.036 exp 0.92 -> 0.998 (3 train fixtures, shrink 0.60)
- ref pass 2: hellblade-ii gpuBound x0.970 cpuBound x0.894 exp 1.07 -> 1.07 (2 train fixtures, shrink 0.50)
- ref pass 2: valorant gpuBound x0.750 cpuBound x1.091 exp 0.65 -> 0.65 (4 train fixtures, shrink 0.67)
- constants.json written: NO (proposed only)

## 2026-08-18T17:32:19.367Z
- fixtures: 161 (34 holdout)
- median APE: 14.0%, p90: 38.9%, spearman: 0.920, sign: n/a
- verdict: FAIL (advisory — recalled fixtures)
  - p90 APE 38.9% exceeds 30.0%

## calibration 2026-08-18T17:35:42.674Z
- fixtures: 161 (train 127 / holdout 34; ADVISORY — recalled)
- fit consulted the train split only; holdout after: medianAPE 14.1%, p90 42.4%, rho 0.919
- pass 1: GPU_ARCH_EFFICIENCY.Ampere 1 -> 1.075 (train objective 0.2779)
- pass 1: GPU_ARCH_EFFICIENCY.Pascal 1 -> 1.075 (train objective 0.2733)
- pass 2: GPU_ARCH_EFFICIENCY.RDNA 2 1.18 -> 1.268 (train objective 0.1785)
- pass 2: GPU_ARCH_EFFICIENCY.Pascal 1.075 -> 1 (train objective 0.1781)
- ref pass 1: cyberpunk-2077 gpuBound x1.000 cpuBound x0.811 exp 1.04 -> 1.04 (31 train fixtures, shrink 0.94)
- ref pass 1: counter-strike-2 gpuBound x0.814 cpuBound x1.229 exp 0.95 -> 0.996 (24 train fixtures, shrink 0.92)
- ref pass 1: forza-horizon-5 gpuBound x1.213 cpuBound x1.213 exp 0.98 -> 1.041 (13 train fixtures, shrink 0.87)
- ref pass 1: shadow-of-the-tomb-raider gpuBound x1.000 cpuBound x0.955 exp 1 -> 1.038 (6 train fixtures, shrink 0.75)
- ref pass 1: red-dead-redemption-2 gpuBound x1.103 cpuBound x1.000 exp 1 -> 1 (6 train fixtures, shrink 0.75)
- ref pass 1: fortnite gpuBound x0.791 cpuBound x0.957 exp 0.98 -> 0.994 (5 train fixtures, shrink 0.71)
- ref pass 1: alan-wake-2 gpuBound x0.918 cpuBound x0.750 exp 1.05 -> 1.017 (4 train fixtures, shrink 0.67)
- ref pass 1: black-myth-wukong gpuBound x0.970 cpuBound x1.000 exp 1.05 -> 1.025 (2 train fixtures, shrink 0.50)
- ref pass 1: the-last-of-us-part-i gpuBound x0.913 cpuBound x0.791 exp 1 -> 1 (5 train fixtures, shrink 0.71)
- ref pass 1: hogwarts-legacy gpuBound x0.957 cpuBound x0.913 exp 1 -> 0.95 (5 train fixtures, shrink 0.71)
- ref pass 1: rocket-league gpuBound x0.803 cpuBound x1.000 exp 0.93 -> 0.93 (4 train fixtures, shrink 0.67)
- ref pass 1: league-of-legends gpuBound x0.862 cpuBound x0.960 exp 0.9 -> 0.9 (4 train fixtures, shrink 0.67)
- ref pass 1: total-war-warhammer-iii gpuBound x1.321 cpuBound x1.321 exp 0.97 -> 1.03 (6 train fixtures, shrink 0.75)
- ref pass 1: microsoft-flight-simulator-2024 gpuBound x1.250 cpuBound x1.036 exp 0.92 -> 0.92 (3 train fixtures, shrink 0.60)
- ref pass 1: hellblade-ii gpuBound x0.970 cpuBound x0.806 exp 1.07 -> 1.07 (2 train fixtures, shrink 0.50)
- ref pass 1: valorant gpuBound x1.281 cpuBound x1.160 exp 0.93 -> 0.93 (4 train fixtures, shrink 0.67)
- ref pass 2: forza-horizon-5 gpuBound x1.052 cpuBound x0.895 exp 1.041 -> 1.041 (13 train fixtures, shrink 0.87)
- ref pass 2: shadow-of-the-tomb-raider gpuBound x1.000 cpuBound x1.045 exp 1.038 -> 1.047 (6 train fixtures, shrink 0.75)
- ref pass 2: fortnite gpuBound x1.000 cpuBound x1.000 exp 0.994 -> 0.998 (5 train fixtures, shrink 0.71)
- ref pass 2: alan-wake-2 gpuBound x1.000 cpuBound x0.918 exp 1.017 -> 1.017 (4 train fixtures, shrink 0.67)
- ref pass 2: black-myth-wukong gpuBound x1.000 cpuBound x1.204 exp 1.025 -> 1.012 (2 train fixtures, shrink 0.50)
- ref pass 2: the-last-of-us-part-i gpuBound x1.000 cpuBound x0.913 exp 1 -> 1 (5 train fixtures, shrink 0.71)
- ref pass 2: hogwarts-legacy gpuBound x1.000 cpuBound x0.957 exp 0.95 -> 0.986 (5 train fixtures, shrink 0.71)
- ref pass 2: rocket-league gpuBound x0.918 cpuBound x1.000 exp 0.93 -> 0.93 (4 train fixtures, shrink 0.67)
- ref pass 2: league-of-legends gpuBound x0.918 cpuBound x1.040 exp 0.9 -> 0.9 (4 train fixtures, shrink 0.67)
- ref pass 2: total-war-warhammer-iii gpuBound x1.321 cpuBound x1.000 exp 1.03 -> 1.03 (6 train fixtures, shrink 0.75)
- ref pass 2: microsoft-flight-simulator-2024 gpuBound x1.250 cpuBound x1.036 exp 0.92 -> 0.998 (3 train fixtures, shrink 0.60)
- ref pass 2: hellblade-ii gpuBound x1.000 cpuBound x0.894 exp 1.07 -> 1.07 (2 train fixtures, shrink 0.50)
- ref pass 2: valorant gpuBound x0.750 cpuBound x1.091 exp 0.93 -> 0.93 (4 train fixtures, shrink 0.67)
- constants.json written: NO (proposed only)

## 2026-08-18T17:35:43.365Z
- fixtures: 161 (34 holdout)
- median APE: 17.1%, p90: 39.5%, spearman: 0.905, sign: n/a
- verdict: FAIL (advisory — recalled fixtures)
  - median APE 17.1% exceeds 15.0%
  - p90 APE 39.5% exceeds 30.0%
  - train/holdout median APE gap 10.6% exceeds 8.0% — the model is fitting the fixture set, not reality

## calibration 2026-08-18T17:36:56.470Z
- fixtures: 161 (train 127 / holdout 34; ADVISORY — recalled)
- fit consulted the train split only; holdout after: medianAPE 20.1%, p90 37.3%, rho 0.920
- pass 1: GPU_ARCH_EFFICIENCY.Ampere 1 -> 1.075 (train objective 0.2779)
- pass 1: GPU_ARCH_EFFICIENCY.Pascal 1 -> 1.075 (train objective 0.2733)
- ref final: cyberpunk-2077 gpuBound x1.000 cpuBound x0.839 (31 train fixtures, shrink 0.89)
- ref final: counter-strike-2 gpuBound x1.000 cpuBound x1.169 (24 train fixtures, shrink 0.86)
- ref final: forza-horizon-5 gpuBound x1.222 cpuBound x1.150 (13 train fixtures, shrink 0.76)
- ref final: red-dead-redemption-2 gpuBound x1.065 cpuBound x1.000 (6 train fixtures, shrink 0.60)
- ref final: fortnite gpuBound x0.852 cpuBound x1.000 (5 train fixtures, shrink 0.56)
- ref final: alan-wake-2 gpuBound x0.906 cpuBound x1.000 (4 train fixtures, shrink 0.50)
- ref final: black-myth-wukong gpuBound x0.983 cpuBound x1.000 (2 train fixtures, shrink 0.33)
- ref final: the-last-of-us-part-i gpuBound x0.943 cpuBound x0.852 (5 train fixtures, shrink 0.56)
- ref final: hogwarts-legacy gpuBound x0.972 cpuBound x0.896 (5 train fixtures, shrink 0.56)
- ref final: rocket-league gpuBound x0.866 cpuBound x1.000 (4 train fixtures, shrink 0.50)
- ref final: league-of-legends gpuBound x0.906 cpuBound x1.000 (4 train fixtures, shrink 0.50)
- ref final: total-war-warhammer-iii gpuBound x1.170 cpuBound x1.170 (6 train fixtures, shrink 0.60)
- ref final: microsoft-flight-simulator-2024 gpuBound x1.119 cpuBound x1.046 (3 train fixtures, shrink 0.43)
- ref final: hellblade-ii gpuBound x0.983 cpuBound x1.000 (2 train fixtures, shrink 0.33)
- ref final: valorant gpuBound x1.140 cpuBound x1.140 (4 train fixtures, shrink 0.50)
- constants.json written: NO (proposed only)

## 2026-08-18T17:36:57.185Z
- fixtures: 161 (34 holdout)
- median APE: 18.2%, p90: 37.3%, spearman: 0.908, sign: n/a
- verdict: FAIL (advisory — recalled fixtures)
  - median APE 18.2% exceeds 15.0%
  - train/holdout median APE gap 8.1% exceeds 8.0% — the model is fitting the fixture set, not reality

## calibration 2026-08-18T17:40:17.047Z
- fixtures: 161 (161 recalled)
- in-sample medianAPE 14.8% -> 10.3%
- ref cyberpunk-2077 gpuBound x0.954 cpuBound x1.182 (43 fixtures)
- ref counter-strike-2 gpuBound x0.912 cpuBound x1.174 (29 fixtures)
- ref forza-horizon-5 gpuBound x1.234 cpuBound x1 (16 fixtures)
- ref shadow-of-the-tomb-raider gpuBound x0.966 cpuBound x1 (8 fixtures)
- ref red-dead-redemption-2 gpuBound x1.065 cpuBound x1 (6 fixtures)
- ref fortnite gpuBound x0.833 cpuBound x1 (7 fixtures)
- ref alan-wake-2 gpuBound x0.906 cpuBound x1 (4 fixtures)
- ref black-myth-wukong gpuBound x0.965 cpuBound x1 (2 fixtures)
- ref total-war-warhammer-iii gpuBound x1.191 cpuBound x1.191 (8 fixtures)
- ref the-last-of-us-part-i gpuBound x0.888 cpuBound x0.841 (6 fixtures)
- ref hogwarts-legacy gpuBound x1.03 cpuBound x1 (6 fixtures)
- ref rocket-league gpuBound x0.866 cpuBound x1 (4 fixtures)
- ref league-of-legends gpuBound x0.866 cpuBound x1 (4 fixtures)
- ref microsoft-flight-simulator-2024 gpuBound x1.119 cpuBound x1.046 (3 fixtures)
- ref hellblade-ii gpuBound x0.965 cpuBound x1 (2 fixtures)
- ref valorant gpuBound x1.182 cpuBound x1.182 (7 fixtures)
- ref apex-legends gpuBound x1.063 cpuBound x0.936 (2 fixtures)
- global constants: frozen priors (see src/core/constants.ts); constants.json untouched

## 2026-08-18T17:40:18.015Z
- fixtures: 161; grouped 5-fold CV with in-fold reference fitting
- CV median APE: 12.9%, p90: 34.9%, spearman: 0.934, sign: 90.0%
- in-sample median APE: 10.3%
- verdict: FAIL (advisory — recalled fixtures)
  - CV sign accuracy 90.0% below 95.0%

## 2026-08-18T17:41:18.175Z
- fixtures: 161; grouped 5-fold CV with in-fold reference fitting
- CV median APE: 12.9%, p90: 34.9%, spearman: 0.934, sign: 100.0%
- in-sample median APE: 10.3%
- verdict: PASS (advisory — recalled fixtures)

## 2026-08-18T17:41:19.355Z
- fixtures: 161; grouped 5-fold CV with in-fold reference fitting
- CV median APE: 12.9%, p90: 34.9%, spearman: 0.934, sign: 100.0%
- in-sample median APE: 10.3%
- verdict: PASS (advisory — recalled fixtures)

## calibration 2026-08-18T17:59:10.878Z
- fixtures: 161 (161 recalled)
- in-sample medianAPE 14.8% -> 10.3%
- ref cyberpunk-2077 gpuBound x0.954 cpuBound x1.182 (43 fixtures)
- ref counter-strike-2 gpuBound x0.912 cpuBound x1.174 (29 fixtures)
- ref forza-horizon-5 gpuBound x1.234 cpuBound x1 (16 fixtures)
- ref shadow-of-the-tomb-raider gpuBound x0.966 cpuBound x1 (8 fixtures)
- ref red-dead-redemption-2 gpuBound x1.065 cpuBound x1 (6 fixtures)
- ref fortnite gpuBound x0.833 cpuBound x1 (7 fixtures)
- ref alan-wake-2 gpuBound x0.906 cpuBound x1 (4 fixtures)
- ref black-myth-wukong gpuBound x0.965 cpuBound x1 (2 fixtures)
- ref total-war-warhammer-iii gpuBound x1.191 cpuBound x1.191 (8 fixtures)
- ref the-last-of-us-part-i gpuBound x0.888 cpuBound x0.841 (6 fixtures)
- ref hogwarts-legacy gpuBound x1.03 cpuBound x1 (6 fixtures)
- ref rocket-league gpuBound x0.866 cpuBound x1 (4 fixtures)
- ref league-of-legends gpuBound x0.866 cpuBound x1 (4 fixtures)
- ref microsoft-flight-simulator-2024 gpuBound x1.119 cpuBound x1.046 (3 fixtures)
- ref hellblade-ii gpuBound x0.965 cpuBound x1 (2 fixtures)
- ref valorant gpuBound x1.182 cpuBound x1.182 (7 fixtures)
- ref apex-legends gpuBound x1.063 cpuBound x0.936 (2 fixtures)
- global constants: frozen priors (see src/core/constants.ts); constants.json untouched

## 2026-08-18T17:59:11.893Z
- fixtures: 161; grouped 5-fold CV with in-fold reference fitting
- CV median APE: 12.9%, p90: 34.9%, spearman: 0.934, sign: 100.0%
- in-sample median APE: 10.3%
- verdict: PASS (advisory — recalled fixtures)

## calibration 2026-08-18T18:03:04.124Z
- fixtures: 161 (161 recalled)
- in-sample medianAPE 14.4% -> 10.3%
- ref cyberpunk-2077 gpuBound x0.954 cpuBound x1.182 (43 fixtures)
- ref counter-strike-2 gpuBound x0.912 cpuBound x1.174 (29 fixtures)
- ref forza-horizon-5 gpuBound x1.234 cpuBound x1 (16 fixtures)
- ref shadow-of-the-tomb-raider gpuBound x0.966 cpuBound x1 (8 fixtures)
- ref red-dead-redemption-2 gpuBound x1.065 cpuBound x1 (6 fixtures)
- ref fortnite gpuBound x0.833 cpuBound x1 (7 fixtures)
- ref alan-wake-2 gpuBound x0.906 cpuBound x1 (4 fixtures)
- ref black-myth-wukong gpuBound x0.965 cpuBound x1 (2 fixtures)
- ref total-war-warhammer-iii gpuBound x1.191 cpuBound x1.191 (8 fixtures)
- ref the-last-of-us-part-i gpuBound x0.888 cpuBound x0.841 (6 fixtures)
- ref hogwarts-legacy gpuBound x1.03 cpuBound x1 (6 fixtures)
- ref rocket-league gpuBound x0.866 cpuBound x1 (4 fixtures)
- ref league-of-legends gpuBound x0.866 cpuBound x1 (4 fixtures)
- ref microsoft-flight-simulator-2024 gpuBound x1.119 cpuBound x1.046 (3 fixtures)
- ref hellblade-ii gpuBound x0.965 cpuBound x1 (2 fixtures)
- ref valorant gpuBound x1.182 cpuBound x1.182 (7 fixtures)
- ref apex-legends gpuBound x1.063 cpuBound x0.936 (2 fixtures)
- global constants: frozen priors (see src/core/constants.ts); constants.json untouched

## 2026-08-18T18:03:05.218Z
- fixtures: 161; grouped 5-fold CV with in-fold reference fitting
- CV median APE: 13.6%, p90: 35.6%, spearman: 0.931, sign: 100.0%
- in-sample median APE: 10.3%
- verdict: PASS (advisory — recalled fixtures)

## 2026-08-20T10:05:17.655Z
- fixtures: 161; grouped 5-fold CV with in-fold reference fitting
- CV median APE: 13.6%, p90: 35.6%, spearman: 0.931, sign: 100.0%
- in-sample median APE: 10.3%
- verdict: PASS (advisory — recalled fixtures)

## calibration 2026-08-20T10:05:56.028Z
- fixtures: 161 (161 recalled)
- in-sample medianAPE 14.3% -> 9.4%
- ref cyberpunk-2077 gpuBound x0.954 cpuBound x1.182 (43 fixtures)
- ref counter-strike-2 gpuBound x1 cpuBound x1.174 (29 fixtures)
- ref forza-horizon-5 gpuBound x1.234 cpuBound x1 (16 fixtures)
- ref shadow-of-the-tomb-raider gpuBound x0.966 cpuBound x1 (8 fixtures)
- ref red-dead-redemption-2 gpuBound x1.065 cpuBound x1 (6 fixtures)
- ref fortnite gpuBound x0.833 cpuBound x1 (7 fixtures)
- ref alan-wake-2 gpuBound x0.906 cpuBound x1 (4 fixtures)
- ref black-myth-wukong gpuBound x0.965 cpuBound x1 (2 fixtures)
- ref total-war-warhammer-iii gpuBound x1.191 cpuBound x1.191 (8 fixtures)
- ref the-last-of-us-part-i gpuBound x0.888 cpuBound x0.841 (6 fixtures)
- ref hogwarts-legacy gpuBound x1.03 cpuBound x1 (6 fixtures)
- ref rocket-league gpuBound x0.866 cpuBound x1 (4 fixtures)
- ref league-of-legends gpuBound x0.906 cpuBound x1 (4 fixtures)
- ref microsoft-flight-simulator-2024 gpuBound x1.119 cpuBound x1.046 (3 fixtures)
- ref hellblade-ii gpuBound x0.965 cpuBound x1 (2 fixtures)
- ref valorant gpuBound x1.182 cpuBound x1.182 (7 fixtures)
- ref apex-legends gpuBound x1.063 cpuBound x0.936 (2 fixtures)
- global constants: frozen priors (see src/core/constants.ts); constants.json untouched

## 2026-08-20T10:05:57.339Z
- fixtures: 161; grouped 5-fold CV with in-fold reference fitting
- CV median APE: 12.6%, p90: 29.6%, spearman: 0.947, sign: 100.0%
- in-sample median APE: 9.4%
- verdict: PASS (advisory — recalled fixtures)

## 2026-08-20T10:18:34.015Z
- fixtures: 161; grouped 5-fold CV with in-fold reference fitting
- CV median APE: 12.6%, p90: 29.6%, spearman: 0.947, sign: 100.0%
- in-sample median APE: 9.4%
- verdict: PASS (advisory — recalled fixtures)

## 2026-08-20T10:25:41.126Z
- fixtures: 161; grouped 5-fold CV with in-fold reference fitting
- CV median APE: 12.6%, p90: 29.6%, spearman: 0.947, sign: 100.0%
- in-sample median APE: 9.4%
- verdict: PASS (advisory — recalled fixtures)

## 2026-08-20T10:44:30.392Z
- fixtures: 161; grouped 5-fold CV with in-fold reference fitting
- CV median APE: 12.6%, p90: 29.6%, spearman: 0.947, sign: 100.0%
- in-sample median APE: 9.4%
- verdict: PASS (advisory — recalled fixtures)

## 2026-08-20T10:56:14.643Z
- fixtures: 161; grouped 5-fold CV with in-fold reference fitting
- CV median APE: 12.6%, p90: 29.6%, spearman: 0.947, sign: 100.0%
- in-sample median APE: 9.4%
- verdict: PASS (advisory — recalled fixtures)

## 2026-08-20T11:20:16.990Z
- fixtures: 161; grouped 5-fold CV with in-fold reference fitting
- CV median APE: 12.6%, p90: 29.6%, spearman: 0.947, sign: 100.0%
- in-sample median APE: 9.4%
- verdict: PASS (advisory — recalled fixtures)

## 2026-08-20T11:25:14.191Z
- fixtures: 234; grouped 5-fold CV with in-fold reference fitting
- CV median APE: 11.7%, p90: 28.4%, spearman: 0.951, sign: 100.0%
- in-sample median APE: 10.2%
- verdict: PASS (advisory — recalled fixtures)

## 2026-08-20T11:28:16.137Z
- fixtures: 234; grouped 5-fold CV with in-fold reference fitting
- CV median APE: 11.7%, p90: 28.4%, spearman: 0.951, sign: 100.0%
- in-sample median APE: 10.2%
- verdict: PASS (advisory — recalled fixtures)

## 2026-08-20T11:35:17.857Z
- fixtures: 234; grouped 5-fold CV with in-fold reference fitting
- CV median APE: 11.7%, p90: 28.4%, spearman: 0.951, sign: 100.0%
- in-sample median APE: 10.2%
- verdict: PASS (advisory — recalled fixtures)

## 2026-08-20T12:09:10.684Z
- fixtures: 234; grouped 5-fold CV with in-fold reference fitting
- CV median APE: 11.7%, p90: 28.4%, spearman: 0.951, sign: 100.0%
- in-sample median APE: 10.2%
- verdict: PASS (advisory — recalled fixtures)
