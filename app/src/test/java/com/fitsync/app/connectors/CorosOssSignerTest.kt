package com.fitsync.app.connectors

import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipInputStream
import org.junit.Test

class CorosOssSignerTest {

    @Test
    fun ossStringToSign_exactFormat() {
        // 签名串格式是手写签名最易拼错的部分：精确断言换行与字段顺序
        val s = CorosOssSigner.ossStringToSign(
            method = "PUT",
            contentType = "application/octet-stream",
            date = "Wed, 01 Jan 2025 00:00:00 GMT",
            securityToken = "sec-token",
            bucket = "coros-oss",
            objectKey = "fit_zip/u-7/abc123.zip",
        )
        assertThat(s).isEqualTo(
            "PUT\n" +
                "\n" +
                "application/octet-stream\n" +
                "Wed, 01 Jan 2025 00:00:00 GMT\n" +
                "x-oss-security-token:sec-token\n" +
                "/coros-oss/fit_zip/u-7/abc123.zip",
        )
    }

    @Test
    fun authorization_hasCorrectPrefixAndNonEmptySignature() {
        val a = CorosOssSigner.authorization(
            method = "PUT",
            contentType = CorosOssSigner.CONTENT_TYPE,
            date = "Wed, 01 Jan 2025 00:00:00 GMT",
            securityToken = "sec-token",
            bucket = "coros-oss",
            objectKey = "fit_zip/u-7/abc123.zip",
            accessKeyId = "AKID-test",
            accessKeySecret = "SECRET-test",
        )
        assertThat(a).startsWith("OSS AKID-test:")
        assertThat(a.substringAfter(':')).isNotEmpty() // 冒号后是 base64 签名
    }

    @Test
    fun authorization_sensitiveToSecretObjectKeyAndToken() {
        val base = auth(secret = "S1", objectKey = "fit_zip/u/1.zip", token = "T1")
        assertThat(auth(secret = "S2", objectKey = "fit_zip/u/1.zip", token = "T1")).isNotEqualTo(base)
        assertThat(auth(secret = "S1", objectKey = "fit_zip/u/2.zip", token = "T1")).isNotEqualTo(base)
        // securityToken 进了签名串：改它签名必变
        assertThat(auth(secret = "S1", objectKey = "fit_zip/u/1.zip", token = "T2")).isNotEqualTo(base)
    }

    private fun auth(secret: String, objectKey: String, token: String): String =
        CorosOssSigner.authorization(
            method = "PUT",
            contentType = CorosOssSigner.CONTENT_TYPE,
            date = "Wed, 01 Jan 2025 00:00:00 GMT",
            securityToken = token,
            bucket = "coros-oss",
            objectKey = objectKey,
            accessKeyId = "AKID-test",
            accessKeySecret = secret,
        )

    @Test
    fun plan_buildsVirtualHostedUrlAndHeaders() {
        val sts = CorosStsCredentials(
            accessKeyId = "ak",
            accessKeySecret = "sk",
            securityToken = "st",
            bucket = "coros-oss",
            region = "oss-cn-beijing",
            expiration = null,
        )
        val plan = CorosOssSigner.plan(sts, "fit_zip/u/abc.zip", "Wed, 01 Jan 2025 00:00:00 GMT")
        assertThat(plan.url).isEqualTo("https://coros-oss.oss-cn-beijing.aliyuncs.com/fit_zip/u/abc.zip")
        assertThat(plan.authorization).startsWith("OSS ak:")
        assertThat(plan.contentType).isEqualTo("application/octet-stream")
        assertThat(plan.securityToken).isEqualTo("st")
    }

    @Test
    fun buildZip_containsSingleFitEntry() {
        val fit = File.createTempFile("coros-upload", ".fit")
        try {
            val payload = byteArrayOf(1, 2, 3, 4, 5)
            fit.writeBytes(payload)
            val zip = CorosOssSigner.buildZip(fit, "act.fit")
            val (entryName, content) = ZipInputStream(ByteArrayInputStream(zip)).use { zis ->
                val e = zis.nextEntry
                e!!.name to zis.readBytes()
            }
            assertThat(entryName).isEqualTo("act.fit")
            assertThat(content).isEqualTo(payload)
        } finally {
            fit.delete()
        }
    }

    @Test
    fun buildPutRequest_headersAndContentTypeConsistentWithSignature() {
        val sts = CorosStsCredentials(
            accessKeyId = "ak",
            accessKeySecret = "sk",
            securityToken = "st",
            bucket = "coros-oss",
            region = "oss-cn-beijing",
            expiration = null,
        )
        val plan = CorosOssSigner.plan(sts, "fit_zip/u/x.zip", "Wed, 01 Jan 2025 00:00:00 GMT")
        val request = CorosOssSigner.buildPutRequest(plan, byteArrayOf(1, 2, 3))
        assertThat(request.header("Authorization")).isEqualTo(plan.authorization)
        assertThat(request.header("x-oss-security-token")).isEqualTo("st")
        assertThat(request.header("Date")).isEqualTo(plan.date)
        // body 的 contentType 必须等于签名串里的 contentType，否则 OSS SignatureDoesNotMatch
        assertThat(request.body?.contentType()?.toString()).isEqualTo("application/octet-stream")
    }
}
