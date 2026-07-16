package com.fitsync.app.data.db

import androidx.room.TypeConverter
import com.fitsync.app.domain.model.AccountStatus
import com.fitsync.app.domain.model.CollectorType
import com.fitsync.app.domain.model.ConnectorExecution
import com.fitsync.app.domain.model.DedupStatus
import com.fitsync.app.domain.model.DeliveryJobStatus
import com.fitsync.app.domain.model.FitStoragePolicy
import com.fitsync.app.domain.model.ImportJobStatus
import com.fitsync.app.domain.model.Platform
import com.fitsync.app.domain.model.SportType
import com.fitsync.app.domain.model.ValidationStatus

class Converters {
    @TypeConverter fun fromPlatform(v: Platform?): String? = v?.name
    @TypeConverter fun toPlatform(v: String?): Platform? = v?.let { Platform.valueOf(it) }

    @TypeConverter fun fromAccountStatus(v: AccountStatus?): String? = v?.name
    @TypeConverter fun toAccountStatus(v: String?): AccountStatus? = v?.let { AccountStatus.valueOf(it) }

    @TypeConverter fun fromDedupStatus(v: DedupStatus?): String? = v?.name
    @TypeConverter fun toDedupStatus(v: String?): DedupStatus? = v?.let { DedupStatus.valueOf(it) }

    @TypeConverter fun fromCollectorType(v: CollectorType?): String? = v?.name
    @TypeConverter fun toCollectorType(v: String?): CollectorType? = v?.let { CollectorType.valueOf(it) }

    @TypeConverter fun fromConnectorExecution(v: ConnectorExecution?): String? = v?.name
    @TypeConverter fun toConnectorExecution(v: String?): ConnectorExecution? =
        v?.let { ConnectorExecution.valueOf(it) }

    @TypeConverter fun fromValidationStatus(v: ValidationStatus?): String? = v?.name
    @TypeConverter fun toValidationStatus(v: String?): ValidationStatus? = v?.let { ValidationStatus.valueOf(it) }

    @TypeConverter fun fromImportJobStatus(v: ImportJobStatus?): String? = v?.name
    @TypeConverter fun toImportJobStatus(v: String?): ImportJobStatus? = v?.let { ImportJobStatus.valueOf(it) }

    @TypeConverter fun fromDeliveryJobStatus(v: DeliveryJobStatus?): String? = v?.name
    @TypeConverter fun toDeliveryJobStatus(v: String?): DeliveryJobStatus? = v?.let { DeliveryJobStatus.valueOf(it) }

    @TypeConverter fun fromSportType(v: SportType?): String? = v?.name
    @TypeConverter fun toSportType(v: String?): SportType? = v?.let { SportType.valueOf(it) }

    @TypeConverter fun fromFitStoragePolicy(v: FitStoragePolicy?): String? = v?.name
    @TypeConverter fun toFitStoragePolicy(v: String?): FitStoragePolicy? = v?.let { FitStoragePolicy.valueOf(it) }
}
