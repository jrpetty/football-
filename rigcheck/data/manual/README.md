# Manual data import lane

Drop CSV files in this directory. `npm run import:manual` validates and ingests
them on every build; rejections are reported per row with the reason.

This lane exists because the highest-quality performance data will not come from
an automated agent. It comes from reviewer datasets shared under licence, from
forum spreadsheets, and above all from your own measurements via `harness/`.
**The long-term accuracy of the model depends on this lane being frictionless**,
so the schema is deliberately forgiving about optional columns and strict about
the ones that change the meaning of a measurement.

## Schema

Required columns:

| Column | Type | Notes |
|---|---|---|
| `gpu_id` | string | Must match a catalogue id, e.g. `nvidia-geforce-rtx-3060-12gb` |
| `cpu_id` | string | Must match a catalogue id, e.g. `amd-ryzen-5-3600` |
| `game_id` | string | Must match a catalogue id, e.g. `cyberpunk-2077` |
| `resolution` | enum | `1080p`, `1440p`, `2160p`, `3440x1440` |
| `preset` | string | Free text, but be consistent — `ultra`, `high`, `medium` |
| `avg_fps` | number | > 0 |

Strongly recommended (their absence reduces the record's weight):

| Column | Type | Notes |
|---|---|---|
| `low_1pct` | number | 1% low FPS |
| `low_01pct` | number | 0.1% low FPS |
| `upscaling_tech` | enum | `none`, `dlss`, `fsr`, `xess`, `tsr` — defaults to `none` |
| `upscaling_quality` | enum | `native`, `quality`, `balanced`, `performance`, `ultra-performance` |
| `frame_gen` | bool | `true`/`false` — defaults to false |
| `rt_tier` | string | e.g. `off`, `medium`, `psycho` |
| `ram_gb` | number | |
| `ram_channels` | number | 1, 2, 4 or 8 |
| `ram_mts` | number | |
| `ram_cl` | number | |
| `storage` | enum | `hdd`, `sata-ssd`, `nvme-gen3`, `nvme-gen4` |
| `game_build` | string | Game version/patch |
| `driver_version` | string | |
| `api` | string | `dx12`, `dx11`, `vulkan` |
| `date` | ISO date | When the measurement was taken |
| `source_note` | string | Where it came from |

### Why the fingerprint columns matter

"Ultra at 1440p" in a 2019 review and a 2024 review of the same game are
different workloads: presets get retuned by patches, a DX12 path lands,
upscaling defaults change. Ingesting them as equivalent injects bias that looks
exactly like signal.

The importer therefore:

- Records a **settings fingerprint** per row.
- **Reduces the weight** of rows missing `game_build`, `driver_version`, `date`
  or the upscaling columns, rather than discarding them.
- **Refuses to form a comparison edge** between two rows whose hard axes
  (upscaling, RT tier, API, resolution) differ.

A row without `upscaling_tech` is assumed native. If that assumption is wrong for
your data, set the column — an upscaled figure silently treated as native is one
of the worst corruptions you can feed this model.

## Example

```csv
gpu_id,cpu_id,game_id,resolution,preset,avg_fps,low_1pct,upscaling_tech,upscaling_quality,ram_gb,ram_channels,ram_mts,storage,driver_version,date,source_note
nvidia-geforce-rtx-3060-12gb,amd-ryzen-5-3600,cyberpunk-2077,1080p,high,72,58,none,native,16,2,3200,nvme-gen3,546.33,2024-03-11,own measurement via harness
amd-radeon-rx-6800-xt,amd-ryzen-7-5800x3d,counter-strike-2,1440p,high,412,241,none,native,32,2,3600,nvme-gen4,24.1.1,2024-04-02,own measurement via harness
```

## Running

```bash
npm run import:manual
```

Output lands in `data/measured/records.json` and a per-file report is printed.
Rejected rows are listed individually with the reason — nothing fails silently.
