package app.gamenative.utils

import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.dnsoverhttps.DnsOverHttps
import timber.log.Timber
import java.net.InetAddress
import java.util.concurrent.TimeUnit

object Net {

    private val bootstrapClient = OkHttpClient.Builder()
        .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
        .build()

    private val doh: DnsOverHttps = DnsOverHttps.Builder()
        .client(bootstrapClient)
        .url("https://dns.google/dns-query".toHttpUrl())
        .bootstrapDnsHosts(
            InetAddress.getByName("8.8.8.8"),
            InetAddress.getByName("8.8.4.4"),
        )
        .build()

    val fallbackDns: Dns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            return try {
                doh.lookup(hostname)
            } catch (e: Exception) {
                Timber.w(e, "DoH lookup failed for $hostname, falling back to system DNS")
                Dns.SYSTEM.lookup(hostname)
            }
        }
    }

    val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dns(fallbackDns)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(5, TimeUnit.MINUTES)
            .pingInterval(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}
