-- Slice 5 schema (spec §3.1): persistent concepts and their per-entry membership.

create table concept (
    id            bigserial primary key,
    name          text not null unique,   -- TagName.normalize'd; may coincide with an existing tag
    phrase        text,                   -- optional bootstrap seed (raw text)
    phrase_vector vector,                 -- resolved once at create/phrase-change (model = anchor_model)
    anchor        vector,                 -- null until computable (see §3.2)
    anchor_model  text,
    created_at    timestamptz not null default now(),
    updated_at    timestamptz not null default now()
);

create table concept_entry (
    concept_id     bigint not null references concept(id) on delete cascade,
    media_entry_id bigint not null references media_entry(id) on delete cascade,
    status         text not null check (status in ('EXAMPLE', 'EXCLUDED')),
    primary key (concept_id, media_entry_id)
);
create index concept_entry_entry_idx on concept_entry (media_entry_id);
