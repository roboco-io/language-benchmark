package io.realworld.article;

import java.security.SecureRandom;
import java.text.Normalizer;
import java.util.Locale;

/** Builds URL-safe slugs from article titles. */
final class Slugs {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int MAX_BASE_LENGTH = 80;

    private Slugs() {
    }

    static String base(String title) {
        String ascii = Normalizer.normalize(title, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        String slug = ascii.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (slug.length() > MAX_BASE_LENGTH) {
            slug = slug.substring(0, MAX_BASE_LENGTH).replaceAll("-+$", "");
        }
        return slug.isEmpty() ? "article" : slug;
    }

    /** A short random suffix used to keep slugs unique when titles repeat. */
    static String suffix() {
        return Long.toString(RANDOM.nextLong() >>> 1, 36).substring(0, 6);
    }
}
