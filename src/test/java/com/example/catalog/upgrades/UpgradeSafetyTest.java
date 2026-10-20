package com.example.catalog.upgrades;

import java.util.List;
import com.example.catalog.Book;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.UpdateDefinition;
import org.springframework.data.mongodb.core.query.Query;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class UpgradeSafetyTest {
    @Test
    void eachUpgradeUsesBulkOperationsWithoutRecordScans() {
        for (DatabaseUpgrade upgrade : List.of(new UpgradeToV1_2_3(), new UpgradeToV2_0_1())) {
            MongoTemplate mongo = mock(MongoTemplate.class);
            assertThatCode(() -> upgrade.apply(mongo)).doesNotThrowAnyException();
            verify(mongo, times(2)).updateMulti(any(Query.class), any(UpdateDefinition.class), eq(Book.COLLECTION));
            verifyNoMoreInteractions(mongo);
        }
    }

    @Test
    void databaseWriteFailuresAreNotIgnored() {
        for (DatabaseUpgrade upgrade : List.of(new UpgradeToV1_2_3(), new UpgradeToV2_0_1())) {
            MongoTemplate mongo = mock(MongoTemplate.class);
            DataAccessResourceFailureException failure = new DataAccessResourceFailureException("Database unavailable");
            when(mongo.updateMulti(any(Query.class), any(UpdateDefinition.class), eq(Book.COLLECTION))).thenThrow(failure);
            assertThatThrownBy(() -> upgrade.apply(mongo)).isSameAs(failure);
        }
    }
}
