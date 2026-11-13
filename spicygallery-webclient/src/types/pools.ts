/** Pools wire types (spec §3.3–§3.9) — mirror PoolService's DTO records. */
import type { GridItem } from 'src/stores/entries';
import type { TagDto } from './tags';

/** The folder a pool was seeded from (spec §3.3); null for manual pools. */
export interface PoolOrigin {
  rootName: string;
  /** '' = the root's top level. */
  dirPath: string;
}

/**
 * One pool tile (spec §3.7) — mirrors PoolService.PoolDto (Instant → ISO string).
 * `entryCount`/`cover` are flattened values (grandchildren count; empty pool → null
 * cover). Nested sub-pools arrive as SUMMARY tiles: name/entryCount only, the rest null.
 */
export interface PoolDto {
  id: number;
  name: string;
  description: string | null;
  entryCount: number;
  updatedAt: string | null;
  origin: PoolOrigin | null;
  /** The pool's OWN tags incl. the `pool_type:pool` system tag. */
  tags: TagDto[];
  cover: GridItem | null;
  /** §4.3: present ONLY on the `GET /api/pools?entryId=` variant (NON_NULL wire style),
   *  true iff the entry is flattened-contained. */
  member?: boolean;
}

/**
 * One detail row — exactly one of `entry`/`pool` is non-null. `position` is the row's
 * slot in the pool's unified 1..N member sequence.
 */
export interface PoolMember {
  memberId: number;
  position: number;
  entry: GridItem | null;
  pool: PoolDto | null;
}

/** GET /api/pools/{id} (spec §3.9): tile + members in position order. */
export interface PoolDetail {
  pool: PoolDto;
  members: PoolMember[];
}

/**
 * A folder suggestion (spec §3.3) — a directory with >= 2 direct entries, retired when
 * it is an existing pool's origin. Virtual draft tiles, never stored.
 */
export interface FolderSuggestion {
  rootName: string;
  /** '' = the root's top level. */
  dirPath: string;
  entryCount: number;
  /** The folder's first natural-ordered entry (the draft tile's thumbnail). */
  cover: GridItem | null;
}