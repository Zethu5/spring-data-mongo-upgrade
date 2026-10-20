package com.example.catalog;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import com.example.catalog.upgrades.*;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.mongodb.core.MongoTemplate;

import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "MONGODB_INTEGRATION_TESTS", matches = "true")
class CatalogIntegrationTest {
    private MongoClient client;
    private MongoTemplate mongo;
    private String uri;

    @BeforeEach
    void database() {
        String database = "catalog_test_" + UUID.randomUUID().toString().replace("-", "");
        uri = "mongodb://localhost:27017/" + database;
        client = MongoClients.create(uri);
        mongo = new MongoTemplate(client, database);
    }

    @AfterEach
    void cleanUp() {
        mongo.getDb().drop();
        client.close();
    }

    private ConfigurableApplicationContext start() {
        return new SpringApplicationBuilder(CatalogApplication.class).run(
                "--spring.data.mongodb.uri=" + uri,
                "--spring.main.banner-mode=off", "--logging.level.root=OFF");
    }

    private List<Document> documents() {
        return mongo.getCollection(Book.COLLECTION).find().sort(new Document(Book.ID, 1)).into(new ArrayList<>());
    }

    @Test
    void startupAppliesAllUpgradesAndRepeatedStartupPreservesExistingBooks() {
        try (ConfigurableApplicationContext context = start()) {
            BookRepository books = context.getBean(BookRepository.class);
            assertThat(books.count()).isEqualTo(3);
            assertThat(books.findByAuthorName("Joshua Bloch")).hasSize(1);
            Book book = books.findById("9780134685991").orElseThrow();
            assertThat(book.getPages()).isEqualTo(416L);
            assertThat(book.getPublicationYear()).isEqualTo(2018);
            assertThat(book.isAvailable()).isTrue();
            assertThat(documents()).allSatisfy(document -> {
                assertThat(document.get(Book.PAGES)).isInstanceOf(Long.class);
                assertThat(document.get(Book.AUTHOR)).isInstanceOf(Document.class);
                assertThat(document).doesNotContainKeys(Book.PUBLISHED_YEAR, Book.GENRES);
            });
            books.save(new Book(book.getIsbn(), "Edited title", book.getAuthor(),
                    book.getPublicationYear(), book.getPages(), false));
        }
        List<Document> before = documents();
        try (ConfigurableApplicationContext ignored = start()) {
            assertThat(documents()).isEqualTo(before);
            assertThat(mongo.getCollectionNames()).containsExactlyInAnyOrder(Book.COLLECTION, "databaseUpgradeLock");
            assertThat(mongo.getCollection("databaseUpgradeLock").countDocuments()).isZero();
        }
    }

    @Test
    void existingUpgradeLockBlocksUpgradesWithoutRemovingItsOwner() {
        Document lock = new Document(Book.ID, "catalog").append("owner", "another-process");
        mongo.getCollection("databaseUpgradeLock").insertOne(lock);
        assertThatThrownBy(() -> new UpgradeRunner(mongo).run(null))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Upgrade lock exists");
        assertThat(documents()).isEmpty();
        assertThat(mongo.getCollection("databaseUpgradeLock").find().into(new ArrayList<>()))
                .containsExactly(lock);
    }

    @Test
    void databaseFailureReleasesLockAndAllowsRetryAfterFixingTheCause() {
        mongo.executeCommand(new Document("create", Book.COLLECTION)
                .append("validator", new Document(Book.TITLE, "blocked")));
        assertThatThrownBy(() -> new UpgradeRunner(mongo).run(null))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(mongo.getCollection("databaseUpgradeLock").countDocuments()).isZero();
        mongo.executeCommand(new Document("collMod", Book.COLLECTION).append("validator", new Document()));
        new UpgradeRunner(mongo).run(null);
        assertThat(documents()).hasSize(3);
        assertThat(mongo.getCollection("databaseUpgradeLock").countDocuments()).isZero();
    }

    @Test
    void repeatedStartupRestoresDeletedSampleBooks() {
        try (ConfigurableApplicationContext context = start()) {
            context.getBean(BookRepository.class).deleteById("9780201633610");
        }
        try (ConfigurableApplicationContext context = start()) {
            assertThat(context.getBean(BookRepository.class).count()).isEqualTo(3);
            assertThat(context.getBean(BookRepository.class).findById("9780201633610")).isPresent();
        }
    }

    @Test
    void databaseDoesNotEnforceDocumentValidationOnLaterWrites() {
        try (ConfigurableApplicationContext context = start()) {
            BookRepository books = context.getBean(BookRepository.class);
            Book book = new Book("9780000000000", "Unvalidated", new Book.Author("Author"), 2020, 0L, true);
            books.save(book);
            assertThat(books.findById(book.getIsbn())).contains(book);
        }
    }

    @Test
    void upgradesExistingDataWithoutLosingUnrelatedFields() {
        new UpgradeToV1_0_0().apply(mongo);
        mongo.getCollection(Book.COLLECTION).updateOne(new Document(Book.ID, "9780134685991"),
                new Document("$set", new Document(Book.TITLE, "User title")
                        .append(Book.AVAILABLE, false).append("note", "Keep this")));
        try (ConfigurableApplicationContext context = start()) {
            Book book = context.getBean(BookRepository.class).findById("9780134685991").orElseThrow();
            assertThat(book.getTitle()).isEqualTo("User title");
            assertThat(book.isAvailable()).isFalse();
            assertThat(mongo.getCollection(Book.COLLECTION).find(new Document(Book.ID, book.getIsbn())).first())
                    .containsEntry("note", "Keep this");
        }
    }

    @Test
    void eachEntireReleaseCanBeRepeatedBeforeTheNextRelease() {
        new UpgradeToV1_0_0().apply(mongo);
        new UpgradeToV1_0_0().apply(mongo);
        new UpgradeToV1_2_3().apply(mongo);
        new UpgradeToV1_2_3().apply(mongo);
        new UpgradeToV2_0_1().apply(mongo);
        List<Document> before = documents();
        new UpgradeToV2_0_1().apply(mongo);
        assertThat(documents()).isEqualTo(before).hasSize(3);
    }

    @Test
    void entireV123ReleaseResumesFromMixedPrimitiveAndObjectValues() {
        new UpgradeToV1_0_0().apply(mongo);
        mongo.getCollection(Book.COLLECTION).updateOne(new Document(Book.ID, "9780134685991"),
                new Document("$set", new Document(Book.PAGES, 416L)));
        mongo.getCollection(Book.COLLECTION).updateOne(new Document(Book.ID, "9780132350884"),
                new Document("$set", new Document(Book.AUTHOR, new Document(Book.Author.NAME, "Robert C. Martin"))));
        new UpgradeToV1_2_3().apply(mongo);
        assertThat(documents()).allSatisfy(book -> {
            assertThat(book.get(Book.PAGES)).isInstanceOf(Long.class);
            assertThat(book.get(Book.AUTHOR, Document.class).get(Book.Author.NAME)).isInstanceOf(String.class);
        });
    }

    @Test
    void entireV201ReleaseResumesFromPartiallyConvertedDocuments() {
        new UpgradeToV1_0_0().apply(mongo);
        new UpgradeToV1_2_3().apply(mongo);
        mongo.getCollection(Book.COLLECTION).updateOne(new Document(Book.ID, "9780134685991"),
                new Document("$set", new Document(Book.PUBLICATION_YEAR, 2018).append(Book.AVAILABLE, false))
                        .append("$unset", new Document(Book.PUBLISHED_YEAR, "").append(Book.GENRES, "")));
        mongo.getCollection(Book.COLLECTION).updateOne(new Document(Book.ID, "9780132350884"),
                new Document("$set", new Document(Book.PUBLICATION_YEAR, 2008))
                        .append("$unset", new Document(Book.PUBLISHED_YEAR, "")));
        new UpgradeToV2_0_1().apply(mongo);
        assertThat(documents()).allSatisfy(book ->
                assertThat(book).containsKeys(Book.PUBLICATION_YEAR, Book.AVAILABLE).doesNotContainKeys(Book.PUBLISHED_YEAR, Book.GENRES));
        assertThat(mongo.getCollection(Book.COLLECTION).find(new Document(Book.ID, "9780134685991")).first())
                .containsEntry(Book.AVAILABLE, false);
    }

    @Test
    void renameCollisionSkipsOnlyThatBookAndCanBeRetriedAfterRepair() {
        new UpgradeToV1_0_0().apply(mongo);
        new UpgradeToV1_2_3().apply(mongo);
        Document id = new Document(Book.ID, "9780134685991");
        mongo.getCollection(Book.COLLECTION).updateOne(id, new Document("$set", new Document(Book.PUBLICATION_YEAR, 2025)));
        Document before = mongo.getCollection(Book.COLLECTION).find(id).first();
        try (ConfigurableApplicationContext ignored = start()) {
            assertThat(mongo.getCollection(Book.COLLECTION).find(id).first()).isEqualTo(before);
            assertThat(mongo.getCollection(Book.COLLECTION).countDocuments(new Document(Book.GENRES, new Document("$exists", false))))
                    .isEqualTo(2);
        }
        mongo.getCollection(Book.COLLECTION).updateOne(id, new Document("$unset", new Document(Book.PUBLICATION_YEAR, "")));
        try (ConfigurableApplicationContext ignored = start()) {
            assertThat(mongo.getCollection(Book.COLLECTION).find(id).first())
                    .containsEntry(Book.PUBLICATION_YEAR, 2018).doesNotContainKeys(Book.PUBLISHED_YEAR, Book.GENRES);
        }
    }

    @Test
    void existingAvailabilityIsPreservedWithoutCheckingItsType() {
        new UpgradeToV1_0_0().apply(mongo);
        new UpgradeToV1_2_3().apply(mongo);
        Document id = new Document(Book.ID, "9780134685991");
        mongo.getCollection(Book.COLLECTION).updateOne(id, new Document("$set", new Document(Book.AVAILABLE, null)));
        new UpgradeToV2_0_1().apply(mongo);
        assertThat(mongo.getCollection(Book.COLLECTION).find(id).first())
                .containsEntry(Book.AVAILABLE, null).containsEntry(Book.PUBLICATION_YEAR, 2018)
                .doesNotContainKeys(Book.PUBLISHED_YEAR, Book.GENRES);
    }

    @Test
    void missingYearSkipsThatBookWithoutDeletingItsGenres() {
        new UpgradeToV1_0_0().apply(mongo);
        new UpgradeToV1_2_3().apply(mongo);
        Document id = new Document(Book.ID, "9780134685991");
        mongo.getCollection(Book.COLLECTION).updateOne(id, new Document("$unset", new Document(Book.PUBLISHED_YEAR, "")));
        Document before = mongo.getCollection(Book.COLLECTION).find(id).first();
        new UpgradeToV2_0_1().apply(mongo);
        assertThat(mongo.getCollection(Book.COLLECTION).find(id).first()).isEqualTo(before);
        assertThat(mongo.getCollection(Book.COLLECTION).countDocuments(new Document(Book.GENRES, new Document("$exists", false))))
                .isEqualTo(2);
    }

    static java.util.stream.Stream<Arguments> pageConversionInputs() {
        return java.util.stream.Stream.of(
                Arguments.of(416, 416L), Arguments.of(416L, 416L), Arguments.of("416", 416L),
                Arguments.of(0, 0L), Arguments.of(-1, -1L), Arguments.of(Long.MIN_VALUE, Long.MIN_VALUE),
                Arguments.of(Long.MAX_VALUE, Long.MAX_VALUE));
    }

    @ParameterizedTest
    @MethodSource("pageConversionInputs")
    void mongodbConvertsPagesWithoutInputTypeGuards(Object value, long expected) {
        new UpgradeToV1_0_0().apply(mongo);
        Document id = new Document(Book.ID, "9780134685991");
        mongo.getCollection(Book.COLLECTION).updateOne(id, new Document("$set", new Document(Book.PAGES, value)));
        new UpgradeToV1_2_3().apply(mongo);
        assertThat(mongo.getCollection(Book.COLLECTION).find(id).first()).containsEntry(Book.PAGES, expected);
    }

    static java.util.stream.Stream<Arguments> skippedPageInputs() {
        return java.util.stream.Stream.of(
                Arguments.of("not a number"), Arguments.of("false"), Arguments.of("00416"),
                Arguments.of("9223372036854775808"), Arguments.of(416.75), Arguments.of(-1.75),
                Arguments.of(1.0000000000000002), Arguments.of(List.of(416)), Arguments.of(new Document()),
                Arguments.of(true), Arguments.of(false), Arguments.of((Object) null),
                Arguments.of(new org.bson.types.Decimal128(new java.math.BigDecimal("416.00000000000000000001"))));
    }

    @ParameterizedTest
    @MethodSource("skippedPageInputs")
    void unsupportedOrLossyPagesLeaveTheWholeRecordUnchangedForThisRelease(Object value) {
        new UpgradeToV1_0_0().apply(mongo);
        Document id = new Document(Book.ID, "9780134685991");
        mongo.getCollection(Book.COLLECTION).updateOne(id, new Document("$set", new Document(Book.PAGES, value)));
        Document before = mongo.getCollection(Book.COLLECTION).find(id).first();
        new UpgradeToV1_2_3().apply(mongo);
        new UpgradeToV1_2_3().apply(mongo);
        assertThat(mongo.getCollection(Book.COLLECTION).find(id).first()).isEqualTo(before);
        assertThat(mongo.getCollection(Book.COLLECTION).countDocuments(new Document(Book.PAGES, new Document("$type", "long"))
                .append(Book.AUTHOR, new Document("$type", "object")))).isEqualTo(2);
    }

    @Test
    void authorsWithoutAPagesFieldStillConvert() {
        new UpgradeToV1_0_0().apply(mongo);
        Document id = new Document(Book.ID, "9780134685991");
        mongo.getCollection(Book.COLLECTION).updateOne(id, new Document("$unset", new Document(Book.PAGES, "")));
        new UpgradeToV1_2_3().apply(mongo);
        Document book = mongo.getCollection(Book.COLLECTION).find(id).first();
        assertThat(book).doesNotContainKey(Book.PAGES);
        assertThat(book.get(Book.AUTHOR, Document.class)).containsEntry(Book.Author.NAME, "Joshua Bloch");
    }

    static java.util.stream.Stream<Arguments> authorConversionInputs() {
        return java.util.stream.Stream.of(
                Arguments.of(""), Arguments.of(List.of("Author")), Arguments.of(new Document()),
                Arguments.of(123), Arguments.of((Object) null));
    }

    @ParameterizedTest
    @MethodSource("authorConversionInputs")
    void authorValuesAreWrappedWithoutInputTypeGuards(Object value) {
        new UpgradeToV1_0_0().apply(mongo);
        Document id = new Document(Book.ID, "9780134685991");
        mongo.getCollection(Book.COLLECTION).updateOne(id, new Document("$set", new Document(Book.AUTHOR, value)));
        new UpgradeToV1_2_3().apply(mongo);
        new UpgradeToV1_2_3().apply(mongo);
        assertThat(mongo.getCollection(Book.COLLECTION).find(id).first().get(Book.AUTHOR, Document.class))
                .containsEntry(Book.Author.NAME, value);
    }

    @Test
    void existingAuthorNameIsNotInspectedOrWrappedAgain() {
        new UpgradeToV1_0_0().apply(mongo);
        Document author = new Document(Book.Author.NAME, List.of("Author")).append("note", "Keep this");
        Document id = new Document(Book.ID, "9780134685991");
        mongo.getCollection(Book.COLLECTION).updateOne(id, new Document("$set", new Document(Book.AUTHOR, author)));
        new UpgradeToV1_2_3().apply(mongo);
        assertThat(mongo.getCollection(Book.COLLECTION).find(id).first()).containsEntry(Book.AUTHOR, author);
    }

    @Test
    void yearValuesAreRenamedWithoutInputTypeGuards() {
        new UpgradeToV1_0_0().apply(mongo);
        Document id = new Document(Book.ID, "9780134685991");
        mongo.getCollection(Book.COLLECTION).updateOne(id,
                new Document("$set", new Document(Book.PUBLISHED_YEAR, "unknown")));
        new UpgradeToV2_0_1().apply(mongo);
        assertThat(mongo.getCollection(Book.COLLECTION).find(id).first())
                .containsEntry(Book.PUBLICATION_YEAR, "unknown").containsEntry(Book.AVAILABLE, true)
                .doesNotContainKeys(Book.PUBLISHED_YEAR, Book.GENRES);
    }

    @Test
    void unconvertibleRecordsAreSkippedWhileOtherRecordsAndUpgradesComplete() {
        new UpgradeToV1_0_0().apply(mongo);
        Document id = new Document(Book.ID, "9780134685991");
        mongo.getCollection(Book.COLLECTION).updateOne(id,
                new Document("$set", new Document(Book.PAGES, "not a number")));
        try (ConfigurableApplicationContext context = start()) {
            assertThat(mongo.getCollection(Book.COLLECTION).find(id).first())
                    .containsEntry(Book.PAGES, "not a number").containsEntry(Book.AUTHOR, "Joshua Bloch")
                    .containsEntry(Book.PUBLICATION_YEAR, 2018);
            assertThat(context.getBean(BookRepository.class).findByAuthorName("Robert C. Martin")).hasSize(1);
        }
    }

    @Test
    void releaseCompletesWhenAllPagesAreUnsupported() {
        new UpgradeToV1_0_0().apply(mongo);
        mongo.getCollection(Book.COLLECTION).updateMany(new Document(),
                new Document("$set", new Document(Book.PAGES, "not a number")));
        List<Document> before = documents();
        new UpgradeToV1_2_3().apply(mongo);
        assertThat(documents()).isEqualTo(before);
    }

    @Test
    void upgradesDoNothingWhenNoRecordsHaveRelevantFields() {
        new UpgradeToV1_0_0().apply(mongo);
        mongo.getCollection(Book.COLLECTION).updateMany(new Document(), new Document("$unset",
                new Document(Book.PAGES, "").append(Book.AUTHOR, "").append(Book.PUBLISHED_YEAR, "")));
        List<Document> before = documents();
        try (ConfigurableApplicationContext ignored = start()) {
            assertThat(documents()).isEqualTo(before);
        }
    }

    @Test
    void emptyCollectionsAreValidNoOps() {
        new UpgradeToV1_2_3().apply(mongo);
        new UpgradeToV2_0_1().apply(mongo);
        assertThat(documents()).isEmpty();
    }
}
