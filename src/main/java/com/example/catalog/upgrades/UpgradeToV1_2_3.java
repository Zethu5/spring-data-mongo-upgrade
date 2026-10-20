package com.example.catalog.upgrades;

import java.util.Map;
import com.example.catalog.Book;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.AggregationUpdate;
import org.springframework.data.mongodb.core.aggregation.BooleanOperators;
import org.springframework.data.mongodb.core.aggregation.ComparisonOperators;
import org.springframework.data.mongodb.core.aggregation.ConvertOperators;
import org.springframework.data.mongodb.core.aggregation.LiteralOperators;
import org.springframework.data.mongodb.core.aggregation.ObjectOperators;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

import static org.springframework.data.mongodb.core.query.Criteria.where;
import static org.springframework.data.mongodb.core.query.Query.query;

public class UpgradeToV1_2_3 implements DatabaseUpgrade {
    @Override
    public String version() {
        return "1.2.3";
    }

    @Override
    public void apply(MongoTemplate mongo) {
        Criteria safePages = losslessPages();
        Query pagesToConvert = query(where(Book.PAGES).exists(true)).addCriteria(safePages);
        AggregationUpdate convertPages = AggregationUpdate
                .update()
                .set(Book.PAGES)
                .toValue(ConvertOperators.valueOf(Book.PAGES).convertToLong());
        mongo.updateMulti(pagesToConvert, convertPages, Book.COLLECTION);

        Query authorsToConvert = query(where(Book.AUTHOR).exists(true).and(Book.AUTHOR_NAME).exists(false))
                .addCriteria(new Criteria().orOperator(where(Book.PAGES).exists(false), safePages));

        ObjectOperators.SetField authorObject = ObjectOperators.SetField.field(Book.Author.NAME)
                .input(LiteralOperators.Literal.asLiteral(Map.of())).toValueOf(Book.AUTHOR);

        AggregationUpdate convertAuthors = AggregationUpdate.update().set(Book.AUTHOR).toValue(authorObject);
        mongo.updateMulti(authorsToConvert, convertAuthors, Book.COLLECTION);
    }

    private static Criteria losslessPages() {
        // Failed conversions return a marker used only for filtering, never for stored data.
        ConvertOperators.Convert converted = ConvertOperators.valueOf(Book.PAGES).convertTo("long")
                .onErrorReturn(false).onNullReturn(false);
        ConvertOperators.Convert originalText = ConvertOperators.valueOf(Book.PAGES).convertTo("string").onErrorReturn(null);
        ConvertOperators.ToString convertedText = ConvertOperators.valueOf(converted).convertToString();
        return Criteria.expr(BooleanOperators.And.and(
                ComparisonOperators.valueOf(converted).notEqualToValue(false),
                ComparisonOperators.valueOf(originalText).equalTo(convertedText)));
    }
}
