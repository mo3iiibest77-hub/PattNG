package com.v2ray.ang.ui.qsn.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudFrontIpRangesParserTest {

    @Test
    fun extractsOnlyCloudFrontIpv4PrefixesIncludingWideMasksAndDeduplicates() {
        val sampleJson = """
            {
              "syncToken": "1700000000",
              "prefixes": [
                {"ip_prefix": "13.32.0.5/15", "region": "GLOBAL", "service": "CLOUDFRONT"},
                {"ip_prefix": "13.32.0.0/15", "region": "GLOBAL", "service": "CLOUDFRONT"},
                {"ip_prefix": "52.84.0.0/14", "region": "GLOBAL", "service": "CLOUDFRONT"},
                {"ip_prefix": "54.231.0.0/16", "region": "us-east-1", "service": "S3"},
                {"ip_prefix": "3.5.0.0/19", "region": "us-west-2", "service": "EC2"},
                {"ip_prefix": "99.84.0.0/16", "region": "GLOBAL", "service": "cloudfront"}
              ],
              "ipv6_prefixes": [
                {"ipv6_prefix": "2600:9000::/28", "region": "GLOBAL", "service": "CLOUDFRONT"}
              ]
            }
        """.trimIndent()

        val cidrs = CloudFrontIpRangesParser.parseIpv4Cidrs(sampleJson)
        assertEquals(
            listOf("13.32.0.0/15", "52.84.0.0/14", "99.84.0.0/16"),
            cidrs,
        )
    }

    @Test
    fun handlesMalformedOrEmptyPayloadsWithoutThrowing() {
        assertTrue(CloudFrontIpRangesParser.parseIpv4Cidrs("").isEmpty())
        assertTrue(CloudFrontIpRangesParser.parseIpv4Cidrs("{not json").isEmpty())
        assertTrue(CloudFrontIpRangesParser.parseIpv4Cidrs("{\"prefixes\":[]}").isEmpty())
    }
}
