package com.fitsync.app.connectors

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * COROS 上传第三步：用 STS 临时凭证对阿里云 OSS PUT 请求做 V1 签名（HMAC-SHA1 + base64）。
 *
 * 与 iGPSPORT（服务端预签名 URL 直传）不同，COROS 由客户端拿 STS 临时 AK/SK 自己签名，
 * 故必须正确拼装 [ossStringToSign] 并 HmacSHA1。签名串里的 Content-Type 必须与实际发送头
 * 完全一致（显式 [CONTENT_TYPE]），否则 OSS 返回 SignatureDoesNotMatch——这是与 iGPSPORT
 * 「空 Content-Type」相反的坑。
 */
internal object CorosOssSigner {
    const val CONTENT_TYPE = "application/octet-stream"

    /**
     * OSS V1 签名串（暴露为 internal 便于单测固化格式——这是手写签名最易拼错的部分）。
     *
     * StringToSign = HTTP-VERB + "\n"
     *              + Content-MD5(空) + "\n"
     *              + Content-Type + "\n"
     *              + Date(RFC1123 GMT) + "\n"
     *              + CanonicalizedOSSHeaders(x-oss-* 按字典序，每项 name:value + 换行)
     *              + CanonicalizedResource(/bucket/objectKey)
     */
    fun ossStringToSign(
        method: String,
        contentType: String,
        date: String,
        securityToken: String,
        bucket: String,
        objectKey: String,
    ): String = buildString {
        append(method).append('\n')
        append("").append('\n')                 // Content-MD5 不发，留空
        append(contentType).append('\n')
        append(date).append('\n')
        append("x-oss-security-token:").append(securityToken).append('\n')
        append('/').append(bucket).append('/').append(objectKey)
    }

    /** 生成 `Authorization: OSS {AccessKeyId}:{Signature}` 头。 */
    fun authorization(
        method: String,
        contentType: String,
        date: String,
        securityToken: String,
        bucket: String,
        objectKey: String,
        accessKeyId: String,
        accessKeySecret: String,
    ): String {
        val stringToSign = ossStringToSign(method, contentType, date, securityToken, bucket, objectKey)
        return "OSS $accessKeyId:${hmacSha1Base64(accessKeySecret, stringToSign)}"
    }

    /** 组装一次完整 PUT 的 plan（virtual-hosted URL + 各头）。[date] 由调用方用 [currentRfc1123Date] 生成。 */
    fun plan(sts: CorosStsCredentials, objectKey: String, date: String): CorosOssPutPlan {
        val auth = authorization(
            method = "PUT",
            contentType = CONTENT_TYPE,
            date = date,
            securityToken = sts.securityToken,
            bucket = sts.bucket,
            objectKey = objectKey,
            accessKeyId = sts.accessKeyId,
            accessKeySecret = sts.accessKeySecret,
        )
        return CorosOssPutPlan(
            url = "https://${sts.bucket}.${ossEndpoint(sts.region)}/$objectKey",
            authorization = auth,
            date = date,
            contentType = CONTENT_TYPE,
            securityToken = sts.securityToken,
        )
    }

    /** 构造 OkHttp PUT 请求；Content-Type 由 body.contentType() 提供，与签名串一致。 */
    fun buildPutRequest(plan: CorosOssPutPlan, zipBytes: ByteArray): Request {
        val body = zipBytes.toRequestBody(plan.contentType.toMediaType())
        return Request.Builder()
            .url(plan.url)
            .put(body)
            .header("Authorization", plan.authorization)
            .header("x-oss-security-token", plan.securityToken)
            .header("Date", plan.date)
            .build()
    }

    /** 把单个 .fit 打成 zip 字节流（单 entry，entry 名 = [entryName]，需与 import 的 oriFileName 一致）。 */
    fun buildZip(originalFit: File, entryName: String): ByteArray {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zip ->
            zip.putNextEntry(ZipEntry(entryName))
            originalFit.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
        }
        return baos.toByteArray()
    }

    /** 当前时间的 RFC1123 GMT 字符串（OSS Date 头要求）。 */
    fun currentRfc1123Date(): String {
        val sdf = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("GMT")
        return sdf.format(Date())
    }

    private fun ossEndpoint(region: String): String = "$region.aliyuncs.com"

    private fun hmacSha1Base64(key: String, data: String): String {
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA1"))
        return Base64.getEncoder().encodeToString(mac.doFinal(data.toByteArray(Charsets.UTF_8)))
    }
}

internal data class CorosOssPutPlan(
    val url: String,
    val authorization: String,
    val date: String,
    val contentType: String,
    val securityToken: String,
)
