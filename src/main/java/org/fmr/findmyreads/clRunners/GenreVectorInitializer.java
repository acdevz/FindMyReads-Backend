package org.fmr.findmyreads.clRunners;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.fmr.findmyreads.models.Genre;
import org.fmr.findmyreads.repositories.GenreRepository;
import org.fmr.findmyreads.services.EmbeddingService;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Runs once on application startup.
 * For every genre that has no prototype_vector, generates an embedding
 * from a rich descriptive prompt about that genre and persists it.
 * Idempotent — genres that already have a vector are skipped entirely.
 * Safe to restart the application at any time.
 * Why a descriptive prompt instead of just the genre name?
 * Embedding "Science Fiction" alone gives a thin vector.
 * Embedding a description of what Science Fiction books feel like —
 * themes, tone, typical content — gives a much richer centroid that
 * genuinely represents the semantic space of that genre.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GenreVectorInitializer implements CommandLineRunner {

    private final GenreRepository genreRepository;
    private final EmbeddingService embeddingService;

    @Override
    @Transactional
    public void run(String... args) {
        List<Genre> genres = genreRepository.findAll();

        long missing = genres.stream().filter(g -> g.getPrototypeVector() == null).count();
        if (missing == 0) {
            log.info("GenreVectorInitializer: all {} genres already have prototype vectors — skipping", genres.size());
            return;
        }

        log.info("GenreVectorInitializer: computing prototype vectors for {}/{} genres", missing, genres.size());

        for (Genre genre : genres) {
            if (genre.getPrototypeVector() != null) continue;

            try {
                String prompt = buildGenrePrompt(genre.getSlug(), genre.getName());
                float[] vector = embeddingService.embed(prompt);
                genre.setPrototypeVector(vector);
                genreRepository.save(genre);
                log.info("Embedded genre: {}", genre.getName());
            } catch (Exception e) {
                log.error("Failed to embed genre '{}': {}", genre.getName(), e.getMessage());
            }
        }

        log.info("GenreVectorInitializer: done");
    }

    // -------------------------------------------------------------------------

    /**
     * Builds a rich descriptive prompt for a genre.
     * The prompt describes the genre's themes, tone, typical subjects, and
     * what readers enjoy about it — giving the embedding model enough context
     * to place the vector accurately in semantic space.
     * This is the cold-start seed: a new user who picks "Science Fiction"
     * gets a profile_vector that already understands what Sci-Fi feels like,
     * before they've rated a single book.
     */
    private String buildGenrePrompt(String slug, String name) {
        return switch (slug) {
            case "science-fiction" -> """
                Science fiction books exploring space exploration, futuristic technology,
                artificial intelligence, dystopian societies, time travel, alien civilizations,
                and the impact of science on humanity. Authors like Isaac Asimov, Arthur C. Clarke,
                Philip K. Dick, Ursula K. Le Guin. Themes of humanity's future, technological
                progress, and speculative worlds beyond our own.
                """;
            case "fantasy" -> """
                Fantasy novels featuring magic systems, mythical creatures, epic quests,
                world-building with invented histories and cultures, heroes and villains,
                swords and sorcery. Authors like J.R.R. Tolkien, Brandon Sanderson,
                George R.R. Martin, Robin Hobb. Themes of good versus evil, power,
                destiny, and richly imagined secondary worlds.
                """;
            case "mystery" -> """
                Mystery novels centered on solving crimes, detective investigations,
                whodunit puzzles, clues and red herrings, murder investigations,
                amateur and professional sleuths. Authors like Agatha Christie,
                Arthur Conan Doyle, Raymond Chandler. Themes of justice, deception,
                hidden truth, and the satisfaction of revelation.
                """;
            case "thriller" -> """
                Thriller novels driven by suspense, high stakes, danger, espionage,
                psychological tension, fast-paced plotting, conspiracies, assassins,
                and life-or-death situations. Authors like John Grisham, Lee Child,
                Gillian Flynn, Tom Clancy. Themes of survival, betrayal, fear,
                and relentless momentum.
                """;
            case "romance" -> """
                Romance novels focused on love stories, emotional relationships,
                attraction and chemistry between characters, heartbreak and reunion,
                happily ever after endings. Authors like Nicholas Sparks, Nora Roberts,
                Jane Austen, Colleen Hoover. Themes of love, vulnerability, connection,
                desire, and emotional intimacy.
                """;
            case "horror" -> """
                Horror novels designed to frighten, unsettle, and disturb through
                supernatural entities, psychological dread, monsters, haunted places,
                existential terror, and the unknown. Authors like Stephen King,
                Shirley Jackson, H.P. Lovecraft, Paul Tremblay. Themes of fear,
                mortality, the uncanny, and darkness within human nature.
                """;
            case "historical-fiction" -> """
                Historical fiction set in specific past eras with accurate period detail,
                real historical events and figures woven with fictional characters,
                exploration of how people lived in other times. Authors like Hilary Mantel,
                Ken Follett, Anthony Burgess, Colm Tóibín. Themes of the past shaping
                the present, human resilience across time, and historical truth.
                """;
            case "literary-fiction" -> """
                Literary fiction prioritising prose style, complex characters, psychological
                depth, ambiguous morality, and artistic innovation over plot. Authors like
                Kazuo Ishiguro, Toni Morrison, Gabriel García Márquez, Donna Tartt.
                Themes of identity, memory, loss, consciousness, and the human condition
                explored with nuance and craft.
                """;
            case "biography" -> """
                Biographies and memoirs documenting real lives — their struggles,
                achievements, relationships, and legacies. Subjects range from political
                leaders and artists to scientists and ordinary people with extraordinary
                stories. Themes of ambition, perseverance, identity, and what makes
                a life meaningful.
                """;
            case "self-help" -> """
                Self-help and personal development books offering practical advice,
                psychological frameworks, productivity systems, habit building, mindset
                shifts, and life improvement strategies. Authors like James Clear,
                Dale Carnegie, Brené Brown, Ryan Holiday. Themes of growth, discipline,
                resilience, and living intentionally.
                """;
            case "science" -> """
                Popular science books making complex scientific ideas accessible —
                physics, biology, chemistry, neuroscience, evolution, cosmology.
                Authors like Richard Dawkins, Carl Sagan, Stephen Hawking, Mary Roach.
                Themes of curiosity, discovery, the nature of reality, and humanity's
                place in the universe.
                """;
            case "technology" -> """
                Books about technology, computing, the internet, artificial intelligence,
                software, startups, digital culture, and the people who build the future.
                Authors like Walter Isaacson, Steven Levy, Shoshana Zuboff. Themes of
                innovation, disruption, power, ethics, and technology's effect on society.
                """;
            case "philosophy" -> """
                Philosophy books exploring ethics, metaphysics, epistemology, logic,
                political philosophy, existentialism, and the fundamental questions
                of existence and meaning. Authors like Plato, Nietzsche, Simone de Beauvoir,
                Albert Camus. Themes of truth, justice, free will, consciousness, and
                how to live a good life.
                """;
            case "psychology" -> """
                Psychology books exploring human behaviour, cognition, emotion, mental
                health, social dynamics, decision making, and the unconscious mind.
                Authors like Daniel Kahneman, Viktor Frankl, Robert Cialdini, Oliver Sacks.
                Themes of what drives us, why we think the way we do, and the science
                of the human mind.
                """;
            case "business" -> """
                Business books covering entrepreneurship, management, leadership, strategy,
                marketing, organisational culture, and building companies. Authors like
                Peter Drucker, Ben Horowitz, Phil Knight, Clayton Christensen. Themes of
                ambition, execution, failure and recovery, and what makes organisations succeed.
                """;
            case "economics" -> """
                Economics books explaining markets, incentives, inequality, global trade,
                behavioural economics, monetary policy, and how economies shape societies.
                Authors like Milton Friedman, Thomas Piketty, Ha-Joon Chang, Daron Acemoglu.
                Themes of scarcity, value, power, and the systems that govern how resources
                are distributed.
                """;
            case "history" -> """
                History books examining past events, civilisations, wars, revolutions,
                empires, and the forces that shaped the modern world. Authors like
                Yuval Noah Harari, Barbara Tuchman, Robert Caro, Simon Schama. Themes of
                power, change, cause and consequence, and lessons the past offers the present.
                """;
            case "politics" -> """
                Political books covering governance, ideology, democracy, authoritarianism,
                power, social movements, diplomacy, and the structure of political systems.
                Authors like Hannah Arendt, George Orwell, Francis Fukuyama, Noam Chomsky.
                Themes of justice, liberty, power, and how societies organise collective life.
                """;
            case "travel" -> """
                Travel writing and adventure books capturing journeys to distant places,
                cultural encounters, landscapes, personal transformation through movement,
                and the experience of being a stranger somewhere new. Authors like
                Bill Bryson, Bruce Chatwin, Rolf Potts. Themes of discovery, curiosity,
                displacement, and the world's extraordinary variety.
                """;
            case "comics-graphic-novels" -> """
                Comics and graphic novels combining sequential art with storytelling —
                superhero epics, literary graphic memoirs, manga, and illustrated fiction.
                Authors like Art Spiegelman, Alan Moore, Marjane Satrapi, Neil Gaiman.
                Themes span all genres — the medium prioritises visual narrative,
                panel composition, and the interplay of image and text.
                """;
            // fallback for any genre added to the DB later without a case here
            default -> String.format("""
                Books in the %s genre. Typical themes, subjects, and narrative styles
                associated with %s literature, including representative authors and works
                that define this category.
                """, name, name);
        };
    }
}
