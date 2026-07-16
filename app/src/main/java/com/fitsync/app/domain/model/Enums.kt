package com.fitsync.app.domain.model

enum class AccountStatus {
    ACTIVE,
    AUTH_EXPIRED,
    NEEDS_USER_ACTION,
    DISABLED,
    EXPERIMENTAL,
}

enum class SessionState {
    AUTHENTICATED,
    EXPIRED,
    NOT_CONNECTED,
    NEEDS_USER_ACTION,
}

enum class ConnectorExecution {
    BACKGROUND,
    FOREGROUND_WEB,
    MANUAL,
    UNSUPPORTED,
}

enum class DedupStatus {
    UNIQUE,
    AUTO_MERGED,
    NEEDS_REVIEW,
    MANUALLY_MERGED,
    MANUALLY_KEPT_SEPARATE,
}

enum class CollectorType {
    SHARE,
    CLOUD,
    LOCAL,
}

enum class ValidationStatus {
    VALID,
    VALID_WITH_WARNINGS,
    CRC_INVALID,
    NOT_ACTIVITY,
    TRUNCATED,
    UNSUPPORTED,
    PENDING,
}

enum class ImportJobStatus {
    RECEIVED,
    COPYING,
    VALIDATING,
    PARSING,
    DEDUPLICATING,
    READY,
    NEEDS_REVIEW,
    INVALID,
    DUPLICATE_FILE,
}

enum class DeliveryJobStatus {
    PENDING,
    UPLOADING,
    WAITING_USER,
    WAITING_REMOTE_PROCESS,
    SUCCEEDED,
    DUPLICATE_REMOTE,
    RETRY_WAIT,
    FAILED,
    DISABLED,
}

enum class SportType {
    CYCLING,
    INDOOR_CYCLING,
    MTB,
    GRAVEL,
    RUNNING,
    OTHER,
    UNKNOWN,
}

enum class FitStoragePolicy {
    KEEP_FOREVER,
    DELETE_AFTER_7_DAYS,
    DELETE_AFTER_SYNC,
}
