/** Concepts wire types (spec §3.5) — mirror ConceptService's DTO records. */
import type { GridItem } from 'src/stores/entries';

/** Workbench read model — mirrors ConceptService.ConceptDto (Instant → ISO string). */
export interface ConceptDto {
  id: number;
  name: string;
  phrase: string;
  exampleCount: number;
  excludedCount: number;
  /** Entries carrying the concept's tag (any provenance). */
  taggedCount: number;
  /** Examples still missing the tag — the "apply to N" preview. */
  applyable: number;
  /** An anchor exists but was built under a different embedding model. */
  anchorStale: boolean;
  createdAt: string;
  updatedAt: string;
}

/** One suggestion: a grid-shaped entry (the server's EntryDto) plus its
 *  best-frame cosine to the concept anchor. */
export interface SuggestionItem {
  entry: GridItem;
  score: number;
}

/** GET /api/concepts/{id}/members — the workbench member lists as score-less
 *  grid-shaped entries (the same shape the suggestion items carry). Entries that
 *  vanish while listed are skipped server-side, never an error. */
export interface ConceptMembers {
  examples: GridItem[];
  excluded: GridItem[];
}

/** POST /api/concepts/{id}/apply accounting — mirrors ConceptService.ApplyResult. */
export interface ApplyResult {
  /** The tag that was written (the concept's name). */
  tag: string;
  /** The concept's example count — the apply scope. */
  examples: number;
  /** media_tag rows inserted by this apply (name tag plus implication closure). */
  tagged: number;
  /** Examples that already carried the tag before the call. */
  alreadyTagged: number;
}
