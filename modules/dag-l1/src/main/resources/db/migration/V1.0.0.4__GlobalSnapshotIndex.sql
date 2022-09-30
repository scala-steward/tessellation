create table if not exists GlobalSnapshotIndex (
    hash varchar(64) primary key,
    indexValue long not null default 0,
    snapshotBytes blob not null
);
