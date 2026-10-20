package com.example.catalog.upgrades;

import com.example.catalog.Book;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import static org.springframework.data.mongodb.core.query.Criteria.where;
import static org.springframework.data.mongodb.core.query.Query.query;

public class UpgradeToV2_0_1 implements DatabaseUpgrade {
    @Override
    public String version() {
        return "2.0.1";
    }

    @Override
    public void apply(MongoTemplate mongo) {
        Criteria oldYear = where(Book.PUBLISHED_YEAR).exists(true).and(Book.PUBLICATION_YEAR).exists(false);
        Criteria newYear = where(Book.PUBLICATION_YEAR).exists(true).and(Book.PUBLISHED_YEAR).exists(false);
        Criteria relevantRecords = new Criteria().orOperator(oldYear, newYear);

        Update renameAndClean = new Update().rename(Book.PUBLISHED_YEAR, Book.PUBLICATION_YEAR).unset(Book.GENRES);
        mongo.updateMulti(query(relevantRecords), renameAndClean, Book.COLLECTION);

        Query missingAvailability = query(new Criteria().andOperator(relevantRecords, where(Book.AVAILABLE).exists(false)));
        mongo.updateMulti(missingAvailability, new Update().set(Book.AVAILABLE, true), Book.COLLECTION);
    }
}
