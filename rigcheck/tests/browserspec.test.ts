import { describe, expect, it } from 'vitest';
import {
  DEVICE_MEMORY_CAP_GB, inspectionSummary, resolutionFor, standardRefresh,
  type BrowserInspection,
} from '../src/core/browserspec.ts';

describe('resolution from a pixel width', () => {
  it('maps the four the catalogue has references for', () => {
    expect(resolutionFor(1920)).toBe('1080p');
    expect(resolutionFor(2560)).toBe('1440p');
    expect(resolutionFor(3440)).toBe('3440x1440');
    expect(resolutionFor(3840)).toBe('2160p');
  });

  it('rounds DOWN, so an unusual panel is never flattered', () => {
    // 3200 wide is between 1440p and ultrawide. Calling it the higher one would
    // make the machine look like it is doing more work than it is, and an
    // estimate built on that reads high — which is the error that costs
    // somebody money.
    expect(resolutionFor(3200)).toBe('1440p');
    expect(resolutionFor(3839)).toBe('3440x1440');
    expect(resolutionFor(2559)).toBe('1080p');
  });

  it('refuses anything below the lowest reference rather than guessing', () => {
    expect(resolutionFor(1366)).toBeNull();
    expect(resolutionFor(0)).toBeNull();
    expect(resolutionFor(-1)).toBeNull();
    expect(resolutionFor(NaN)).toBeNull();
  });

  it('handles a display far above anything in the catalogue', () => {
    expect(resolutionFor(7680)).toBe('2160p');
  });
});

describe('refresh rate', () => {
  it('snaps a measured rate to the panel it plainly is', () => {
    expect(standardRefresh(59.94)).toBe(60);
    expect(standardRefresh(61)).toBe(60);
    expect(standardRefresh(143.6)).toBe(144);
    expect(standardRefresh(239.1)).toBe(240);
    expect(standardRefresh(165.9)).toBe(165);
  });

  it('leaves a rate that is not near a standard panel alone', () => {
    // Silently moving a 90Hz panel to 100 would be worse than an odd-looking
    // number: the number is at least true.
    expect(standardRefresh(90)).toBe(90);
    expect(standardRefresh(85.4)).toBe(85);
  });

  it('is proportional, so the tolerance does not swallow neighbours at the top', () => {
    // 165 and 170 are five apart. A fixed +/-5Hz tolerance would make either
    // claim the other; the tolerance is a percentage for that reason.
    expect(standardRefresh(168)).toBe(170);
    expect(standardRefresh(163)).toBe(165);
  });

  it('returns zero for a rate that could not be measured', () => {
    expect(standardRefresh(0)).toBe(0);
    expect(standardRefresh(-30)).toBe(0);
    expect(standardRefresh(NaN)).toBe(0);
  });
});

describe('the summary', () => {
  const base: BrowserInspection = {
    renderer: '',
    gpuName: null,
    threads: null,
    memoryFloorGB: null,
    platform: null,
    display: null,
    webgpu: false,
    notes: [],
  };

  it('always says the processor model cannot be read, because it cannot', () => {
    const s = inspectionSummary({
      ...base,
      renderer: 'NVIDIA GeForce RTX 3060',
      gpuName: { value: 'NVIDIA GeForce RTX 3060', confidence: 'reported', note: '' },
      threads: { value: 16, confidence: 'reported', note: '' },
    });
    expect(s).toMatch(/processor model/);
  });

  it('names the memory cap rather than implying a total was read', () => {
    const s = inspectionSummary({
      ...base,
      memoryFloorGB: { value: 8, confidence: 'bound', note: '' },
    });
    expect(s).toContain(`${DEVICE_MEMORY_CAP_GB}GB`);
    expect(s).toMatch(/floor/i);
  });

  it('says so plainly when the browser identified nothing', () => {
    expect(inspectionSummary(base)).toMatch(/would not identify/i);
  });
});
