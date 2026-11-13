/** Tag wire types (spec §7) + the booru categories (pool_type leads). */

export type TagCategory = 'pool_type' | 'general' | 'artist' | 'character' | 'copyright' | 'meta';

/** Facet grouping + the tag editor's category selector follow this order — pool_type leads. */
export const TAG_CATEGORIES: TagCategory[] = [
  'pool_type',
  'general',
  'artist',
  'character',
  'copyright',
  'meta',
];

/**
 * The user-facing categories: `pool_type` is INTERNALLY GENERATED vocabulary (it
 * distinguishes realized pools from suggested drafts), so tag CREATION and
 * RECATEGORIZATION selectors must never offer it. Visibility keeps TAG_CATEGORIES
 * (the tags page filter chips, chips on pools, the pools-page facets — which
 * still group pool_type first).
 */
export const USER_TAG_CATEGORIES: TagCategory[] = TAG_CATEGORIES.filter((c) => c !== 'pool_type');

export interface TagDto {
  id: number;
  name: string;
  category: TagCategory;
  postCount: number;
  /** Provenance of this entry's tag link ('user' or e.g. 'hydra-3.5'); null off the entry context. */
  taggedBy?: string | null;
}

/** Sidebar facet: tag usage over the current result set. */
export interface FacetDto {
  name: string;
  category: TagCategory;
  count: number;
}

/** Autocomplete hit: prefix matches first, then substring, then alias names. */
export interface AutocompleteDto {
  name: string;
  category: TagCategory;
  postCount: number;
  aliasOf: string | null;
}

/**
 * Client-side preview of the server's name normalization (core TagName): the server
 * re-validates on PUT, this just keeps the editor's working set honest.
 */
export function normalizeTagName(raw: string): string {
  return raw
    .trim()
    .toLowerCase()
    .replace(/:/g, '')
    .replace(/\s+/g, '_')
    .replace(/^[^a-z0-9_()+.'-]+/, '')
    .replace(/[^a-z0-9_()+.'-]+$/, '');
}
