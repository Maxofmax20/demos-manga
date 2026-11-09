package eu.kanade.tachiyomi.ui.setting.track

import android.net.Uri
import android.widget.Toast
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.sy.SYMR

// KMK -->
// Compatibility handler for callbacks from the old OAuth flow: it must never crash the app.
class GoogleDriveLoginActivity : BaseOAuthLoginActivity() {
    override fun handleResult(uri: Uri) {
        Toast.makeText(this, stringResource(SYMR.strings.google_drive_not_signed_in), Toast.LENGTH_LONG).show()
        returnToSettings()
    }
}
// KMK <--
