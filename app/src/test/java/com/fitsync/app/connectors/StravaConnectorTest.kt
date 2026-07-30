package com.fitsync.app.connectors

import com.google.common.truth.Truth.assertThat
import org.json.JSONObject
import org.junit.Test

class StravaConnectorTest {
    @Test
    fun duplicateError_extractsActivityIdFromOfficialResponseShape() {
        assertThat(
            parseStravaDuplicateActivityId("Test.fit duplicate of activity 21234316"),
        ).isEqualTo("21234316")
    }

    @Test
    fun uploadId_prefersStringFieldForFuture64BitIds() {
        val root = JSONObject(
            """{"id":123,"id_str":"9223372036854775808","activity_id":null}""",
        )

        assertThat(stravaUploadId(root)).isEqualTo("9223372036854775808")
        assertThat(stravaActivityId(root)).isNull()
    }

    @Test
    fun activityId_acceptsStringOrNumericJsonValues() {
        assertThat(stravaActivityId(JSONObject("""{"activity_id":"153243126"}""")))
            .isEqualTo("153243126")
        assertThat(stravaActivityId(JSONObject("""{"activity_id":153243127}""")))
            .isEqualTo("153243127")
    }
}
