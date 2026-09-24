/**
 * Tiny synchronous lookup the mock server fills with data: URLs for
 * artifacts and exports, so `artifactUrl()` can stay synchronous.
 */
export const mockArtifactUrls = new Map<string, string>();
