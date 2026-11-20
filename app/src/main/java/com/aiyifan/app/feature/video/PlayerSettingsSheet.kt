package com.aiyifan.app.feature.video

import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.DefaultTrackNameProvider
import androidx.media3.ui.PlayerView
import com.aiyifan.app.R
import com.aiyifan.app.core.ui.createOptionsSheet
import com.google.android.material.bottomsheet.BottomSheetDialog
import androidx.media3.ui.R as MediaR
import kotlin.math.abs

/** Routes Media3 control buttons to bottom sheets while keeping the player's selection rules. */
@UnstableApi
internal class PlayerSettingsSheet(
    private val activity: AppCompatActivity,
    private val playerView: PlayerView,
) : DefaultLifecycleObserver {
    private var dialog: BottomSheetDialog? = null

    init {
        activity.lifecycle.addObserver(this)
        playerView.findViewById<View>(MediaR.id.exo_settings)?.setOnClickListener {
            playerView.player?.let(::showSettings)
        }
        playerView.findViewById<View>(MediaR.id.exo_subtitle)?.setOnClickListener {
            playerView.player?.let { player -> showTracks(player, C.TRACK_TYPE_TEXT) }
        }
    }

    override fun onStop(owner: LifecycleOwner) {
        dialog?.dismiss()
        dialog = null
    }

    private fun showSettings(player: Player) {
        val entries = buildList<Pair<Int, () -> Unit>> {
            if (player.isCommandAvailable(Player.COMMAND_SET_SPEED_AND_PITCH)) {
                add(MediaR.string.exo_controls_playback_speed to {
                    showSpeed(
                        SPEEDS,
                        activity.resources.getStringArray(MediaR.array.exo_controls_playback_speeds).toList(),
                        MediaR.string.exo_controls_playback_speed,
                    )
                })
            }
            if (player.isCommandAvailable(Player.COMMAND_GET_TRACKS) &&
                player.isCommandAvailable(Player.COMMAND_SET_TRACK_SELECTION_PARAMETERS)
            ) {
                add(MediaR.string.exo_track_selection_title_audio to { showTracks(player, C.TRACK_TYPE_AUDIO) })
            }
        }
        if (entries.isEmpty()) return
        show(activity.createOptionsSheet(
            activity.getString(MediaR.string.exo_controls_settings_description),
            entries.map { activity.getString(it.first) },
        ) { entries[it].second() })
    }

    fun showSpeed(speeds: List<Float>, labels: List<String>, title: Int) {
        val player = playerView.player ?: return
        val selected = speeds.indices.minBy { abs(speeds[it] - player.playbackParameters.speed) }
        show(activity.createOptionsSheet(activity.getString(title), labels, selectedIndex = selected) { index ->
            if (playerView.player === player && player.isCommandAvailable(Player.COMMAND_SET_SPEED_AND_PITCH)) {
                player.setPlaybackSpeed(speeds[index])
            }
        })
    }

    private fun showTracks(player: Player, type: Int) {
        if (!player.isCommandAvailable(Player.COMMAND_GET_TRACKS) ||
            !player.isCommandAvailable(Player.COMMAND_SET_TRACK_SELECTION_PARAMETERS)
        ) return
        val subtitles = type == C.TRACK_TYPE_TEXT
        val nameProvider = DefaultTrackNameProvider(activity.resources)
        val choices = buildList {
            player.currentTracks.groups.filter { it.type == type }.forEach { group ->
                for (index in 0 until group.length) {
                    val format = group.getTrackFormat(index)
                    if (group.isTrackSupported(index) && format.selectionFlags and C.SELECTION_FLAG_FORCED == 0) {
                        add(TrackChoice(group, index, nameProvider.getTrackName(format)))
                    }
                }
            }
        }
        val parameters = player.trackSelectionParameters
        val selection = choices.indexOfFirst {
            it.group.isTrackSelected(it.index) && (subtitles || parameters.overrides.containsKey(it.group.mediaTrackGroup))
        } + 1
        val title = if (subtitles) R.string.player_subtitle_tracks else MediaR.string.exo_track_selection_title_audio
        val default = if (subtitles || choices.isEmpty()) MediaR.string.exo_track_selection_none else MediaR.string.exo_track_selection_auto
        show(activity.createOptionsSheet(
            activity.getString(title),
            listOf(activity.getString(default)) + choices.map(TrackChoice::label),
            selectedIndex = selection,
        ) { index ->
            if (playerView.player !== player ||
                !player.isCommandAvailable(Player.COMMAND_SET_TRACK_SELECTION_PARAMETERS)
            ) return@createOptionsSheet
            val builder = player.trackSelectionParameters.buildUpon().clearOverridesOfType(type)
            val choice = choices.getOrNull(index - 1)
            if (choice != null) {
                builder.setOverrideForType(TrackSelectionOverride(choice.group.mediaTrackGroup, choice.index))
                    .setTrackTypeDisabled(type, false)
            } else if (subtitles) {
                // Keep forced subtitles available when the user turns regular subtitles off.
                builder.setIgnoredTextSelectionFlags(C.SELECTION_FLAG_FORCED.inv())
            } else {
                builder.setTrackTypeDisabled(type, false)
            }
            player.trackSelectionParameters = builder.build()
        })
    }

    private fun show(sheet: BottomSheetDialog) {
        dialog?.dismiss()
        dialog = sheet
        sheet.setOnDismissListener { if (dialog === sheet) dialog = null }
        sheet.show()
    }

    private data class TrackChoice(val group: Tracks.Group, val index: Int, val label: String)

    private companion object {
        val SPEEDS = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
    }
}
