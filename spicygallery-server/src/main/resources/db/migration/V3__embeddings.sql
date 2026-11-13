-- Slice 3 schema (spec §3.3): pgvector embeddings per entry (one row per video frame).
-- Dimension 768 pinned after a live probe of embeddinggemma-2 (plan Task 1 Step 6).

create extension if not exists vector;

create table media_embedding (
  id              bigint generated always as identity primary key,
  media_entry_id  bigint not null references media_entry (id) on delete cascade,
  frame_idx       int not null default 0,          -- 0 for images
  timestamp_ms    bigint,                          -- videos only
  model           text not null,
  embedding       vector(768) not null
);
create index media_embedding_entry_idx on media_embedding (media_entry_id);
create index media_embedding_hnsw_idx on media_embedding
  using hnsw (embedding vector_cosine_ops);
