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
                        parent_id        UUID        REFERENCES genres(id) ON DELETE CASCADE,
                        name             TEXT        NOT NULL,
                        slug             TEXT        NOT NULL UNIQUE,
                        description      TEXT,
                        prototype_vector vector(768),
                        created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    -- Ensure we don't have duplicate names under the same parent
                        UNIQUE(parent_id, name)
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
-- 1. Insert Parent Genres
INSERT INTO genres (name, slug, description) VALUES
                                                 ('Fiction & Literature', 'fiction', 'The catch-all for general storytelling, relationships, and the past. Focuses on character development, emotional arcs, and narrative craft.'),
                                                 ('Sci-Fi & Fantasy', 'sci-fi-fantasy', 'Imaginative world-building, magical systems, mythical creatures, and future tech. Books that transport you beyond the boundaries of mundane reality.'),
                                                 ('Mystery & Thriller', 'mystery-thriller', 'High stakes, tension, and the quest for truth. Driven by crime-solving, psychological manipulation, and pacing designed to keep you on edge.'),
                                                 ('Business & Economics', 'business-economics', 'How the world works, money, and building things. Explores corporate strategy, the global economy, leadership, and personal wealth.'),
                                                 ('History & Biography', 'history-biography', 'Non-fiction documenting the real events and people that shaped our world, from sweeping civilizations to intimate personal memoirs.'),
                                                 ('Science, Mind & Body', 'science-lifestyle', 'Self-improvement, tech, and understanding the universe. Bridges the hard facts of physics with the introspective realms of psychology.'),
                                                 ('Poetry & Verse', 'poetry', 'Rhythmic, lyrical, and evocative writing that explores emotion, nature, and the human soul through carefully crafted verse and stanzas.');
-- 2. Insert Sub-Genres with Dedicated Descriptions
INSERT INTO genres (parent_id, name, slug, description) VALUES
-- Fiction & Literature Children
((SELECT id FROM genres WHERE slug = 'fiction'), 'Historical Fiction', 'historical-fiction', 'Stories set in specific past eras with accurate period details, weaving real historical events with fictional characters.'),
((SELECT id FROM genres WHERE slug = 'fiction'), 'Romance & Relationships', 'romance', 'Narratives focused on love stories, emotional connections, intense chemistry, heartbreak, and happily-ever-afters.'),
((SELECT id FROM genres WHERE slug = 'fiction'), 'Literary Fiction & Classics', 'literary-fiction', 'Character-driven stories prioritizing gorgeous prose style, psychological depth, and artistic innovation over fast-paced plots.'),
((SELECT id FROM genres WHERE slug = 'fiction'), 'Young Adult (YA)', 'young-adult', 'Coming-of-age tales focusing on the teenage experience, first loves, identity formation, and finding one''s place in the world.'),

-- Sci-Fi & Fantasy Children
((SELECT id FROM genres WHERE slug = 'sci-fi-fantasy'), 'Science Fiction', 'science-fiction', 'Explorations of space, futuristic technology, artificial intelligence, and the cosmic impact of science on humanity.'),
((SELECT id FROM genres WHERE slug = 'sci-fi-fantasy'), 'High / Epic Fantasy', 'epic-fantasy', 'Massive world-building featuring complex magic systems, mythical creatures, epic quests, and sweeping fictional histories.'),
((SELECT id FROM genres WHERE slug = 'sci-fi-fantasy'), 'Dystopian & Cyberpunk', 'dystopian', 'Gritty, futuristic narratives exploring oppressive societal control, totalitarian governments, and high-tech survival.'),
((SELECT id FROM genres WHERE slug = 'sci-fi-fantasy'), 'Paranormal & Urban Fantasy', 'paranormal', 'Contemporary worlds where magic, supernatural entities, vampires, and hidden mysteries exist secretly alongside humans.'),

-- Mystery & Thriller Children
((SELECT id FROM genres WHERE slug = 'mystery-thriller'), 'Crime & Detective', 'crime-detective', 'Gritty police procedurals and crime-solving centered on forensics, complex investigations, and the pursuit of justice.'),
((SELECT id FROM genres WHERE slug = 'mystery-thriller'), 'Psychological Thriller', 'psychological-thriller', 'Tense narratives driven by mental manipulation, unreliable narrators, extreme paranoia, and domestic mind games.'),
((SELECT id FROM genres WHERE slug = 'mystery-thriller'), 'Suspense & Espionage', 'suspense-espionage', 'High-stakes thrillers featuring spies, assassins, global conspiracies, covert operations, and relentless action.'),
((SELECT id FROM genres WHERE slug = 'mystery-thriller'), 'Cozy Mystery', 'cozy-mystery', 'Lighthearted whodunits featuring amateur sleuths, quirky tight-knit communities, and puzzle-solving without explicit violence.'),

-- Business & Economics Children
((SELECT id FROM genres WHERE slug = 'business-economics'), 'Economics & Finance', 'economics-finance', 'Deep dives into macro and micro economics, global trade, behavioral economics, and how markets shape society.'),
((SELECT id FROM genres WHERE slug = 'business-economics'), 'Entrepreneurship', 'entrepreneurship', 'Insights on startups, innovation, venture capital, scaling businesses, and the visionary pursuit of creating something new.'),
((SELECT id FROM genres WHERE slug = 'business-economics'), 'Management & Leadership', 'management-leadership', 'Strategies for organizational psychology, inspiring teams, executive leadership, and building resilient corporate cultures.'),
((SELECT id FROM genres WHERE slug = 'business-economics'), 'Personal Finance', 'personal-finance', 'Practical, actionable advice on wealth building, investing in markets, budgeting, overcoming debt, and achieving financial independence.'),

-- History & Biography Children
((SELECT id FROM genres WHERE slug = 'history-biography'), 'World & Ancient History', 'world-history', 'Sweeping examinations of human history, ancient civilizations, and the monumental cultural forces that shaped the modern world.'),
((SELECT id FROM genres WHERE slug = 'history-biography'), 'Biographies & Memoirs', 'memoir-biography', 'Intimate, true accounts documenting real lives, personal struggles, remarkable achievements, and historical figures.'),
((SELECT id FROM genres WHERE slug = 'history-biography'), 'Politics & Government', 'politics', 'Explorations of political science, democracy, foreign policy, justice, and the systems by which societies organize collective life.'),
((SELECT id FROM genres WHERE slug = 'history-biography'), 'Military History', 'military-history', 'Detailed accounts of wars, epic battles, military strategy, geopolitics, and the visceral experiences of soldiers throughout time.'),

-- Science, Mind & Body Children
((SELECT id FROM genres WHERE slug = 'science-lifestyle'), 'Psychology & Self-Help', 'psychology-self-help', 'Actionable frameworks for habit building, productivity, mindset shifts, and understanding human behavior and mental health.'),
((SELECT id FROM genres WHERE slug = 'science-lifestyle'), 'Computer Science & Tech', 'tech-computers', 'Explorations of artificial intelligence, algorithms, digital culture, and how the internet is fundamentally rewiring society.'),
((SELECT id FROM genres WHERE slug = 'science-lifestyle'), 'Physics & Hard Science', 'hard-science', 'Accessible deep dives into physics, astronomy, biology, quantum mechanics, and humanity''s place in the universe.'),
((SELECT id FROM genres WHERE slug = 'science-lifestyle'), 'Philosophy & Society', 'philosophy-sociology', 'Profound discussions on ethics, metaphysics, societal structures, stoicism, and the fundamental questions of meaning and existence.'),

-- Poetry & Verse Children
((SELECT id FROM genres WHERE slug = 'poetry'), 'Classic Poetry', 'classic-poetry', 'Timeless verses from history’s greatest masters, exploring romanticism, tragedy, and the human condition in traditional forms.'),
((SELECT id FROM genres WHERE slug = 'poetry'), 'Contemporary & Modern', 'contemporary-poetry', 'Modern free verse and contemporary voices breaking traditional rules to explore identity, trauma, and modern life.'),
((SELECT id FROM genres WHERE slug = 'poetry'), 'Epic & Narrative', 'epic-poetry', 'Sweeping, book-length poems and mythic tales that tell grand stories of heroes, gods, and historical journeys.'),
((SELECT id FROM genres WHERE slug = 'poetry'), 'Spoken Word & Slam', 'spoken-word', 'Passionate, performance-based poetry written to be heard aloud, often dealing with social justice, politics, and raw emotion.');