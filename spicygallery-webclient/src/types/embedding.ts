/** Wire shapes of GET /api/embeddings/status + backfill (spec slice-3 §6). */
export interface EmbeddingStatus {
  enabled: boolean;
  model: string;
  breakerOpen: boolean;
  withEmbeddings: number;
  missing: number;
}

export interface BackfillStatus {
  running: boolean;
  done: number;
  total: number;
  error: string | null;
}
