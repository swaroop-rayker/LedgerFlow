# Database Persistence Independence Policy

LedgerFlow's database must be completely independent of the application build lifecycle.

Building, rebuilding, deploying, reinstalling (without uninstall), hot reload, app restart, process death, and device reboot must NEVER modify or delete persistent data.

The database must retain:

* Expenses
* Categories
* Subcategories
* Merchant Intelligence
* Pending Transactions
* Budgets
* Attachments
* Settings
* Audit Logs
* Search History
* User Preferences

The application should always treat the existing database as the authoritative source of truth.

Startup responsibilities are limited to:

1. Open the database.
2. Validate schema compatibility.
3. Run integrity checks.
4. Execute required migrations.
5. Continue normal operation.

Startup must NEVER:

* Delete the database.
* Recreate the database.
* Reset tables.
* Reseed existing data.
* Restore backups automatically.
* Modify user-owned records.

Database mutation is only permitted through explicit user actions or approved migration paths.

The build process, Gradle configuration, Hilt initialization, and application startup must remain completely decoupled from database persistence while preserving all existing database contents and behavior across builds and application updates.
