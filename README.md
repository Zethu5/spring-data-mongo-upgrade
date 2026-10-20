# MongoDB upgrades

Java 17, Spring Boot 3.5.0, and MongoDB 8.0. No database upgrade library is required.

`UpgradeRunner` runs every upgrade in its list during each Spring Boot startup.
Each upgrade provides a version. The runner sorts versions numerically before execution: `1.2.3` runs before `1.10.0`.
A database upgrade lock prevents simultaneous upgrades. There is no target version, history, or validator cleanup.
A database failure stops execution and propagates to Spring Boot. Earlier database changes remain.

## Upgrades

Upgrade classes are in `src/main/java/com/example/catalog/upgrades/`.

| Class | Changes |
| --- | --- |
| `UpgradeToV1_0_0` | Insert three sample books if their IDs are missing |
| `UpgradeToV1_2_3` | Convert pages to long and wrap author values in objects |
| `UpgradeToV2_0_1` | Rename publication year, add availability, and delete genres |

Each class implements `DatabaseUpgrade.version()` and `DatabaseUpgrade.apply(MongoTemplate mongo)`.
Use numeric release versions such as `1.2.3`.
To add an upgrade, implement both methods and add the class to the list in `UpgradeRunner.upgrades()`.
Keep upgrades safe to repeat. Every startup runs all of them again.
The sample-book upgrade restores deleted sample books but preserves existing books.

Upgrades use Spring Data queries and bulk operations, without loading historical documents into the current Java model.
Field and collection names use constants from `Book`.
Do not change stored field names in released constants. Add a new constant for a renamed field.

## Conversion behavior

`UpgradeToV1_2_3` applies only lossless page conversions.
Unsupported values, fractions, overflow, nulls, and numeric strings with leading zeros remain unchanged.
An unsupported pages value skips both page and author conversion for that record.
Authors without pages can still convert. Existing `author.name` fields remain unchanged.

`UpgradeToV2_0_1` selects records with exactly one of the old and new year fields.
It renames the old field and removes genres, then adds availability where missing.
Rename collisions never overwrite either year field. Existing availability values, including null, remain unchanged.
Records with both year fields or neither year field are skipped by this upgrade.

Each upgrade has its own queries. A later upgrade can change records skipped by an earlier upgrade.
Skipped records are checked again on the next startup.
Empty collections and queries with no matches are successful no-ops.
Each operation is atomic per document, not across the entire upgrade list.

## Safety

**Back up the database before running. The final upgrade permanently deletes `genres` from matching records.**

1. Stop other application instances and database writers.
2. Confirm the target MongoDB URI.
3. Run only one upgrade process at a time.

There is no automatic rollback, completion history, or downgrade protection.
The lock coordinates upgrade runners, not normal application writes.
Stop all older runners before deploying this version. Their lock collection is separate from `databaseUpgradeLock`.
Lock writes use majority acknowledgement and journaling.
After acquiring the lock, the runner attempts removal in `finally`, whether upgrades succeed or fail.
A failed run can leave partial changes. Fix the cause before restarting; restarting repeats the entire list.
A crash or failed lock deletion can leave the lock behind. The lock has no automatic expiry.
A timed-out database operation can continue after lock removal. Confirm that previous operations have ended before retrying.

### Recover a remaining lock

1. Stop all application instances and confirm that their database operations have ended.
2. Inspect the error and the lock's `owner` and `acquiredAt` fields.
3. Fix the failure before removing the inspected lock.
4. Restart one instance. It repeats the entire upgrade list.

Inspect the lock:

```sh
docker compose exec mongo mongosh book_catalog --eval 'db.databaseUpgradeLock.findOne({_id: "catalog"})'
```

After the safety checks, replace `INSPECTED_OWNER` with the exact inspected owner:

```sh
docker compose exec mongo mongosh book_catalog --eval 'db.databaseUpgradeLock.deleteOne({_id: "catalog", owner: "INSPECTED_OWNER"})'
```

Existing MongoDB validators remain active and can reject upgrades.
Records skipped by conversions can retain formats that the current Java model cannot read.
Do not access migrated collections from bean constructors or initialization methods before the runner executes.

## Run

1. Start Docker Desktop.
2. Open a terminal in this directory.
3. Run `docker compose up -d --wait`.
4. Run `mvn spring-boot:run` after the safety checks.

The default URI is `mongodb://localhost:27017/book_catalog`. Override it with `MONGODB_URI`.
This application initializes the database, then exits. It is not a web server.
MongoDB remains available through Docker.

Inspect documents:

```sh
docker compose exec mongo mongosh book_catalog --eval 'db.books.find({}, {_class: 0}).pretty()'
```

## Current document format

```json
{
  "_id": "9780134685991",
  "title": "Effective Java",
  "author": { "name": "Joshua Bloch" },
  "publicationYear": 2018,
  "pages": 416,
  "available": true
}
```

Pages use BSON `long`; JSON does not show integer width.
MongoDB does not enforce this format unless a validator already exists. Validate normal application writes separately.
Other fields remain unchanged, except for explicitly retired fields. Spring Data can also write `_class` metadata.
`BookRepository` provides CRUD methods and `findByAuthorName(String name)`.

## Tests

Run unit tests without MongoDB:

```sh
mvn test
```

Run integration tests against MongoDB on `localhost:27017` in PowerShell:

```powershell
$env:MONGODB_INTEGRATION_TESTS = 'true'
mvn clean verify
```

Or use Bash:

```sh
MONGODB_INTEGRATION_TESTS=true mvn clean verify
```

Integration tests create and remove only random `catalog_test_*` databases. They do not upgrade `book_catalog`.
Tests cover startup, repeated execution, database failures, partial conversions, invalid values, and rename collisions.
Without the environment variable, Maven skips integration tests.

## Local use only

Docker binds MongoDB to `127.0.0.1`. Authentication is not configured.
Do not expose MongoDB without authentication and TLS.
Run `docker compose stop` to stop MongoDB without deleting its data.
