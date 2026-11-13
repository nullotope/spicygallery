-- Slice 1 schema (spec §5)

create table media_entry (
  id            bigint generated always as identity primary key, -- booru-style short IDs
  kind          text not null,        -- 'image' | 'video'
  status        text not null,        -- 'ok' | 'missing' | 'error'
  root_name     text not null,        -- configured library name (identity key)
  rel_path      text not null,        -- relative to that library's root, '/'-separated
  file_name     text not null,
  ext           text not null,        -- lowercased without dot
  mime          text not null,
  size_bytes    bigint not null,
  content_hash  text not null,        -- sha-256 hex, lowercased; set on every ingest
  width         int,                  -- EXIF-orientation-applied
  height        int,
  duration_ms   bigint,               -- video only
  media_mtime   timestamptz not null, -- file mtime at last ingest
  imported_at   timestamptz not null default now(),
  updated_at    timestamptz not null default now(),
  unique (root_name, rel_path)
);
create index media_entry_kind_idx   on media_entry (kind);
create index media_entry_status_idx on media_entry (status);
-- keyset scans use the PK index backward; no extra id index needed

create table import_run (
  id            bigint generated always as identity primary key,
  status        text not null,        -- 'running' | 'completed' | 'failed'
  started_at    timestamptz not null default now(),
  finished_at   timestamptz,
  scanned       int not null default 0,
  added         int not null default 0,
  updated       int not null default 0,
  missing_count int not null default 0,
  error_count   int not null default 0,
  message       text                  -- failure reason / error summary
);
