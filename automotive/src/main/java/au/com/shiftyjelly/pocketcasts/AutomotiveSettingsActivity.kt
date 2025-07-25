package au.com.shiftyjelly.pocketcasts

import android.os.Bundle
import android.view.View
import android.widget.ImageView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.fragment.app.Fragment
import au.com.shiftyjelly.pocketcasts.models.type.EpisodeViewSource
import au.com.shiftyjelly.pocketcasts.ui.helper.FragmentHostListener
import com.google.android.material.bottomsheet.BottomSheetBehavior
import dagger.hilt.android.AndroidEntryPoint
import kotlin.time.Duration
import au.com.shiftyjelly.pocketcasts.images.R as IR
import au.com.shiftyjelly.pocketcasts.profile.R as PR

@AndroidEntryPoint
class AutomotiveSettingsActivity :
    AppCompatActivity(),
    FragmentHostListener {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_automotive_settings)

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(false) {
                override fun handleOnBackPressed() {
                    handleBackPressed()
                }
            },
        )

        val settingsFragment = AutomotiveSettingsFragment()
        supportFragmentManager.beginTransaction().replace(R.id.frameMain, settingsFragment).commitNowAllowingStateLoss()

        val btnClose = findViewById<ImageView>(PR.id.btnClose)
        btnClose?.setImageResource(IR.drawable.ic_arrow_back)
        btnClose?.setOnClickListener { handleBackPressed() }
    }

    override fun setSupportActionBar(toolbar: Toolbar?) {
        super.setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)
    }

    override fun onSupportNavigateUp(): Boolean {
        handleBackPressed()
        return true
    }

    fun addFragment(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.frameMain, fragment)
            .addToBackStack(null)
            .commitAllowingStateLoss()
    }

    private fun handleBackPressed() {
        if (supportFragmentManager.backStackEntryCount > 0) {
            supportFragmentManager.popBackStack()
            return
        }
        onBackPressedDispatcher.onBackPressed()
    }

    // TODO: Refactor FragmentHostListener in to something more generic so it can be used
    // cleaner between automotive and main
    override fun addFragment(fragment: Fragment, onTop: Boolean) {
        addFragment(fragment)
    }

    override fun replaceFragment(fragment: Fragment) {
        addFragment(fragment)
    }

    override fun showBottomSheet(fragment: Fragment) {
        addFragment(fragment)
    }

    override fun bottomSheetClosePressed(fragment: Fragment) {
        handleBackPressed()
    }

    override fun openPlayer() {
    }

    override fun closePlayer() {
    }

    override fun showModal(fragment: Fragment) {
        addFragment(fragment)
    }

    override fun closeModal(fragment: Fragment) {
        handleBackPressed()
    }

    override fun openTab(tabId: Int) {
    }

    override fun closeToRoot() {
    }

    override fun closePodcastsToRoot() {
    }

    override fun openPodcastPage(uuid: String, sourceView: String?) {
    }

    override fun openCloudFiles() {
    }

    override fun snackBarView(): View {
        return findViewById(android.R.id.content)
    }

    override fun setFullScreenDarkOverlayViewVisibility(visible: Boolean) {
    }

    override fun showAccountUpgradeNow(autoSelectPlus: Boolean) {
    }

    override fun updateStatusBar() {
    }

    override fun getPlayerBottomSheetState(): Int {
        return 0
    }

    override fun addPlayerBottomSheetCallback(callback: BottomSheetBehavior.BottomSheetCallback) = Unit

    override fun removePlayerBottomSheetCallback(callback: BottomSheetBehavior.BottomSheetCallback) = Unit

    override fun openEpisodeDialog(
        episodeUuid: String?,
        source: EpisodeViewSource,
        podcastUuid: String?,
        forceDark: Boolean,
        autoPlay: Boolean,
        startTimestamp: Duration?,
        endTimestamp: Duration?,
    ) {
    }

    override fun lockPlayerBottomSheet(locked: Boolean) {
    }

    override fun updateSystemColors() {
    }

    override fun overrideNextRefreshTimer() {
    }

    override fun isUpNextShowing(): Boolean {
        return false
    }

    override fun showStoriesOrAccount(source: String) {
    }

    override fun whatsNewDismissed(fromConfirmAction: Boolean) {
    }
}
