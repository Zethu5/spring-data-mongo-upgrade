package com.example.catalog;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BookFieldNamesTest {
    @Test
    void releasedCollectionAndLegacyFieldNamesRemainStable() {
        assertThat(Book.COLLECTION).isEqualTo("books");
        assertThat(Book.ID).isEqualTo("_id");
        assertThat(Book.PUBLISHED_YEAR).isEqualTo("publishedYear");
        assertThat(Book.GENRES).isEqualTo("genres");
        assertThat(Book.AUTHOR_NAME).isEqualTo("author.name");
    }
}
