-- Slice 6 schema (spec §3.1): pools with unified (entry | sub-pool) membership, pool tags,
-- the import follow-up's pool link, and the pool_type tag seed.

create table pool (
    id          bigserial primary key,
    name        text not null unique,   -- free text, trimmed; NOT tag-normalized
    description text,
    origin_root text,                   -- folder pools: the directory they were seeded from
    origin_dir  text,                   -- ('' = library root top level); null = manual
    created_at  timestamptz not null default now(),
    updated_at  timestamptz not null default now()
);

-- unified members: entries AND sub-pools share one position sequence
create table pool_member (
    id             bigint generated always as identity primary key,
    pool_id        bigint not null references pool(id) on delete cascade,
    position       int not null,        -- 1..N over ALL members; renumbered on every mutation
    entry_id       bigint references media_entry(id) on delete cascade,
    child_pool_id  bigint references pool(id) on delete cascade,
    added_at       timestamptz not null default now(),
    check ((entry_id is null) <> (child_pool_id is null))
);
create unique index pool_member_entry_uq on pool_member (pool_id, entry_id) where entry_id is not null;
create unique index pool_member_pool_uq  on pool_member (pool_id, child_pool_id) where child_pool_id is not null;
create index pool_member_position_idx on pool_member (pool_id, position);

-- pool-level tags (own tags; distinct from member-entry tags)
create table pool_tag (
    pool_id bigint not null references pool(id) on delete cascade,
    tag_id  bigint not null references tag(id) on delete cascade,
    primary key (pool_id, tag_id)
);

-- the import follow-up records the pool it drafted
alter table import_run add column pool_id bigint references pool(id);

-- pool_type joins the tag categories as the FIRST category; no CHECK constraint
-- exists on tag.category (comment only), so seeding is all the migration needs:
insert into tag (name, category) values ('pool', 'pool_type'), ('suggested', 'pool_type')
  on conflict (name) do update set category = excluded.category;
