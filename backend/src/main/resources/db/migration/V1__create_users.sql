CREATE TABLE users (
    id            BIGINT  GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    email         varchar(254)  NOT NULL,
    password_hash varchar(255)  NOT NULL,
    role          varchar(20)  NOT NULL,
    created_at    timestamptz   NOT NULL DEFAULT now(),

    CONSTRAINT uk_users_email       UNIQUE (email),
    CONSTRAINT ck_users_email_lower CHECK  (email = lower(email)),
    CONSTRAINT ck_users_role        CHECK  (role IN ('USER','ADMIN'))
);