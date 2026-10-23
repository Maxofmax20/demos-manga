"""Exercise the actual Android journal DDL and membership FK rollback on SQLite.
This is persistence verification, not a claim of physical Android execution.
"""
import json,sqlite3,tempfile,unittest
from pathlib import Path
class CategorySqlRecoveryTests(unittest.TestCase):
 def setUp(self):
  self.tmp=tempfile.TemporaryDirectory();self.addCleanup(self.tmp.cleanup);self.path=Path(self.tmp.name)/'android.db'
  root=Path(__file__).resolve().parents[2]
  journal=(root/'data/src/main/sqldelight/tachiyomi/migrations/48.sqm').read_text()
  memberships=(root/'data/src/main/sqldelight/tachiyomi/data/mangas_categories.sq').read_text().split('insert:')[0]
  with self.open() as db:
   db.executescript('CREATE TABLE mangas(_id INTEGER PRIMARY KEY,version INTEGER NOT NULL DEFAULT 0,is_syncing INTEGER NOT NULL DEFAULT 0);CREATE TABLE categories(_id INTEGER PRIMARY KEY);')
   db.executescript(memberships);db.executescript(journal)
   db.executemany('INSERT INTO mangas(_id) VALUES(?)',[(1,),(2,)])
   db.executemany('INSERT INTO categories VALUES(?)',[(7,),(9,)])
   db.executemany('INSERT INTO mangas_categories(manga_id,category_id) VALUES(?,?)',[(1,7),(2,7)])
 def open(self):
  db=sqlite3.connect(self.path);db.execute('PRAGMA foreign_keys=ON');return db
 def memberships(self):
  with self.open() as db:return db.execute('SELECT manga_id,category_id FROM mangas_categories ORDER BY manga_id,category_id').fetchall()
 def test_late_batch_failure_rolls_back_memberships_and_journal(self):
  with self.assertRaises(sqlite3.IntegrityError):
   with self.open() as db:
    db.execute('DELETE FROM mangas_categories WHERE manga_id=1')
    db.execute('INSERT INTO mangas_categories(manga_id,category_id) VALUES(1,9)')
    db.execute("INSERT INTO remote_category_commits VALUES('command','preview','before','after',1)")
    db.execute('INSERT INTO mangas_categories(manga_id,category_id) VALUES(2,999)')
  self.assertEqual(self.memberships(),[(1,7),(2,7)])
  with self.open() as db:self.assertEqual(db.execute('SELECT count(*) FROM remote_category_commits').fetchone()[0],0)
 def test_commit_and_recovery_journal_survive_reopen(self):
  with self.open() as db:
   db.execute('INSERT INTO mangas_categories(manga_id,category_id) VALUES(1,9)')
   db.execute("INSERT INTO remote_category_commits VALUES('command','preview','before','after',1)")
  with self.open() as reopened:
   self.assertEqual(reopened.execute("SELECT before_json,after_json FROM remote_category_commits WHERE command_id='command'").fetchone(),('before','after'))
  self.assertEqual(self.memberships(),[(1,7),(1,9),(2,7)])
