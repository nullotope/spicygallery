-- Slice 2 schema (spec §4): tags, aliases, implications, entry-tag links.

create extension if not exists pg_trgm;

create table tag (
  id          bigint generated always as identity primary key,
  name        text not null,            -- canonical, normalized: lowercase + underscores
  category    text not null,            -- 'general' | 'artist' | 'character' | 'copyright' | 'meta'
  post_count  int not null default 0,   -- denormalized; maintained by TagService in-txn
  created_at  timestamptz not null default now(),
  unique (name)
);
create index tag_post_count_idx on tag (post_count desc, id);
create index tag_name_trgm_idx  on tag using gin (name gin_trgm_ops);  -- autocomplete ILIKE

create table tag_alias (
  id              bigint generated always as identity primary key,
  antecedent_name text not null,        -- the alias (non-canonical) name
  tag_id          bigint not null references tag (id),   -- canonical target
  created_at      timestamptz not null default now(),
  unique (antecedent_name)              -- a name is either a tag or an alias, never both
);

create table tag_implication (
  id                bigint generated always as identity primary key,
  antecedent_tag_id bigint not null references tag (id),
  consequent_tag_id bigint not null references tag (id),
  created_at        timestamptz not null default now(),
  unique (antecedent_tag_id, consequent_tag_id),
  check (antecedent_tag_id <> consequent_tag_id)
);

create table media_tag (
  media_entry_id bigint not null references media_entry (id) on delete cascade,
  tag_id         bigint not null references tag (id) on delete cascade,
  tagged_at      timestamptz not null default now(),
  tagged_by      text not null default 'user',   -- provenance: 'hydra' etc. arrives in slice 4
  primary key (media_entry_id, tag_id)
);
create index media_tag_tag_idx on media_tag (tag_id, media_entry_id);
