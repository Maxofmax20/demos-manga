import unittest,tempfile,copy
from pathlib import Path
import categories,command_queue
class CategoryTests(unittest.TestCase):
 def setUp(self):
  self.tmp=tempfile.TemporaryDirectory();self.addCleanup(self.tmp.cleanup);self.db=Path(self.tmp.name)/'q.db';command_queue.initialize(self.db)
  self.snapshot={'revision':'a'*64,'scopes':{s:s in ['read_library','organize_library'] for s in categories.SCOPES},'categories':[{'id':'0','name':'','order':-1,'hidden':False,'flags':'0'},{'id':'7','name':'قيد القراءة','order':0,'hidden':False,'flags':'0'},{'id':'9','name':'مكتملة ✓','order':1,'hidden':False,'flags':'0'}], 'manga':[{'id':f'{i:024x}','title':f'عنوان {i}','category_ids':['7']} for i in range(219)]}
  categories.store(self.db,'phone',self.snapshot)
 def preview(self,mode='add',ids=None):return categories.preview(self.db,'phone','a'*64,[{'manga_id':'0'*24,'category_ids':ids if ids is not None else ['9'],'mode':mode}])
 def test_unicode_and_full_pagination(self):
  self.assertEqual(categories.snapshot(self.db,'phone')['categories'],self.snapshot['categories'])
  items=[]
  for offset in [0,100,200]:items+=categories.page(self.db,'phone',offset)['items']
  self.assertEqual(len(items),219);self.assertEqual(len({m['id'] for m in items}),219)
 def test_multi_category_add_remove_replace(self):
  self.assertEqual(self.preview()['changes'][0]['after'],['7','9'])
  self.assertEqual(self.preview('remove',['7'])['changes'][0]['after'],[])
  self.assertEqual(self.preview('replace',['9'])['changes'][0]['after'],['9'])
 def test_preview_never_edits_snapshot(self):
  p=self.preview();self.assertEqual(categories.page(self.db,'phone',0,1)['items'][0]['category_ids'],['7'])
  self.assertEqual(p['state'],'preview_only')
 def test_exact_approval_idempotent_queue(self):
  p=self.preview()
  with self.assertRaises(ValueError):categories.apply(self.db,'phone',p['preview_id'],False,command_queue)
  first=categories.apply(self.db,'phone',p['preview_id'],True,command_queue)
  second=categories.apply(self.db,'phone',p['preview_id'],True,command_queue)
  self.assertEqual(first,second);self.assertEqual(first['state'],'pending_confirmation')
  self.assertEqual(len(command_queue.pending(self.db,'phone')),1)
 def test_phone_edits_conflict(self):
  p=self.preview();self.snapshot['revision']='b'*64;categories.store(self.db,'phone',self.snapshot)
  with self.assertRaises(ValueError):categories.apply(self.db,'phone',p['preview_id'],True,command_queue)
 def test_scope_revocation(self):
  p=self.preview();self.snapshot['scopes']['organize_library']=False;categories.store(self.db,'phone',self.snapshot)
  with self.assertRaises(PermissionError):categories.apply(self.db,'phone',p['preview_id'],True,command_queue)
 def test_invalid_duplicate_default_ids(self):
  for ids in [['9','9'],['0'],['123'],[9]]:
   with self.subTest(ids=ids),self.assertRaises(ValueError):self.preview(ids=ids)
 def test_reorder_exact_and_default_fixed(self):
  with self.assertRaises(ValueError):categories.preview(self.db,'phone','a'*64,[],['7'])
  p=categories.preview(self.db,'phone','a'*64,[],['9','7']);self.assertEqual(p['category_order'],['9','7'])
 def test_invalid_snapshots_atomic(self):
  broken=copy.deepcopy(self.snapshot);broken['manga'][1]['id']=broken['manga'][0]['id']
  with self.assertRaises(ValueError):categories.store(self.db,'phone',broken)
  self.assertEqual(categories.page(self.db,'phone')['total'],219)
