package com.example.catalog.upgrades;

import org.springframework.data.mongodb.core.MongoTemplate;

public interface DatabaseUpgrade {
    String version();

    void apply(MongoTemplate mongo);
}
