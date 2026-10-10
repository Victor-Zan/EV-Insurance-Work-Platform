import requests,sys,uuid,time,json,io
from openpyxl import Workbook
from datetime import datetime,timezone
from pathlib import Path
base='http://127.0.0.1:8080/api/v1'
s=requests.Session()
r=s.post(base+'/auth/login',json={'username':'dev_customer_service','password':sys.argv[1],'portal':'ADMIN'});r.raise_for_status();s.headers['Authorization']='Bearer '+r.json()['data']['accessToken']
def get(path):
 r=s.get(base+path,timeout=120);r.raise_for_status();return r.json()['data']
def post(path,body):
 r=s.post(base+path,json=body,headers={'Idempotency-Key':str(uuid.uuid4())},timeout=120);r.raise_for_status();return r.json()['data']
region=next(x['id'] for x in get('/pricing/regions?page=1&size=100')['records'] if x['code']=='DEV-DISTRICT')
claim='XLSX-'+str(uuid.uuid4())
draft=post('/work-orders/drafts',dict(insuranceCompany='合成XLSX验收',claimNo=claim,ownerName='合成车主',ownerPhone='13800001234',vehicleBrand='合成品牌',vehicleModel='测试型号',vehicleVin='XLSX-'+str(uuid.uuid4()),accidentAt=datetime.now(timezone.utc).isoformat(),accidentRegionId=region,accidentDescription='2000行独立合成导入'))
id=draft['id'];post('/work-orders/'+id+'/submit',{'confirmPossibleDuplicate':True});case=get('/work-orders/'+id)
post('/funds/cases/'+id+'/targets',{'expectedVersion':0,'direction':'RECEIVE','amount':'2000.00','reason':'合成2000行验收'})
w=Workbook();sheet=w.active;sheet.append(['transactionNo','direction','claimNo','businessNo','amount','occurredAt','note']);prefix=str(uuid.uuid4())
for n in range(2000):sheet.append([prefix+'-'+str(n),'RECEIVE',claim,case['businessNo'],'1.00','2026-10-10T10:00:00+08:00','SYNTHETIC'])
buf=io.BytesIO();w.save(buf);data=buf.getvalue();Path('../local-validation/synthetic-2000.xlsx').write_bytes(data)
results=[]
for attempt in range(2):
 start=time.perf_counter();r=s.post(base+'/funds/imports',files={'file':('synthetic-2000.xlsx',data,'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet')},timeout=120);r.raise_for_status();batch=r.json()['data'];preview=time.perf_counter()-start
 assert batch['totalRows']==2000,batch
 start=time.perf_counter();post('/funds/imports/'+batch['id']+'/confirm',{});confirm=time.perf_counter()-start
 statuses={}
 for page in range(1,101):
  for row in get('/funds/imports/'+batch['id']+'/rows?page='+str(page)+'&size=20')['records']:statuses[row['status']]=statuses.get(row['status'],0)+1
 assert statuses==({'RECORDED':2000} if attempt==0 else {'DUPLICATE':2000}),statuses
 funds=get('/funds/cases/'+id);assert funds['receivable']['net']=='2000.00',funds
 history=get('/funds/cases/'+id+'/entries?page=1&size=20');assert history['total']==2000,history['total']
 results.append(dict(attempt=attempt+1,previewSeconds=round(preview,3),confirmSeconds=round(confirm,3),statuses=statuses,net=funds['receivable']['net'],ledgerRows=history['total'],batchId=batch['id']))
Path('../local-validation/xlsx-2000-results.json').write_text(json.dumps(dict(status='PASS',caseId=id,businessNo=case['businessNo'],bytes=len(data),results=results),ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps(results))
