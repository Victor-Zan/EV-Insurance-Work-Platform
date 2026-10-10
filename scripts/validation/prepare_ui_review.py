import requests,sys,uuid,json
from pathlib import Path
from datetime import datetime,timezone
b='http://127.0.0.1:8080/api/v1';s=requests.Session()
def login(user,portal):
 r=s.post(b+'/auth/login',json={'username':user,'password':sys.argv[1],'portal':portal});r.raise_for_status();return r.json()['data']['accessToken']
cs=login('dev_customer_service','ADMIN');shop=login('dev_repair_shop','H5');s.headers['Authorization']='Bearer '+cs
def get(path):
 r=s.get(b+path);r.raise_for_status();return r.json()['data']
def post(path,body,token=cs):
 r=s.post(b+path,json=body,headers={'Authorization':'Bearer '+token,'Idempotency-Key':str(uuid.uuid4())});r.raise_for_status();return r.json()['data']
region=next(x['id'] for x in get('/pricing/regions?size=100')['records'] if x['code']=='DEV-DISTRICT')
a=post('/work-orders/drafts',dict(insuranceCompany='合成视觉保司',claimNo='UI-'+str(uuid.uuid4()),ownerName='合成车主',ownerPhone='13800001234',vehicleBrand='合成品牌',vehicleModel='交互复查',vehicleVin='UI-'+str(uuid.uuid4()),accidentAt=datetime.now(timezone.utc).isoformat(),accidentRegionId=region,accidentDescription='仅本地合成视觉验证'))
id=a['id'];post('/work-orders/'+id+'/submit',{'confirmPossibleDuplicate':True,'duplicateReason':'合成独立验收案件'});shopid=next(x['id'] for x in get('/work-orders/'+id+'/eligible-shops?size=100')['records'] if x['code']=='DEV-SHOP');a=post('/work-orders/'+id+'/dispatch',{'shopId':shopid,'confirmPossibleDuplicate':True,'duplicateReason':'合成独立验收案件'});assignment=a['currentAssignment']['assignmentVersion'];post('/work-orders/'+id+'/accept',{'assignmentVersion':assignment},shop);post('/work-orders/'+id+'/arrive',{'assignmentVersion':assignment},shop)
result=json.loads(Path('../local-validation/phase8-visual-results.json').read_text(encoding='utf-8-sig'));completed=next(x['caseId'] for x in result if x['check']=='funds_and_private_import_restart_persistence');case=get('/work-orders/'+completed);f=get('/funds/cases/'+completed);post('/funds/cases/'+completed+'/targets',{'expectedVersion':f['version'],'direction':'RECEIVE','amount':'121.00','reason':'合成界面收款与deadline复查'})
text='transactionNo,direction,claimNo,businessNo,amount,occurredAt,note\nUI-'+str(uuid.uuid4())+',RECEIVE,'+case['claimNo']+',NO-MATCH,1.00,2026-10-10T10:00:00+08:00,SYNTHETIC\n';Path('../local-validation/ui-conflict.csv').write_text(text,encoding='utf-8');r=s.post(b+'/funds/imports',files={'file':('ui-conflict.csv',text.encode(),'text/csv')});r.raise_for_status()
Path('../local-validation/ui-review-fixtures.json').write_text(json.dumps({'activeId':id,'activeBusinessNo':get('/work-orders/'+id)['businessNo'],'completedId':completed,'completedBusinessNo':case['businessNo'],'batchId':r.json()['data']['id']},indent=2),encoding='utf-8');print('Synthetic review fixtures ready')
