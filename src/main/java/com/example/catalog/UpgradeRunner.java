package com.example.catalog;

import java.lang.module.ModuleDescriptor.Version;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import com.mongodb.WriteConcern;
import com.example.catalog.upgrades.DatabaseUpgrade;
import com.example.catalog.upgrades.UpgradeToV1_0_0;
import com.example.catalog.upgrades.UpgradeToV1_2_3;
import com.example.catalog.upgrades.UpgradeToV2_0_1;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

@Component
public class UpgradeRunner implements ApplicationRunner {
    private final MongoTemplate mongo;

    public UpgradeRunner(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    private List<DatabaseUpgrade> upgrades() {
        return List.of(
                new UpgradeToV1_0_0(),
                new UpgradeToV1_2_3(),
                new UpgradeToV2_0_1());
    }

    @Override
    public void run(ApplicationArguments args) {
        MongoTemplate locks = new MongoTemplate(mongo.getMongoDatabaseFactory(), mongo.getConverter());
        locks.setWriteConcern(WriteConcern.MAJORITY.withJournal(true));
        UpgradeLock lock = new UpgradeLock("catalog", UUID.randomUUID().toString(), new Date());
        try {
            locks.insert(lock);
        } catch (DuplicateKeyException exception) {
            throw new IllegalStateException("Upgrade lock exists. See README.md for recovery.", exception);
        }
        try {
            upgrades().stream()
                    .sorted(Comparator.comparing(upgrade -> Version.parse(upgrade.version())))
                    .forEach(upgrade -> upgrade.apply(mongo));
        } finally {
            locks.remove(Query.query(Criteria.where("id").is(lock.getId()).and("owner").is(lock.getOwner())),
                    UpgradeLock.class);
        }
    }
}
