package com.dumuzeyn.mp3player

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/** Accepts only an audio URI, then opens it through the active launcher activity. */
class ExternalAudioActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val source = intent
        val uri = source?.data
        val type = source?.type
        if (source?.action == Intent.ACTION_VIEW && uri?.scheme == "content" &&
            (type?.startsWith("audio/") == true || type == "application/ogg")) {
            val launch = packageManager.getLaunchIntentForPackage(packageName)
                ?: Intent(this, MainActivity::class.java)
            launch.removeCategory(Intent.CATEGORY_LAUNCHER)
            launch.action = Intent.ACTION_VIEW
            launch.setDataAndType(uri, type)
            launch.clipData = source.clipData
            launch.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP or
                (source.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(launch)
        }
        finish()
    }
}
