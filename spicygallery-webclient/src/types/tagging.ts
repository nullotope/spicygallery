/** Tagging wire types (spec §3.2/§6) — mirror TaggingController's DTO records. */
import type { TagCategory } from './tags';

/** GET /api/tagging/info — model observability; `reachable` mirrors the live upstream. */
export interface TaggingInfo {
  enabled: boolean;
  reachable: boolean;
  model: string;
  labels: number | null;
  calibration: string;
  implications: string;
}

/** One model prediction; `category` uses the same wire names as TagDto. */
export interface PredictedTag {
  name: string;
  category: TagCategory;
  probability: number;
}

/** POST /api/entries/{id}/autotag/predict — classifies without writing (spec §3.2). */
export interface PredictResponse {
  model: string;
  calibration: string;
  tags: PredictedTag[];
}
