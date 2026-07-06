# LedgerFlow: Personal Financial Intelligence System

LedgerFlow is a secure, privacy-first, offline-first personal financial intelligence system engineered in Kotlin, Jetpack Compose, Room, and Hilt. It captures banking notifications, normalizes transaction records, and builds secure ledger reports without relying on external cloud synchronization.

---

## 1. Modular Directory Structure

The project implements a strict Clean Architecture layout using Gradle subprojects to isolate business logic, platform services, and user interfaces:

```
LedgerFlow/
│
├── core/
│   ├── common/           # Precision currency math, date utilities, and MerchantNormalizer
│   ├── security/         # Android Keystore providers, AES-256-GCM engines, and SQLCipher passphrase factories
│   └── ui/               # Custom Material 3 branding, typography models, palette colors, and shared UI assets
│
├── domain/               # Pure Kotlin layer: entities, use cases, and repository interfaces
│
├── data/                 # Room database schemas, SQLCipher clients, migrations, and Datastore repositories
│
├── services/             # Background SMS broadcast receivers, WorkManager parsing, and ZIP backup implementations
│
├── presentation/         # ViewModels, type-safe Compose Navigation paths, and responsive feature views
│
└── app/                  # Main Application class, AndroidManifest, build files, and Hilt dependency modules
```

---

## 2. System Architecture & Component Mapping

### Component Dependency Diagram

LedgerFlow follows a strict unidirectional dependency structure where inner layers have no knowledge of outer layers:

```mermaid
graph TD
    subgraph "Application Layer (:app)"
        App[LedgerFlowApplication]
        DI[Hilt Modules]
    end

    subgraph "Presentation Layer (:presentation)"
        UI[Jetpack Compose Screens]
        VM[ViewModels / StateFlow]
    end

    subgraph "Services Layer (:services)"
        SMS[SmsReceiver / SmsWorker]
        Backup[BackupManager]
    end

    subgraph "Data Layer (:data)"
        RepoImpl[Repository Implementations]
        RoomDB[SQLCipher Encrypted SQLite]
        DS[Encrypted DataStore]
    end

    subgraph "Domain Layer (:domain)"
        UC[Use Cases]
        RepoInt[Repository Interfaces]
        Models[Domain Data Models]
    end

    subgraph "Core Utilities (:core)"
        Security[Cryptography & Keystore]
        Common[Currency & Normalizer]
        CoreUI[Material Theme & Colors]
    end

    %% Dependency Arrows
    App --> DI
    DI --> presentation
    DI --> services
    DI --> data
    
    presentation --> domain
    presentation --> CoreUI
    services --> domain
    services --> Security
    data --> domain
    data --> Security
    
    domain --> Common
    CoreUI --> Common
    Security --> Common
```

---

## 3. Data Movement & Transaction Lifecycles

### Transaction Lifecycle: SMS parsing to Room Ledger

LedgerFlow processes incoming transactions in a dual-tier database layout, allowing background capture when the phone is locked, and full ledger merging once the user authenticates via biometrics.

```mermaid
sequenceDiagram
    autonumber
    participant Tel as Telecom Provider
    participant SmsRec as SmsReceiver (Broadcast)
    participant SmsWork as SmsWorker (WorkManager)
    participant PendingDB as Pending DB (Boot-Accessible Cryptography)
    participant MainDB as Main DB (Biometric-Protected Cryptography)
    participant ReviewUI as Review Screen (Compose UI)

    Tel->>SmsRec: Incoming Banking SMS text
    SmsRec->>SmsWork: Queue body for parsing
    SmsWork->>SmsWork: Parse regex (Amount, Merchant, Reference)
    SmsWork->>SmsWork: Run MerchantNormalizer (Canonical resolve)
    SmsWork->>PendingDB: Insert PendingTransaction record
    Note over PendingDB: Decrypted via boot-accessible KeyStore key
    SmsWork-->>ReviewUI: Post System Notification
    
    Note over ReviewUI: User opens notification & authenticates via biometrics
    ReviewUI->>PendingDB: Read PendingTransaction details
    ReviewUI->>ReviewUI: Render confidence levels & merchant history
    
    alt User Approves Transaction
        ReviewUI->>MainDB: Write TransactionEntity (with Transaction ID reference)
        Note over MainDB: Decrypted via biometrics-wrapped Keystore key
        ReviewUI->>PendingDB: Mark pending transaction as APPROVED/CLEARED
    else User Discards Transaction
        ReviewUI->>PendingDB: Mark pending transaction as DISCARDED
    end
```

---

## 4. Merchant Normalization Engine Pipeline

The `MerchantNormalizer` processes raw bank payee names to build clean records, matching aliases and compiling regex templates before registering preferred category predictions.

```mermaid
graph TD
    Raw[Raw SMS Merchant Name] --> Trim[1. Trim Whitespace & Lowercase]
    Trim --> Rules[2. Known Alias Matcher]
    Rules -- Match Found --> Canonical[3. Map to Canonical Name]
    Rules -- No Match --> Regex[4. Compile and Run Cleaner Regex]
    Regex --> Cleanup[5. Strip Suffixes: Pvt, Ltd, Pay, Inc]
    Cleanup --> TitleCase[6. Capitalize Title Words]
    TitleCase --> Learn[7. Preference Store Lookup]
    Canonical --> Learn
    Learn --> Final[Normalized Merchant Name]
```

---

## 5. Backup & Restore Architecture

LedgerFlow preserves user records offline using two isolated backup pipelines:

```mermaid
graph TD
    subgraph "Format A: Full Backup (.lfb)"
        F1[Dump Room SQLite DB File] --> F2[Generate JSON Manifest: app version, checksum, record counts]
        F2 --> F3[Compress DB + Manifest into ZIP]
        F3 --> F4[Encrypt ZIP with PBKDF2 AES-GCM Key]
        F4 --> F5[Write ledgerflow_backup.lfb]
    end

    subgraph "Format B: Portable Backup (.json)"
        P1[Query Room Tables] --> P2[Map to Domain Objects: Expenses, Categories, Settings]
        P2 --> P3[Serialize List to JSON Payload]
        P3 --> P4[Write backup.json]
    end
```

---

## 6. Security Tiers & Cryptography Model

LedgerFlow maintains a robust offline security configuration to guard financial records:

1.  **FLAG_SECURE**: Set on the `Window` in `MainActivity` to disable screenshots, screen recorders, and previews in task managers.
2.  **Two-Tier Keystore Wrapper**:
    *   **Pending Queue DB Key**: Generated using Android KeyStore with authentication requirements bypassed. This allows background WorkManager tasks to write parsed SMS events even when the phone is booted but locked.
    *   **Main Ledger DB Key**: Wrapped under a master key requiring biometric validation. Room cannot open the main database until the user performs biometric verification.
3.  **PBKDF2 Backups**: Full ZIP packages are encrypted via AES-256-GCM. The encryption key is derived using `PBKDF2WithHmacSHA1` using 100,000 iterations and a random salt value.
4.  **Schema Verification**: Before executing restores, the manifest's SHA-256 checksum and database version are verified, ensuring corrupt or incompatible files are aborted before replacing active database sectors.

---

## 7. Build, Verification & Tests

To open the project and run verification tests:

1.  Open Android Studio (Iguana / Koala or newer).
2.  Import this project directory: `d:\LedgerFlow`.
3.  Set the correct `$env:JAVA_HOME` pointing to JDK 17/21 in your command shell.
4.  To run the full suite of unit and integration tests:
    ```bash
    ./gradlew test
    ```
5.  Or run tests for individual components:
    *   [`CurrencyUtilsTest.kt`](file:///d:/LedgerFlow/core/common/src/test/java/com/ledgerflow/core/common/util/CurrencyUtilsTest.kt) - Tests double/cent precision conversions.
    *   [`SmsParserTest.kt`](file:///d:/LedgerFlow/services/src/test/java/com/ledgerflow/services/sms/SmsParserTest.kt) - Validates banking SMS regex patterns and spam exclusion filters.
    *   [`BackupEngineTest.kt`](file:///d:/LedgerFlow/services/src/test/java/com/ledgerflow/services/backup/BackupEngineTest.kt) - Validates GCM encryption/decryption keys.
    *   [`DatabaseMigrationTest.kt`](file:///d:/LedgerFlow/data/src/test/java/com/ledgerflow/data/db/migration/DatabaseMigrationTest.kt) - Validates Room default seeding, resets, and version migrations.
