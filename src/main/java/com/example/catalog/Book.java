package com.example.catalog;

import lombok.Value;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Value
@Document(Book.COLLECTION)
public class Book {
    @Id
    String isbn;
    String title;
    Author author;
    int publicationYear;
    long pages;
    boolean available;

    public static final String COLLECTION = "books";
    public static final String ID = "_id";
    public static final String TITLE = "title";
    public static final String AUTHOR = "author";
    public static final String AUTHOR_NAME = AUTHOR + "." + Author.NAME;
    public static final String PUBLICATION_YEAR = "publicationYear";
    public static final String PAGES = "pages";
    public static final String AVAILABLE = "available";

    // Keep historical field names stable for released upgrades.
    public static final String PUBLISHED_YEAR = "publishedYear";
    public static final String GENRES = "genres";

    @Value
    public static class Author {
        String name;

        public static final String NAME = "name";
    }
}
