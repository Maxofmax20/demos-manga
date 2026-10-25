"""Privacy/source contract for local sync diagnostics."""
import pathlib,unittest
ROOT=pathlib.Path(__file__).resolve().parents[2]
class SyncHealthContract(unittest.TestCase):
 def test_local_success_only(self):
  text=(ROOT/'app/src/main/java/eu/kanade/tachiyomi/data/sync/DemosRemoteSyncJob.kt').read_text()
  self.assertIn('if (completed)',text)
  self.assertIn('"last_successful_upload_ms"',text)
  self.assertIn('"demos-cinema-sync-health"',text)
  self.assertNotIn('putString("backup"',text)
 def test_optin_enqueues_backup_but_not_remote_privileges(self):
  text=(ROOT/'app/src/main/java/eu/kanade/presentation/more/settings/screen/SettingsDataScreen.kt').read_text()
  self.assertIn('shareReadingHistoryWithCinema().set(enabled)',text)
  self.assertIn('SyncDataJob.startNow(context)',text)
  self.assertIn('Last phone upload',text)
  self.assertIn('No completed private background upload',text)
