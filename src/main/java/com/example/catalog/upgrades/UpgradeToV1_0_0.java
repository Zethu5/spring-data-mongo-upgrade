package com.example.catalog.upgrades;

import java.util.List;
import com.example.catalog.Book;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Update;

import static org.springframework.data.mongodb.core.query.Criteria.where;
import static org.springframework.data.mongodb.core.query.Query.query;

public class UpgradeToV1_0_0 implements DatabaseUpgrade {
    @Override
    public String version() {
        return "1.0.0";
    }

    @Override
    public void apply(MongoTemplate mongo) {
        seed(mongo, "9780132350884", "Clean Code", "Robert C. Martin", 2008, 464,
                "Programming", "Software Engineering");
        seed(mongo, "9780134685991", "Effective Java", "Joshua Bloch", 2018, 416,
                "Programming", "Java");
        seed(mongo, "9780201633610", "Design Patterns", "Erich Gamma et al.", 1994, 395,
                "Programming", "Software Engineering");
    }

    private static void seed(MongoTemplate mongo, String isbn, String title, String author,
                             int year, int pages, String... genres) {
        mongo.upsert(query(where(Book.ID).is(isbn)), new Update()
                .setOnInsert(Book.TITLE, title)
                .setOnInsert(Book.AUTHOR, author)
                .setOnInsert(Book.PUBLISHED_YEAR, year)
                .setOnInsert(Book.PAGES, pages)
                .setOnInsert(Book.GENRES, List.of(genres)), Book.COLLECTION);
    }
}
