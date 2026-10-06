package com.dumuzeyn.mp3player

import android.text.TextUtils
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView

/** One remembered queue action with a long-press mode picker. */
internal class RandomQueueSection(private val host: MainActivityCore) : LinearLayout(host) {
    private val preferences = host.getSharedPreferences("mp3_player_ui", 0)
    private var mode = QueueCreationMode.fromStored(preferences.getString(QueueCreationMode.PREFERENCE, null))
    private val count = RandomQueueCountView(host, host.libraryState.tracks.size)
    private val create = host.uiFactory.button(label()).apply {
        id = R.id.random_queue_button
        setSingleLine(true)
        ellipsize = TextUtils.TruncateAt.END
        contentDescription = label()
        host.uiFactory.applyPrimaryButtonStyle(this)
        setOnClickListener { host.playbackQueueController.playGenerated(mode, count.value) }
        setOnLongClickListener { chooseMode(); true }
    }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL

        addView(
            create,
            LayoutParams(0, host.uiFactory.libraryCardHeight(), 1f).apply {
                setMargins(0, host.dp(2), host.dp(4), host.dp(2))
            },
        )
        addView(
            count,
            LayoutParams(host.dp(68), host.uiFactory.libraryCardHeight()).apply {
                setMargins(0, host.dp(2), 0, host.dp(2))
            },
        )
    }

    private fun label(): String = host.tr(mode.english, mode.russian)

    private fun chooseMode() {
        val shade = host.uiFactory.shade()
        val panel = host.uiFactory.panelCard()
        panel.addView(host.uiFactory.centeredDialogTitle(host.tr("Queue mode", "Режим очереди")),
            host.uiFactory.dialogTitleParams())
        val rows = LinearLayout(host).apply { orientation = VERTICAL }
        QueueCreationMode.entries.forEach { option ->
            rows.addView(host.uiFactory.button(host.tr(option.english, option.russian)).apply {
                tag = option
                setSingleLine(true)
                ellipsize = TextUtils.TruncateAt.END
                if (option == mode) host.uiFactory.applyPrimaryButtonStyle(this)
                else host.uiFactory.applySecondaryButtonStyle(this)
                setOnClickListener {
                    mode = option
                    preferences.edit().putString(QueueCreationMode.PREFERENCE, mode.name).apply()
                    create.text = label()
                    create.contentDescription = label()
                    host.overlayHost.removeView(shade)
                }
            }, LayoutParams(-1, host.dp(52)).apply { setMargins(0, host.dp(2), 0, host.dp(2)) })
        }
        panel.addView(ScrollView(host).apply { addView(rows) }, LayoutParams(-1, 0, 1f))
        panel.addView(host.uiFactory.button(host.tr("Cancel", "Отмена")).apply {
            setOnClickListener { host.overlayHost.removeView(shade) }
        }, LayoutParams(-1, host.dp(48)))
        shade.addView(panel, host.centerParams(host.dp(340), host.dp(360)))
        host.overlayHost.addView(shade)
    }
}
