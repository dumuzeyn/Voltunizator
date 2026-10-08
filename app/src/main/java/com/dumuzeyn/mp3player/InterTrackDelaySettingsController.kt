package com.dumuzeyn.mp3player

import android.view.Gravity
import android.widget.LinearLayout
import android.widget.SeekBar

internal class InterTrackDelaySettingsController(private val host: MainActivityCore) {
    private val preferences get() = host.getSharedPreferences(InterTrackDelayPolicy.PREFS, 0)
    private fun seconds() = preferences.getInt(InterTrackDelayPolicy.SECONDS, 0)
        .coerceIn(0, InterTrackDelayPolicy.MAX_SECONDS)
    private fun time(seconds: Int) = "%d:%02d".format(java.util.Locale.ROOT, seconds / 60, seconds % 60)
    fun label() = host.tr("Between songs: ", "Между песнями: ") + time(seconds())

    fun openDialog() {
        val shade = host.uiFactory.shade()
        val panel = host.uiFactory.panelCard()
        panel.addView(host.uiFactory.centeredDialogTitle(host.tr("Delay between songs", "Задержка между песнями")),
            host.uiFactory.dialogTitleParams())
        val value = host.uiFactory.text(time(seconds()), 20, true).apply { gravity = Gravity.CENTER }
        panel.addView(value, LinearLayout.LayoutParams(-1, host.dp(40)))
        val slider = SeekBar(host).apply {
            max = InterTrackDelayPolicy.MAX_SECONDS
            progress = seconds()
            contentDescription = host.tr("Delay between songs", "Задержка между песнями")
        }
        host.uiFactory.applySeekBarColors(slider)
        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                preferences.edit().putInt(InterTrackDelayPolicy.SECONDS, progress).apply()
                value.text = time(progress)
                host.refreshSettingsLabels()
            }
            override fun onStartTrackingTouch(bar: SeekBar) = Unit
            override fun onStopTrackingTouch(bar: SeekBar) = Unit
        })
        panel.addView(slider, LinearLayout.LayoutParams(-1, host.dp(48)))
        panel.addView(host.uiFactory.button(host.tr("Done", "Готово")).apply {
            host.uiFactory.applyPrimaryButtonStyle(this)
            setOnClickListener { host.overlayHost.removeView(shade) }
        }, LinearLayout.LayoutParams(-1, host.dp(48)))
        shade.addView(panel, host.centerParams(host.dp(340), -2))
        host.overlayHost.addView(shade)
    }
}
