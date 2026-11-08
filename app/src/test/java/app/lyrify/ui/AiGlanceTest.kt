package app.lyrify.ui

import app.lyrify.R
import app.lyrify.data.LyricsStatus
import app.lyrify.models.ModelState
import app.lyrify.models.NpuChip
import app.lyrify.models.NpuChips
import app.lyrify.overnight.AiHold
import app.lyrify.overnight.AiProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The AI tab's ring, centre, line, sheet and action for every state of the spec's table (5.6), and the Now tile. */
class AiGlanceTest {
    private val chip = NpuChips.ALL.first()
    private val ready = ModelState.Ready(TOTAL)

    private fun state(
        chip: NpuChip? = this.chip,
        newEnough: Boolean = true,
        models: ModelState? = ready,
        enabled: Boolean = true,
        progress: AiProgress? = null,
        hold: AiHold? = null,
        pluggedIn: Boolean = true,
        unrestricted: Boolean = true,
        stopping: Boolean = false,
    ) = AiState(chip, newEnough, models, enabled, progress, hold, pluggedIn, unrestricted, stopping)

    private fun glance(state: AiState, waiting: Int = WAITING) = aiGlance(state, waiting)

    private fun text(res: Int, vararg args: GlanceArg) = GlanceText(res, args.toList())

    private val empty = Ring(0f, RingColor.HIGHLIGHT)

    // Phones the AI does not run on

    @Test
    fun `no chip and old software show an illustration and a line, nothing else`() {
        assertEquals(
            AiGlance(null, Centre.Icon(R.drawable.ic_memory), text(R.string.ai_no_chip), text(R.string.ai_no_chip_detail), null),
            glance(state(chip = null)),
        )
        assertEquals(
            AiGlance(null, Centre.Icon(R.drawable.ic_system_update), text(R.string.ai_unsupported), text(R.string.ai_unsupported_detail), null),
            glance(state(newEnough = false)),
        )
        assertFalse(state(chip = null).supported)
        assertFalse(state(newEnough = false).supported)
        assertTrue(state().supported)
    }

    @Test
    fun `switched off, the glyph and one line, the switch being the action`() {
        assertEquals(AiGlance(null, Centre.Glyph, text(R.string.ai_off), text(R.string.ai_intro), null), glance(state(enabled = false, models = ModelState.Failed(0, TOTAL))))
    }

    // The download, while the AI files are not ready

    @Test
    fun `not downloaded`() {
        assertEquals(
            AiGlance(
                empty,
                Centre.Icon(R.drawable.ic_download, CentreValue.Bytes(TOTAL)),
                text(R.string.ai_models_missing),
                text(R.string.ai_models_missing_detail, GlanceArg.Bytes(TOTAL)),
                GlanceAction(AiActionKind.DOWNLOAD, text(R.string.ai_download, GlanceArg.Bytes(TOTAL))),
            ),
            glance(state(models = ModelState.Missing(0, TOTAL))),
        )
    }

    @Test
    fun `download paused part way`() {
        assertEquals(
            AiGlance(
                Ring(0.25f, RingColor.OUTLINE),
                Centre.Value(CentreValue.Percent(25)),
                text(R.string.ai_models_partial),
                text(R.string.ai_models_partial_detail, GlanceArg.Bytes(QUARTER), GlanceArg.Bytes(TOTAL)),
                GlanceAction(AiActionKind.RESUME, text(R.string.ai_resume)),
            ),
            glance(state(models = ModelState.Missing(QUARTER, TOTAL))),
        )
    }

    @Test
    fun `downloading`() {
        assertEquals(
            AiGlance(
                Ring(0.25f, RingColor.HIGHLIGHT),
                Centre.Value(CentreValue.Percent(25)),
                text(R.string.ai_downloading, GlanceArg.Bytes(QUARTER), GlanceArg.Bytes(TOTAL)),
                null,
                null,
            ),
            glance(state(models = ModelState.Downloading(QUARTER, TOTAL, waiting = false))),
        )
    }

    @Test
    fun `waiting for Wi-Fi`() {
        assertEquals(
            AiGlance(
                Ring(0.25f, RingColor.OUTLINE),
                Centre.Icon(R.drawable.ic_wifi, CentreValue.Percent(25)),
                text(R.string.ai_models_waiting),
                text(R.string.ai_models_waiting_detail, GlanceArg.Bytes(QUARTER), GlanceArg.Bytes(TOTAL)),
                null,
            ),
            glance(state(models = ModelState.Downloading(QUARTER, TOTAL, waiting = true))),
        )
    }

    @Test
    fun `no room`() {
        assertEquals(
            AiGlance(
                Ring(1f, RingColor.OUTLINE),
                Centre.Icon(R.drawable.ic_storage),
                text(R.string.ai_models_no_room, GlanceArg.Bytes(NEEDED)),
                text(R.string.ai_models_no_room_detail),
                null,
            ),
            glance(state(models = ModelState.NoRoom(0, TOTAL, NEEDED))),
        )
    }

    @Test
    fun `download failed`() {
        assertEquals(
            AiGlance(
                Ring(1f, RingColor.ERROR),
                Centre.Icon(R.drawable.ic_error, tint = RingColor.ERROR),
                text(R.string.ai_models_failed),
                text(R.string.ai_models_failed_detail),
                GlanceAction(AiActionKind.TRY_AGAIN, text(R.string.ai_try_again)),
            ),
            glance(state(models = ModelState.Failed(QUARTER, TOTAL))),
        )
    }

    // Tonight, once the AI files are ready

    @Test
    fun `working`() {
        assertEquals(
            AiGlance(
                Ring(0.3f, RingColor.HIGHLIGHT),
                Centre.Value(CentreValue.Fraction(12, 40), R.string.ai_tonight_label),
                text(R.string.ai_now, GlanceArg.Text("Song A")),
                null,
                null,
            ),
            glance(state(progress = AiProgress(12, 40, "Song A"))),
        )
        // Between songs: no title yet.
        assertEquals(text(R.string.ai_notification_starting), glance(state(progress = AiProgress(0, 40, null))).line)
    }

    @Test
    fun `too warm`() {
        assertEquals(
            AiGlance(
                Ring(0.3f, RingColor.OUTLINE),
                Centre.Icon(R.drawable.ic_thermostat, CentreValue.Fraction(12, 40)),
                text(R.string.ai_hot),
                text(R.string.ai_hot_detail),
                null,
            ),
            glance(state(progress = AiProgress(12, 40, null, hot = true))),
        )
    }

    @Test
    fun `waiting for the charger and starting soon`() {
        assertEquals(
            AiGlance(
                empty,
                Centre.Icon(R.drawable.ic_power, CentreValue.Count(WAITING)),
                text(R.string.ai_waiting_charger),
                GlanceText(R.plurals.ai_waiting_charger_detail, listOf(GlanceArg.Number(WAITING)), quantity = WAITING),
                null,
            ),
            glance(state(pluggedIn = false)),
        )
        assertEquals(
            AiGlance(
                empty,
                Centre.Icon(R.drawable.ic_schedule, CentreValue.Count(WAITING)),
                text(R.string.ai_waiting),
                GlanceText(R.plurals.ai_waiting_detail, listOf(GlanceArg.Number(WAITING)), quantity = WAITING),
                null,
            ),
            glance(state()),
        )
    }

    @Test
    fun `holds`() {
        assertEquals(
            AiGlance(
                Ring(1f, RingColor.ERROR),
                Centre.Icon(R.drawable.ic_block, tint = RingColor.ERROR),
                text(R.string.ai_held_not_allowed),
                text(R.string.ai_held_not_allowed_detail),
                GlanceAction(AiActionKind.OPEN_APP_INFO, text(R.string.open_app_info)),
            ),
            glance(state(hold = AiHold.NOT_ALLOWED)),
        )
        assertEquals(
            AiGlance(Ring(1f, RingColor.OUTLINE), Centre.Icon(R.drawable.ic_pause_circle), text(R.string.ai_held_limit), text(R.string.ai_held_limit_detail), null),
            glance(state(hold = AiHold.DAILY_LIMIT)),
        )
        assertEquals(
            AiGlance(
                Ring(1f, RingColor.ERROR),
                Centre.Icon(R.drawable.ic_error, tint = RingColor.ERROR),
                text(R.string.ai_held_model),
                text(R.string.ai_held_model_detail),
                null,
            ),
            glance(state(hold = AiHold.MODEL_FAILED)),
        )
        assertEquals(
            AiGlance(
                Ring(1f, RingColor.ERROR),
                Centre.Icon(R.drawable.ic_error, tint = RingColor.ERROR),
                text(R.string.ai_held_crashed),
                text(R.string.ai_held_crashed_detail),
                null,
            ),
            glance(state(hold = AiHold.CRASHED)),
        )
        assertEquals(
            AiGlance(Ring(1f, RingColor.OUTLINE), Centre.Icon(R.drawable.ic_memory), text(R.string.ai_held_npu), text(R.string.ai_held_npu_detail), null),
            glance(state(hold = AiHold.NO_NPU)),
        )
    }

    @Test
    fun `all caught up`() {
        assertEquals(
            AiGlance(
                Ring(1f, RingColor.SYNCED),
                Centre.Icon(R.drawable.ic_check, tint = RingColor.SYNCED),
                text(R.string.ai_all_done),
                text(R.string.ai_all_done_detail),
                null,
            ),
            glance(state(), waiting = 0),
        )
    }

    @Test
    fun `every state has one ring, one line and at most one action`() {
        val models = listOf(
            null,
            ModelState.Missing(0, TOTAL),
            ModelState.Missing(QUARTER, TOTAL),
            ModelState.Downloading(QUARTER, TOTAL, waiting = false),
            ModelState.Downloading(QUARTER, TOTAL, waiting = true),
            ModelState.NoRoom(QUARTER, TOTAL, NEEDED),
            ModelState.Ready(TOTAL),
            ModelState.Failed(0, TOTAL),
        )
        val progress = listOf(null, AiProgress(1, 2, "Song A"), AiProgress(1, 2, null, hot = true))
        val holds = listOf<AiHold?>(null) + AiHold.entries
        for (m in models) for (p in progress) for (h in holds) for (plugged in listOf(true, false)) for (waiting in listOf(0, 3)) {
            val g = glance(state(models = m, progress = p, hold = h, pluggedIn = plugged), waiting)
            assertTrue("$m $p $h", g.ring != null)
            assertTrue("$m $p $h", g.line.res != 0)
        }
    }

    @Test
    fun `the download states show while the AI files are not ready, whatever tonight would be`() {
        assertEquals(text(R.string.ai_models_missing), glance(state(models = ModelState.Missing(0, TOTAL), hold = AiHold.DAILY_LIMIT, pluggedIn = false)).line)
    }

    // The Now tile

    @Test
    fun `the AI tile shows while AI lyrics are on`() {
        assertNull(aiTile(state(enabled = false), WAITING))
        assertNull(aiTile(state(chip = null), WAITING))
        assertEquals(AiTile(Ring(0.3f, RingColor.HIGHLIGHT), TileValue.Fraction(12, 40), R.string.ai_tile_tonight), aiTile(state(progress = AiProgress(12, 40, "Song A")), WAITING))
        assertEquals(AiTile(Ring(0.3f, RingColor.OUTLINE), TileValue.Fraction(12, 40), R.string.ai_tile_hot), aiTile(state(progress = AiProgress(12, 40, null, hot = true)), WAITING))
        assertEquals(AiTile(empty, TileValue.Count(WAITING), R.string.ai_tile_charger), aiTile(state(pluggedIn = false), WAITING))
        assertEquals(AiTile(empty, TileValue.Count(WAITING), R.string.ai_tile_soon), aiTile(state(), WAITING))
        assertEquals(AiTile(empty, TileValue.Text(R.string.ai_tile_get), R.string.ai_tile_download), aiTile(state(models = ModelState.Missing(0, TOTAL)), WAITING))
        assertEquals(
            AiTile(Ring(0.25f, RingColor.HIGHLIGHT), TileValue.Percent(25), R.string.ai_tile_download),
            aiTile(state(models = ModelState.Downloading(QUARTER, TOTAL, waiting = false)), WAITING),
        )
        assertEquals(AiTile(Ring(1f, RingColor.OUTLINE), TileValue.Text(R.string.ai_tile_paused), R.string.ai_tile_today), aiTile(state(hold = AiHold.DAILY_LIMIT), WAITING))
        assertEquals(R.string.ai_tile_battery, aiTile(state(hold = AiHold.NOT_ALLOWED), WAITING)?.label)
        assertEquals(R.string.ai_tile_update, aiTile(state(hold = AiHold.MODEL_FAILED), WAITING)?.label)
        assertEquals(R.string.ai_tile_update, aiTile(state(hold = AiHold.CRASHED), WAITING)?.label)
        assertEquals(R.string.ai_tile_chip, aiTile(state(hold = AiHold.NO_NPU), WAITING)?.label)
        assertEquals(AiTile(Ring(1f, RingColor.SYNCED), TileValue.Text(R.string.ai_all_done), R.string.ai_title), aiTile(state(), 0))
    }

    // Results and attention

    @Test
    fun `results list the AI statuses with songs`() {
        val counts = mapOf(
            LyricsStatus.AI_FAILED to 8,
            LyricsStatus.AI_ALIGNED to 74,
            LyricsStatus.AI_TRANSCRIBED to 37,
            LyricsStatus.AI_REJECTED to 0,
            LyricsStatus.AI_INSTRUMENTAL to 9,
            LyricsStatus.SYNCED to 500,
        )
        assertEquals(
            listOf(LyricsStatus.AI_ALIGNED to 74, LyricsStatus.AI_TRANSCRIBED to 37, LyricsStatus.AI_INSTRUMENTAL to 9, LyricsStatus.AI_FAILED to 8),
            aiResults(counts),
        )
        assertEquals(emptyList<Pair<LyricsStatus, Int>>(), aiResults(mapOf(LyricsStatus.SYNCED to 3)))
    }

    @Test
    fun `the AI tab asks for attention when the user can fix something`() {
        assertTrue(aiAttention(state(hold = AiHold.NOT_ALLOWED), WAITING))
        assertTrue(aiAttention(state(models = ModelState.Failed(0, TOTAL)), WAITING))
        assertTrue(aiAttention(state(models = ModelState.NoRoom(0, TOTAL, NEEDED)), WAITING))
        assertFalse(aiAttention(state(), WAITING))
        assertFalse(aiAttention(state(hold = AiHold.DAILY_LIMIT), WAITING))
        assertFalse(aiAttention(state(models = ModelState.Downloading(QUARTER, TOTAL, waiting = true)), WAITING))
        // Unplugged: the hold is not what the tab shows.
        assertFalse(aiAttention(state(hold = AiHold.NOT_ALLOWED, pluggedIn = false), WAITING))
        assertFalse(aiAttention(state(chip = null, models = ModelState.Failed(0, TOTAL)), WAITING))
    }

    @Test
    fun `a full phone is no news while the user never asked for the AI`() {
        assertFalse(aiAttention(state(enabled = false, models = ModelState.NoRoom(0, TOTAL, NEEDED)), WAITING))
        assertTrue(aiAttention(state(enabled = false, models = ModelState.NoRoom(QUARTER, TOTAL, NEEDED)), WAITING))
        assertTrue(aiAttention(state(enabled = false, models = ModelState.Failed(0, TOTAL)), WAITING))
    }

    private companion object {
        const val TOTAL: Long = 1_000_000
        const val QUARTER: Long = 250_000
        const val NEEDED: Long = 2_000_000
        const val WAITING: Int = 41
    }
}
