/** Wire shapes of GET /api/system/info (spec §8) — mirrors SystemInfoService.SystemInfo. */
export interface ToolInfo {
  found: boolean;
  source: 'configured' | 'path' | null;
  version: string | null;
}

export interface LibraryInfo {
  name: string;
  root: string;
  accessible: boolean;
}

export interface SystemInfo {
  libraries: LibraryInfo[];
  ffmpeg: ToolInfo;
  ffprobe: ToolInfo;
  appData: string;
  workers: number;
}
