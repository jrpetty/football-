/** Reads config/business-jobs.json (server side; the pure rules are in business-jobs.ts). */
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { CONFIG_DIR } from './paths.ts';
import type { BusinessJobsConfig } from './business-jobs.ts';

export const BUSINESS_JOBS_FILE = join(CONFIG_DIR, 'business-jobs.json');

export function loadBusinessJobs(file = BUSINESS_JOBS_FILE): BusinessJobsConfig {
  return JSON.parse(readFileSync(file, 'utf8')) as BusinessJobsConfig;
}
