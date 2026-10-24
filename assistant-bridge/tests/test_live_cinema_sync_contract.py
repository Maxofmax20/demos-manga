"""Source-level privacy contract: no automatic uploads without phone consent."""
import pathlib,unittest
ROOT=pathlib.Path(__file__).resolve().parents[2]
class LiveCinemaContractTests(unittest.TestCase):
 def test_background_requires_owner_consent_and_private_service(self):
  text=(ROOT/'app/src/main/java/eu/kanade/tachiyomi/data/sync/DemosRemoteSyncJob.kt').read_text()
  self.assertIn('!preferences.unattendedCloudSync().get()',text)
  self.assertIn('SyncManager.SyncService.SYNCYOMI.value',text)
  self.assertIn('SyncManager(context).syncData()',text)
  self.assertIn('PeriodicWorkRequestBuilder<DemosRemoteSyncJob>(15, TimeUnit.MINUTES)',text)
  self.assertIn('if (request != null)',text)
 def test_reader_respects_private_mode_and_owner_consent(self):
  text=(ROOT/'app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderActivity.kt').read_text()
  self.assertIn('!preferences.incognitoMode().get()',text)
  self.assertIn('privateCinemaSyncPreferences.unattendedCloudSync().get()',text)
  self.assertIn('SyncDataJob.startNow(this@ReaderActivity)',text)
  self.assertIn('now - last > 120_000L',text)
 def test_separate_dated_history_permission_remains_off_by_default(self):
  text=(ROOT/'app/src/main/java/eu/kanade/domain/sync/SyncPreferences.kt').read_text()
  self.assertIn('demos_share_reading_history_cinema", false',text)
  text=(ROOT/'app/src/main/java/eu/kanade/tachiyomi/data/sync/SyncManager.kt').read_text()
  self.assertIn('syncPreferences.shareReadingHistoryWithCinema().get()',text)
