-- =============================================================================
-- V1__init_schema.sql
-- Bookshelf Scanner — initial schema
-- Flyway migration | PostgreSQL + pgvector
-- =============================================================================

-- Enable pgvector extension (Supabase has this available, run once)
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- =============================================================================
-- GENRES
-- Seeded at startup. prototype_vector is the centroid of all books in that
-- genre — used to seed a new user's profile_vector during cold-start onboarding.
-- =============================================================================
CREATE TABLE genres (
                        id               UUID        PRIMARY KEY DEFAULT uuid_generate_v4(),
                        name             TEXT        NOT NULL UNIQUE,           -- e.g. "Science Fiction"
                        slug             TEXT        NOT NULL UNIQUE,           -- e.g. "science-fiction"
                        prototype_vector vector(768),                           -- avg embedding of genre's books
                        created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- =============================================================================
-- BOOKS
-- One row per unique book (deduped on isbn where available, else title+author).
-- book_vector = embedding of: title + " " + author + " " + description + " " + genres
-- Never re-embedded once stored.
-- =============================================================================
CREATE TABLE books (
                       id           UUID        PRIMARY KEY DEFAULT uuid_generate_v4(),
                       isbn         TEXT        UNIQUE,                        -- ISBN-13 preferred, nullable
                       isbn10       TEXT        UNIQUE,                        -- fallback
                       title        TEXT        NOT NULL,
                       author       TEXT        NOT NULL,
                       description  TEXT,
                       cover_url    TEXT,
                       published_at DATE,
                       page_count   INT,
                       language     TEXT        NOT NULL DEFAULT 'en',
                       source       TEXT        NOT NULL DEFAULT 'google_books', -- 'google_books' | 'open_library' | 'manual'
                       book_vector  vector(768),                               -- NULL until embedding job runs
                       created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                       updated_at   TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Partial unique index: deduplicate by normalised title+author when isbn is absent
CREATE UNIQUE INDEX books_title_author_no_isbn_idx
    ON books (LOWER(TRIM(title)), LOWER(TRIM(author)))
    WHERE isbn IS NULL;

-- HNSW index for fast ANN search across entire books table
-- m=16, ef_construction=64 are sensible defaults for < 100k rows
CREATE INDEX books_vector_hnsw_idx
    ON books
        USING hnsw (book_vector vector_cosine_ops)
    WITH (m = 16, ef_construction = 64);

-- =============================================================================
-- BOOK_GENRES  (books ↔ genres  many-to-many)
-- =============================================================================
CREATE TABLE book_genres (
                             book_id  UUID NOT NULL REFERENCES books(id)  ON DELETE CASCADE,
                             genre_id UUID NOT NULL REFERENCES genres(id) ON DELETE CASCADE,
                             PRIMARY KEY (book_id, genre_id)
);

CREATE INDEX book_genres_genre_idx ON book_genres (genre_id);

-- =============================================================================
-- USERS
-- profile_vector = weighted centroid of all rated books (weight = rating).
-- Recomputed incrementally on every new user_books insert/update.
-- books_rated_count is a denormalised counter — avoids COUNT(*) on hot path.
-- =============================================================================
CREATE TABLE users (
                       id                  UUID        PRIMARY KEY DEFAULT uuid_generate_v4(),
                       email               TEXT        NOT NULL UNIQUE,
                       username            TEXT        NOT NULL UNIQUE,
                       password_hash       TEXT        NOT NULL,
                       profile_vector      vector(768),                        -- NULL until first book rated
                       books_rated_count   INT         NOT NULL DEFAULT 0,     -- denominator for centroid update
                       deviation_alpha     FLOAT4      NOT NULL DEFAULT 0.2    -- 0 = pure taste, 1 = full explore
                           CHECK (deviation_alpha >= 0 AND deviation_alpha <= 1),
                       onboarding_done     BOOLEAN     NOT NULL DEFAULT FALSE, -- false = cold start flow not yet complete
                       created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                       updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- =============================================================================
-- USER_GENRE_PREFERENCES
-- Written during onboarding wizard (step 1 of cold start).
-- Each row = user liked/weighted a genre. weight 1-5 mirrors rating scale.
-- Once real book ratings accumulate, profile_vector supersedes this,
-- but this table stays as a fallback and for UI display.
-- =============================================================================
CREATE TABLE user_genre_preferences (
                                        id         UUID        PRIMARY KEY DEFAULT uuid_generate_v4(),
                                        user_id    UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                                        genre_id   UUID        NOT NULL REFERENCES genres(id) ON DELETE CASCADE,
                                        weight     INT2        NOT NULL DEFAULT 3
                                            CHECK (weight BETWEEN 1 AND 5),
                                        created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                                        UNIQUE (user_id, genre_id)
);

CREATE INDEX ugp_user_idx ON user_genre_preferences (user_id);

-- =============================================================================
-- USER_BOOKS  (users ↔ books  many-to-many)
-- Core interaction table. Every time a user marks a book as read + rates it,
-- a row lands here and the application recomputes users.profile_vector.
-- status covers the "scan your own shelf" flow where a user found the book
-- but hasn't rated yet.
-- =============================================================================
CREATE TABLE user_books (
                            id        UUID        PRIMARY KEY DEFAULT uuid_generate_v4(),
                            user_id   UUID        NOT NULL REFERENCES users(id)  ON DELETE CASCADE,
                            book_id   UUID        NOT NULL REFERENCES books(id)  ON DELETE CASCADE,
                            rating    INT2                                        -- nullable until rated
                                CHECK (rating BETWEEN 1 AND 5),
                            status    TEXT        NOT NULL DEFAULT 'read'
                                CHECK (status IN ('read', 'want_to_read', 'scanning')),
    -- 'read'         = finished, may or may not have rating
    -- 'want_to_read' = added from recommendation, not yet read
    -- 'scanning'     = found on a shelf scan, user hasn't actioned yet
                            source    TEXT        NOT NULL DEFAULT 'manual'
                                CHECK (source IN ('manual', 'scan', 'search', 'onboarding')),
                            read_at   TIMESTAMPTZ,
                            created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                            updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                            UNIQUE (user_id, book_id)
);

CREATE INDEX user_books_user_idx ON user_books (user_id);
CREATE INDEX user_books_book_idx ON user_books (book_id);
CREATE INDEX user_books_rated_idx ON user_books (user_id)
    WHERE rating IS NOT NULL;   -- partial index — centroid update only touches rated rows

-- =============================================================================
-- SCANS
-- One row per camera session. raw_ocr_titles stores exactly what Gemini
-- returned before any lookup — useful for debugging failed matches.
-- =============================================================================
CREATE TABLE scans (
                       id              UUID        PRIMARY KEY DEFAULT uuid_generate_v4(),
                       user_id         UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                       raw_ocr_titles  TEXT[]      NOT NULL DEFAULT '{}',      -- Gemini raw output
                       image_url       TEXT,                                   -- optional, if you store the image
                       matched_count   INT         NOT NULL DEFAULT 0,         -- books successfully resolved
                       scanned_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX scans_user_idx ON scans (user_id);

-- =============================================================================
-- SCAN_BOOKS  (scans ↔ books  many-to-many)
-- Tracks exactly which books were resolved from each scan and their match
-- confidence. match_score = Books API / fuzzy match confidence 0.0–1.0.
-- recommendation_rank = position shown to this user at scan time (1 = top pick).
-- =============================================================================
CREATE TABLE scan_books (
                            id                  UUID    PRIMARY KEY DEFAULT uuid_generate_v4(),
                            scan_id             UUID    NOT NULL REFERENCES scans(id)  ON DELETE CASCADE,
                            book_id             UUID    NOT NULL REFERENCES books(id)  ON DELETE CASCADE,
                            match_score         FLOAT4  NOT NULL DEFAULT 1.0
                                CHECK (match_score BETWEEN 0 AND 1),
                            recommendation_rank INT2,                               -- NULL = not ranked (already read)
                            UNIQUE (scan_id, book_id)
);

CREATE INDEX scan_books_scan_idx  ON scan_books (scan_id);
CREATE INDEX scan_books_book_idx  ON scan_books (book_id);

-- =============================================================================
-- HELPER: auto-update updated_at on users and books
-- =============================================================================
CREATE OR REPLACE FUNCTION touch_updated_at()
    RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$;

CREATE TRIGGER users_updated_at
    BEFORE UPDATE ON users
    FOR EACH ROW EXECUTE FUNCTION touch_updated_at();

CREATE TRIGGER books_updated_at
    BEFORE UPDATE ON books
    FOR EACH ROW EXECUTE FUNCTION touch_updated_at();

-- =============================================================================
-- SEED: genre rows (no prototype_vectors yet — computed after first book batch)
-- =============================================================================
INSERT INTO genres (name, slug) VALUES
                                    ('Science Fiction',   'science-fiction'),
                                    ('Fantasy',           'fantasy'),
                                    ('Mystery',           'mystery'),
                                    ('Thriller',          'thriller'),
                                    ('Romance',           'romance'),
                                    ('Horror',            'horror'),
                                    ('Historical Fiction','historical-fiction'),
                                    ('Literary Fiction',  'literary-fiction'),
                                    ('Biography',         'biography'),
                                    ('Self Help',         'self-help'),
                                    ('Science',           'science'),
                                    ('Technology',        'technology'),
                                    ('Philosophy',        'philosophy'),
                                    ('Psychology',        'psychology'),
                                    ('Business',          'business'),
                                    ('Economics',         'economics'),
                                    ('History',           'history'),
                                    ('Politics',          'politics'),
                                    ('Travel',            'travel'),
                                    ('Comics & Graphic Novels', 'comics-graphic-novels');