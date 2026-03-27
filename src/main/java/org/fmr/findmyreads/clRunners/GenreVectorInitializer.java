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
     * Builds a rich descriptive prompt for a genre or sub-genre.
     * The prompt describes the genre's themes, tone, typical subjects, and
     * what readers enjoy about it — giving the embedding model enough context
     * to place the vector accurately in semantic space.
     */
    private String buildGenrePrompt(String slug, String name) {
        return switch (slug) {
            // ==========================================
            // 1. FICTION & LITERATURE (Parent & Children)
            // ==========================================
            case "fiction" -> """
                General fiction and literature encompassing the broad spectrum of human storytelling. 
                Focuses on character development, relationships, societal observations, and narrative craft. 
                Encompasses everything from historical epics and romantic bonds to coming-of-age tales 
                and profound literary explorations of the human condition.
                """;
            case "historical-fiction" -> """
                Historical fiction set in specific past eras with accurate period detail. 
                Real historical events and figures woven with fictional characters. Exploration of how 
                people lived in other times. Authors like Hilary Mantel, Ken Follett, Anthony Doerr. 
                Themes of the past shaping the present, human resilience across time, and historical truth.
                """;
            case "romance" -> """
                Romance novels focused on love stories, emotional relationships, attraction, chemistry, 
                heartbreak, and happily-ever-after endings. Authors like Emily Henry, Nora Roberts, 
                Jane Austen, Nicholas Sparks. Themes of intimacy, vulnerability, passionate connection, 
                desire, and overcoming obstacles to be together.
                """;
            case "literary-fiction" -> """
                Literary fiction prioritizing prose style, complex characters, psychological depth, 
                ambiguous morality, and artistic innovation over plot. Authors like Kazuo Ishiguro, 
                Toni Morrison, Gabriel García Márquez, Donna Tartt. Themes of identity, memory, 
                loss, consciousness, and nuanced explorations of society.
                """;
            case "young-adult" -> """
                Young Adult (YA) fiction focusing on the teenage and adolescent experience. 
                Coming-of-age narratives, first love, identity formation, rebellion against authority, 
                and finding one's place in the world. Authors like John Green, Angie Thomas, Suzanne Collins. 
                Themes of angst, intense emotion, discovery, and transitioning to adulthood.
                """;

            // ==========================================
            // 2. SCI-FI & FANTASY (Parent & Children)
            // ==========================================
            case "sci-fi-fantasy" -> """
                Speculative fiction encompassing both science fiction and fantasy. 
                Defined by imaginative world-building, magical systems, advanced futuristic technology, 
                mythical creatures, and epic scale. Books that transport the reader beyond the boundaries 
                of mundane reality into realms of magic or the far future.
                """;
            case "science-fiction" -> """
                Science fiction books exploring space exploration, futuristic technology, artificial intelligence, 
                alien civilizations, and the impact of science on humanity. Authors like Isaac Asimov, 
                Arthur C. Clarke, Frank Herbert, Ursula K. Le Guin. Themes of humanity's future, 
                technological progress, space operas, and cosmic wonder.
                """;
            case "epic-fantasy" -> """
                High and epic fantasy novels featuring magic systems, mythical creatures, epic quests, 
                and massive world-building with invented histories and cultures. Authors like J.R.R. Tolkien, 
                Brandon Sanderson, George R.R. Martin, Robert Jordan. Themes of good versus evil, destiny, 
                swords and sorcery, and sprawling secondary worlds.
                """;
            case "dystopian" -> """
                Dystopian and cyberpunk fiction exploring oppressive societal control, apocalyptic aftermaths, 
                totalitarian governments, and high-tech/low-life scenarios. Authors like George Orwell, 
                Margaret Atwood, William Gibson, Neal Stephenson. Themes of survival, rebellion, 
                loss of freedom, corporate dystopias, and societal collapse.
                """;
            case "paranormal" -> """
                Paranormal and urban fantasy set in contemporary worlds where magic, supernatural entities, 
                vampires, werewolves, and demons exist secretly alongside humans. Authors like Neil Gaiman, 
                Patricia Briggs, Jim Butcher, Anne Rice. Themes of hidden worlds, occult mysteries, 
                and the supernatural bleeding into everyday life.
                """;

            // ==========================================
            // 3. MYSTERY & THRILLER (Parent & Children)
            // ==========================================
            case "mystery-thriller" -> """
                Mystery, thriller, and suspense fiction driven by high stakes, tension, and the quest for truth. 
                Involves crime solving, espionage, psychological manipulation, and pacing designed to keep 
                readers on the edge of their seats. The narrative is propelled by dark secrets and danger.
                """;
            case "crime-detective" -> """
                Crime and detective fiction centered on solving murders, police procedurals, forensics, 
                and gritty criminal underworlds. Authors like Michael Connelly, Ian Rankin, Arthur Conan Doyle, 
                Tana French. Themes of justice, corruption, the psychology of criminals, and the methodical 
                pursuit of the truth.
                """;
            case "psychological-thriller" -> """
                Psychological thrillers driven by mental tension, unreliable narrators, paranoia, 
                domestic deceit, and mind games. Authors like Gillian Flynn, Paula Hawkins, Alex Michaelides. 
                Themes of gaslighting, hidden trauma, betrayal by loved ones, and the terrifying unknown 
                within the human mind.
                """;
            case "suspense-espionage" -> """
                Suspense and espionage thrillers featuring spies, assassins, global conspiracies, 
                geopolitical stakes, and relentless action. Authors like John le Carré, Tom Clancy, 
                Robert Ludlum, Daniel Silva. Themes of loyalty, betrayal, covert operations, survival, 
                and fast-paced, life-or-death momentum.
                """;
            case "cozy-mystery" -> """
                Cozy mysteries featuring amateur sleuths, small close-knit communities, lighthearted tones, 
                and puzzles to solve without explicit violence or gore. Authors like Agatha Christie, 
                Richard Osman, Louise Penny. Themes of community secrets, eccentric characters, 
                intellectual puzzle-solving, and restorative justice.
                """;

            // ==========================================
            // 4. BUSINESS & ECONOMICS (Parent & Children)
            // ==========================================
            case "business-economics" -> """
                Non-fiction regarding commerce, the global economy, corporate strategy, and financial systems. 
                Explores how wealth is created, how organizations are built and managed, the principles of 
                markets, and the philosophies of money and leadership.
                """;
            case "economics-finance" -> """
                Macro and micro economics, global trade, monetary policy, behavioral economics, and 
                how markets shape societies. Authors like Thomas Piketty, Daniel Kahneman, Steven Levitt, 
                Milton Friedman. Themes of scarcity, incentives, systemic inequality, and the mathematical 
                forces governing resource distribution.
                """;
            case "entrepreneurship" -> """
                Books on startups, innovation, venture capital, building companies from scratch, 
                and disruptive technology. Authors like Peter Thiel, Ben Horowitz, Eric Ries, Phil Knight. 
                Themes of risk-taking, product development, scaling businesses, overcoming failure, 
                and the visionary pursuit of creating something new.
                """;
            case "management-leadership" -> """
                Organizational psychology, team leadership, corporate culture, executive strategy, 
                and human resources. Authors like Simon Sinek, Brené Brown, Peter Drucker, Jim Collins. 
                Themes of inspiring teams, navigating workplace dynamics, ethical leadership, 
                and building enduring, effective organizations.
                """;
            case "personal-finance" -> """
                Practical advice on wealth building, investing, budgeting, retiring early, and achieving 
                financial independence. Authors like Morgan Housel, Dave Ramsey, Robert Kiyosaki, JL Collins. 
                Themes of frugality, compound interest, stock market fundamentals, overcoming debt, 
                and the psychological relationship with money.
                """;

            // ==========================================
            // 5. HISTORY & BIOGRAPHY (Parent & Children)
            // ==========================================
            case "history-biography" -> """
                Non-fiction documenting the real events and people that shaped our world. 
                Encompasses the sweeping narratives of civilizations, the intimate memoirs of individuals, 
                the harsh realities of war, and the complex mechanics of political power.
                """;
            case "world-history" -> """
                Sweeping examinations of human history, ancient civilizations, the rise and fall of empires, 
                and the cultural forces that shaped the modern world. Authors like Yuval Noah Harari, 
                Jared Diamond, Mary Beard, Will Durant. Themes of human evolution, societal collapse, 
                progress, and the macro-trends of human existence.
                """;
            case "memoir-biography" -> """
                Biographies and memoirs documenting real lives, personal struggles, and remarkable achievements. 
                Subjects range from famous historical figures to ordinary people with extraordinary stories. 
                Authors like Walter Isaacson, Michelle Obama, Ron Chernow, Tara Westover. Themes of resilience, 
                identity, legacy, and the deeply personal human experience.
                """;
            case "politics" -> """
                Political science, governance, ideology, democracy, authoritarianism, foreign policy, 
                and social movements. Authors like Hannah Arendt, Francis Fukuyama, Noam Chomsky, 
                Anne Applebaum. Themes of justice, liberty, the mechanics of power, propaganda, 
                and how societies organize collective life.
                """;
            case "military-history" -> """
                Detailed accounts of wars, battles, military strategy, generals, and the experiences of soldiers. 
                Covers conflicts from antiquity to modern global warfare. Authors like Antony Beevor, 
                John Keegan, Rick Atkinson, Max Hastings. Themes of tactical genius, the horrors of combat, 
                camaraderie, and the geopolitical consequences of war.
                """;

            // ==========================================
            // 6. SCIENCE, MIND & BODY (Parent & Children)
            // ==========================================
            case "science-lifestyle" -> """
                Non-fiction exploring the universe, the human mind, and personal growth. 
                Bridges the hard facts of physics and biology with the introspective realms of psychology, 
                philosophy, and actionable self-improvement frameworks.
                """;
            case "psychology-self-help" -> """
                Self-help, personal development, and pop psychology. Frameworks for habit building, 
                productivity, overcoming trauma, and mindset shifts. Authors like James Clear, 
                Malcolm Gladwell, Viktor Frankl, Mark Manson. Themes of discipline, cognitive biases, 
                resilience, mental health, and living intentionally.
                """;
            case "tech-computers" -> """
                Books about computer science, artificial intelligence, algorithms, the internet, software, 
                and digital culture. Authors like Shoshana Zuboff, Jaron Lanier, Kevin Kelly, Cathy O'Neil. 
                Themes of digital disruption, data privacy, the ethics of AI, tech monopolies, 
                and how the internet is rewiring society.
                """;
            case "hard-science" -> """
                Popular science making complex ideas accessible: physics, astronomy, biology, chemistry, 
                neuroscience, and genetics. Authors like Carl Sagan, Stephen Hawking, Richard Dawkins, 
                Carlo Rovelli. Themes of cosmic discovery, the nature of reality, evolution, quantum mechanics, 
                and humanity's place in the universe.
                """;
            case "philosophy-sociology" -> """
                Explorations of ethics, metaphysics, societal structures, existentialism, and the fundamental 
                questions of meaning. Authors like Marcus Aurelius, Friedrich Nietzsche, Michel Foucault, 
                Alain de Botton. Themes of truth, free will, stoicism, societal norms, consciousness, 
                and how to live a good life.
                """;

            // ==========================================
            // 7. POETRY & VERSE
            // ==========================================
            case "poetry" -> """
                Poetry and verse encompassing rhythmic, lyrical, and evocative writing. 
                Explores deep human emotion, nature, love, and tragedy through carefully crafted 
                stanzas, metaphors, and meter. Encompasses everything from ancient epic narratives 
                to modern free verse and spoken word. Authors like Maya Angelou, Walt Whitman, 
                Pablo Neruda, and Emily Dickinson.
                """;
            case "classic-poetry" -> """
                Classic and historical poetry featuring traditional forms, sonnets, structured meter, 
                and rhyme schemes. Explores romanticism, gothic themes, nature, and the human condition 
                through formal verse. Authors like William Shakespeare, John Keats, Edgar Allan Poe, 
                Sylvia Plath, and Rumi. Themes of timeless love, mortality, and existential beauty.
                """;
            case "contemporary-poetry" -> """
                Contemporary and modern poetry utilizing free verse, breaking traditional structural rules. 
                Focuses on raw emotion, identity, trauma, healing, minimalism, and the complexities 
                of modern life. Authors like Rupi Kaur, Mary Oliver, Ocean Vuong, and Amanda Gorman. 
                Themes of introspection, mental health, societal observation, and personal rebirth.
                """;
            case "epic-poetry" -> """
                Epic and narrative poetry featuring sweeping, book-length verses that tell grand stories. 
                Focuses on mythic tales, gods, heroes, historical journeys, and massive battles told in 
                elevated, rhythmic language. Authors like Homer, Virgil, Dante Alighieri, and John Milton. 
                Themes of classical mythology, divine intervention, heroism, and epic quests.
                """;
            case "spoken-word" -> """
                Spoken word and slam poetry written specifically for vocal performance. 
                Relies heavily on rhythm, wordplay, and passionate delivery. Often deals with immediate, 
                pressing themes like social justice, systemic inequality, politics, and raw personal truth. 
                Authors like Gil Scott-Heron, Sarah Kay, Saul Williams, and Rudy Francisco.
                """;

            // ==========================================
            // FALLBACK
            // ==========================================
            default -> String.format("""
                Books belonging to the %s genre. Typical themes, subjects, and narrative styles
                associated with %s literature, including representative authors and works
                that define this category's unique aesthetic and conceptual space.
                """, name, name);
        };
    }
}
