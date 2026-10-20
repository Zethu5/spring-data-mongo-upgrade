package com.example.catalog;

import java.util.Date;
import lombok.Value;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Value
@Document("databaseUpgradeLock")
public class UpgradeLock {
    @Id
    String id;
    String owner;
    Date acquiredAt;
}
