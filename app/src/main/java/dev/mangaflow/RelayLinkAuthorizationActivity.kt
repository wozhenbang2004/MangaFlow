package dev.mangaflow

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import dev.mangaflow.net.completeRelayAuthorization
import dev.mangaflow.net.relayAuthorizationIntent

/** Nonexported result bridge preserves the authenticated calling package at Relay's consent UI. */
class RelayLinkAuthorizationActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        val request = relayAuthorizationIntent(this, provider(), certificate(), nonce())
        if (request == null) { finish(); return }
        try { startActivityForResult(request, AUTHORIZATION) }
        catch (_: Exception) {
            completeRelayAuthorization(this, provider(), certificate(), nonce(), false)
            finish()
        }
    }

    @Deprecated("Activity result bridge for the external consent protocol")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != AUTHORIZATION) return
        // The result carries no trusted state: the shared client rereads the signed provider's IPC.
        completeRelayAuthorization(this, provider(), certificate(), nonce(), resultCode == RESULT_OK)
        finish()
    }

    private fun provider(): String = intent.getStringExtra("provider").orEmpty()
    private fun certificate(): String = intent.getStringExtra("certificate").orEmpty()
    private fun nonce(): String = intent.getStringExtra("nonce").orEmpty()

    companion object { private const val AUTHORIZATION = 7194 }
}
