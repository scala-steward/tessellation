create table if not exists BalanceIndex (
    address varchar(40),
    balance integer,
    height integer,
    hash varchar(64),
    constraint balance_non_negative check (balance >= 0),
    PRIMARY KEY (hash, address)
);
