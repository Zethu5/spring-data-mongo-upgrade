package com.example.catalog;

import com.example.catalog.upgrades.UpgradeToV1_0_0;
import com.example.catalog.upgrades.UpgradeToV1_2_3;
import com.example.catalog.upgrades.UpgradeToV2_0_1;
import com.mongodb.WriteConcern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.MockedConstruction;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.UpdateDefinition;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class UpgradeRunnerTest {
    private MongoTemplate mongo;
    private MockedConstruction<MongoTemplate> lockTemplates;

    @BeforeEach
    void templates() {
        mongo = mock(MongoTemplate.class);
        lockTemplates = mockConstruction(MongoTemplate.class);
    }

    @AfterEach
    void closeTemplates() {
        lockTemplates.close();
    }

    @Test
    void locksBeforeUpgradesAndUnlocksAfterSuccessOnEveryRun() {
        UpgradeRunner runner = new UpgradeRunner(mongo);
        for (int attempt = 0; attempt < 2; attempt++) {
            runner.run(null);
            MongoTemplate locks = lockTemplates.constructed().get(attempt);
            InOrder order = inOrder(locks, mongo);
            order.verify(locks).setWriteConcern(WriteConcern.MAJORITY.withJournal(true));
            order.verify(locks).insert(any(UpgradeLock.class));
            order.verify(mongo, times(3)).upsert(any(Query.class), any(UpdateDefinition.class), eq(Book.COLLECTION));
            order.verify(mongo, times(4)).updateMulti(any(Query.class), any(UpdateDefinition.class), eq(Book.COLLECTION));
            order.verify(locks).remove(any(Query.class), eq(UpgradeLock.class));
            clearInvocations(mongo);
        }
    }

    @Test
    void sortsVersionsNumericallyInsteadOfUsingListOrTextOrder() {
        try (var first = mockConstruction(UpgradeToV1_0_0.class,
                     (upgrade, context) -> when(upgrade.version()).thenReturn("1.10.0"));
             var second = mockConstruction(UpgradeToV1_2_3.class,
                     (upgrade, context) -> when(upgrade.version()).thenReturn("1.2.3"));
             var third = mockConstruction(UpgradeToV2_0_1.class,
                     (upgrade, context) -> when(upgrade.version()).thenReturn("2.0.1"))) {
            new UpgradeRunner(mongo).run(null);
            var middle = first.constructed().get(0);
            var earliest = second.constructed().get(0);
            var latest = third.constructed().get(0);
            InOrder order = inOrder(earliest, middle, latest);
            order.verify(earliest).apply(mongo);
            order.verify(middle).apply(mongo);
            order.verify(latest).apply(mongo);
        }
    }

    @Test
    void stopsAtTheFirstDatabaseFailureAndReleasesTheLock() {
        DataAccessResourceFailureException failure = new DataAccessResourceFailureException("Database unavailable");
        when(mongo.upsert(any(Query.class), any(UpdateDefinition.class), eq(Book.COLLECTION))).thenThrow(failure);
        assertThatThrownBy(() -> new UpgradeRunner(mongo).run(null)).isSameAs(failure);
        verify(mongo).upsert(any(Query.class), any(UpdateDefinition.class), eq(Book.COLLECTION));
        verify(mongo, never()).updateMulti(any(Query.class), any(UpdateDefinition.class), anyString());
        verify(lockTemplates.constructed().get(0)).remove(any(Query.class), eq(UpgradeLock.class));
    }

    @Test
    void existingLockStopsExecutionBeforeAnyUpgrade() {
        lockTemplates.close();
        lockTemplates = mockConstruction(MongoTemplate.class, (locks, context) ->
                when(locks.insert(any(UpgradeLock.class)))
                        .thenThrow(new DuplicateKeyException("Existing lock")));
        assertThatThrownBy(() -> new UpgradeRunner(mongo).run(null))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Upgrade lock exists");
        verify(mongo, never()).upsert(any(Query.class), any(UpdateDefinition.class), anyString());
        verify(mongo, never()).updateMulti(any(Query.class), any(UpdateDefinition.class), anyString());
        verify(lockTemplates.constructed().get(0), never()).remove(any(Query.class), any(Class.class));
    }
}
