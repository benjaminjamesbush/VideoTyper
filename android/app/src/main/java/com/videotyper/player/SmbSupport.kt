package com.videotyper.player

import android.net.Uri
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Properties
import jcifs.CIFSContext
import jcifs.config.PropertyConfiguration
import jcifs.context.BaseContext
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbFile

/**
 * Shared jcifs-ng plumbing used by playback (SmbDataSource), the network browser, and thumbnail
 * generation. Credentials travel inside the smb:// URL as userInfo — "user", "user:pass", or
 * "domain;user:pass" — matching how the rest of the app stores replayable URLs. Missing
 * credentials fall back to guest access.
 */
object SmbSupport {

    fun contextFor(userInfo: String?): CIFSContext {
        val props = Properties().apply {
            setProperty("jcifs.smb.client.minVersion", "SMB202")
            setProperty("jcifs.smb.client.maxVersion", "SMB311")
        }
        val base = BaseContext(PropertyConfiguration(props))
        if (userInfo == null) return base.withGuestCrendentials()

        val decoded = URLDecoder.decode(userInfo, "UTF-8")
        val domain = decoded.substringBefore(';', "")
        val rest = decoded.substringAfter(';')
        val user = rest.substringBefore(':')
        val pass = rest.substringAfter(':', "")
        return base.withCredentials(NtlmPasswordAuthenticator(domain, user, pass))
    }

    /** Build an SmbFile for a smb:// URL, whether or not it carries embedded credentials. */
    fun smbFile(uriString: String): SmbFile {
        val uri = Uri.parse(uriString)
        val ctx = contextFor(uri.userInfo)
        return SmbFile(uriString, ctx)
    }

    /**
     * Compose a replayable smb:// URL with credentials embedded, from a credential-free share path
     * (smb://host/share/dir/file.mkv) and the pieces the user entered. Username/password/domain are
     * URL-encoded so odd characters survive round-tripping.
     */
    fun urlWithCredentials(pathNoCreds: String, user: String, pass: String, domain: String): String {
        if (user.isBlank()) return pathNoCreds // guest / anonymous
        val enc = { s: String -> URLEncoder.encode(s, "UTF-8").replace("+", "%20") }
        val userInfo = buildString {
            if (domain.isNotBlank()) append(enc(domain)).append(';')
            append(enc(user))
            append(':').append(enc(pass))
        }
        return pathNoCreds.replaceFirst("smb://", "smb://$userInfo@")
    }
}
