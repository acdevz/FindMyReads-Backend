package org.fmr.findmyreads.utils;

/**
 * Pure static vector math — no Spring dependencies, fully unit-testable.
 * All methods operate on float[] assumed to be the same dimension.
 * Callers are responsible for dimension validation before calling.
 */
public final class VectorMathUtil {

    private VectorMathUtil() {}

    // -------------------------------------------------------------------------
    // Centroid update
    // -------------------------------------------------------------------------

    /**
     * Incremental weighted centroid update.
     * Formula:
     *   newCentroid = (oldCentroid * oldWeightSum + newVector * weight) / (oldWeightSum + weight)
     * Used every time a user rates a book:
     *   oldCentroid   = user.profileVector  (null on first rating)
     *   oldWeightSum  = user.booksRatedCount (sum of all past ratings, not row count)
     *   newVector     = book.bookVector
     *   weight        = rating (1–5)
     *
     * @param oldCentroid  current profile vector, or null if this is the first rating
     * @param oldWeightSum sum of all rating weights applied so far
     * @param newVector    the new book's embedding vector
     * @param weight       the rating given (acts as the weight)
     * @return updated centroid vector
     */
    public static float[] updateCentroid(
            float[] oldCentroid,
            int oldWeightSum,
            float[] newVector,
            int weight) {

        if (oldCentroid == null || oldWeightSum == 0) {
            // first book — centroid is just the new vector itself
            return newVector.clone();
        }

        int dim = newVector.length;
        float[] result = new float[dim];
        float totalWeight = oldWeightSum + weight;

        for (int i = 0; i < dim; i++) {
            result[i] = (oldCentroid[i] * oldWeightSum + newVector[i] * weight) / totalWeight;
        }
        return result;
    }

    // -------------------------------------------------------------------------
    // Deviation blend (the "exploration knob")
    // -------------------------------------------------------------------------

    /**
     * Linear interpolation between a user's taste vector and a random direction.
     *   result = (1 - alpha) * userVector + alpha * randomVector
     * alpha = 0.0 → pure taste match
     * alpha = 1.0 → fully random direction (still in the same vector space)
     * The randomVector should be unit-normalised before passing in
     * so the blend stays in a meaningful region of the embedding space.
     *
     * @param userVector   user's current profile_vector
     * @param randomVector a random unit vector of same dimension
     * @param alpha        deviation knob value (0.0–1.0)
     */
    public static float[] deviationBlend(
            float[] userVector,
            float[] randomVector,
            float alpha) {

        int dim = userVector.length;
        float[] result = new float[dim];

        for (int i = 0; i < dim; i++) {
            result[i] = (1 - alpha) * userVector[i] + alpha * randomVector[i];
        }
        return result;
    }

    // -------------------------------------------------------------------------
    // Weighted average (used for genre prototype seeding)
    // -------------------------------------------------------------------------

    /**
     * Compute a weighted average of multiple vectors.
     *
     * Used by OnboardingService to seed the initial profile_vector from
     * the user's selected genre prototype vectors + their preference weights.
     *
     * @param vectors float[][] — each row is one vector
     * @param weights int[]     — parallel array of integer weights
     * @return weighted centroid vector, or null if vectors array is empty
     */
    public static float[] weightedAverage(float[][] vectors, int[] weights) {
        if (vectors == null || vectors.length == 0) return null;

        int dim = vectors[0].length;
        float[] result = new float[dim];
        int totalWeight = 0;

        for (int i = 0; i < vectors.length; i++) {
            totalWeight += weights[i];
            for (int j = 0; j < dim; j++) {
                result[j] += vectors[i][j] * weights[i];
            }
        }

        if (totalWeight == 0) return null;

        for (int j = 0; j < dim; j++) {
            result[j] /= totalWeight;
        }
        return result;
    }

    // -------------------------------------------------------------------------
    // Normalisation
    // -------------------------------------------------------------------------

    /**
     * L2-normalise a vector in place (unit vector).
     * Required before deviationBlend so the random direction
     * doesn't dominate due to magnitude differences.
     *
     * @param vector input vector, modified in place
     * @return the same array, normalised
     */
    public static float[] normalise(float[] vector) {
        double magnitude = 0.0;
        for (float v : vector) {
            magnitude += (double) v * v;
        }
        magnitude = Math.sqrt(magnitude);
        if (magnitude == 0) return vector;

        for (int i = 0; i < vector.length; i++) {
            vector[i] /= (float) magnitude;
        }
        return vector;
    }

    // -------------------------------------------------------------------------
    // Vector → PG literal string
    // -------------------------------------------------------------------------

    /**
     * Converts float[] to PostgreSQL vector literal "[0.1,0.2,...]".
     * Used when passing a query vector directly to native @Query parameters.
     *
     * Not a duplicate of VectorConverter — that handles @Entity field
     * persistence automatically. This is for explicit native query params.
     */
    public static String toPgLiteral(float[] vector) {
        if (vector == null) return null;
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            sb.append(vector[i]);
            if (i < vector.length - 1) sb.append(",");
        }
        sb.append("]");
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Random unit vector (for deviation)
    // -------------------------------------------------------------------------

    /**
     * Generates a random unit vector of the given dimension.
     * Uses Gaussian distribution so the direction is uniformly distributed
     * on the hypersphere — simple uniform random per dimension is NOT uniform
     * on the sphere surface.
     *
     * @param dim vector dimension (768 for text-embedding-004)
     */
    public static float[] randomUnitVector(int dim) {
        float[] vec = new float[dim];
        java.util.Random rng = new java.util.Random();
        for (int i = 0; i < dim; i++) {
            vec[i] = (float) rng.nextGaussian();
        }
        return normalise(vec);
    }

    // -------------------------------------------------------------------------
    // Cosine similarity
    // -------------------------------------------------------------------------

    /**
     * Cosine similarity between two vectors.
     * Returns a value in [-1, 1] where:
     *   1.0  = identical direction (perfect taste match)
     *   0.0  = orthogonal (no relationship)
     *  -1.0  = opposite directions
     *
     * Note on pgvector: the <=> operator computes cosine DISTANCE = 1 - similarity.
     * So pgvector ORDER BY vec <=> query ASC gives closest first (lowest distance).
     * This method returns similarity directly — higher = better match.
     *
     * Used by:
     *   TasteProfileService    — project profile_vector onto genre prototype_vectors
     *   ScanGenreService       — not needed here, genre breakdown uses count weighting
     */
    public static float cosineSimilarity(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) return 0f;

        double dot  = 0.0;
        double magA = 0.0;
        double magB = 0.0;

        for (int i = 0; i < a.length; i++) {
            dot  += (double) a[i] * b[i];
            magA += (double) a[i] * a[i];
            magB += (double) b[i] * b[i];
        }

        double denom = Math.sqrt(magA) * Math.sqrt(magB);
        if (denom == 0) return 0f;

        return (float) (dot / denom);
    }

    /**
     * Cosine distance — complement of similarity.
     * Matches pgvector's <=> operator output directly.
     * distance = 1 - similarity
     */
    public static float cosineDistance(float[] a, float[] b) {
        return 1f - cosineSimilarity(a, b);
    }
}
