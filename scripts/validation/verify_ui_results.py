import sys,requests,json
from pathlib import Path
b='http://127.0.0.1:8080/api/v1';f=json.loads(Path('../local-validation/ui-review-fixtures.json').read_text(encoding='utf-8'));s=requests.Session()
def login(u,p):
 r=s.post(b+'/auth/login',json={'username':u,'password':sys.argv[1],'portal':p});r.raise_for_status();return r.json()['data']['accessToken']
tokens={x:login(u,p) for x,u,p in [('cs','dev_customer_service','ADMIN'),('owner','dev_owner','H5'),('shop','dev_repair_shop','H5')]}
def get(path,role='cs'):
 r=s.get(b+path,headers={'Authorization':'Bearer '+tokens[role]});r.raise_for_status();return r.json()['data']
quote=get('/quotations/cases/'+f['activeId'])['rawQuote'];assert quote['total']=='3.69' and quote['lines'][0]['quantity']==3 and quote['lines'][0]['unitPrice']=='1.23'
funds=get('/funds/cases/'+f['completedId']);assert funds['receivable']['net']=='121.00';assert get('/funds/cases/'+f['completedId']+'/entries')['total']==6
rows=get('/funds/imports/'+f['batchId']+'/rows')['records'];assert rows[0]['status']=='RECORDED'
complaints=get('/complaints/cases/'+f['completedId'],'owner')['records'];created=next(x for x in complaints if x['description']=='合成照片选择与投诉界面验收');detail=get('/complaints/'+str(created['id']),'owner');assert len(detail['photos'])==1
batches=get('/funds/imports')['records'];uploaded=next(x for x in batches if x['fileName']=='synthetic-2000.xlsx');assert uploaded['totalRows']==2000
forbidden=s.get(b+'/funds/cases/'+f['completedId'],headers={'Authorization':'Bearer '+tokens['owner']});assert forbidden.status_code==403
result={'status':'PASS','rawQuoteTotal':'3.69','receivableNet':'121.00','ledgerCount':6,'manualMatchStatus':rows[0]['status'],'complaintPhotoCount':len(detail['photos']),'xlsxPreviewRows':uploaded['totalRows'],'ownerFundsStatus':403}
Path('docs/validation/usability-visual/ui-api-results.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8');print(json.dumps(result))
