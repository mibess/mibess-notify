#!/usr/bin/env python3
"""Acceptance check against a running Notify API. Never prints credentials.
HML/local: fake provider only. PRD: no-route event only, no external messages.
"""
import argparse, datetime, http.cookiejar, json, pathlib, ssl, time, urllib.request, urllib.error, uuid

parser=argparse.ArgumentParser()
parser.add_argument('--url',required=True)
parser.add_argument('--env-file',required=True)
parser.add_argument('--mode',choices=['fake','no-send'],default='no-send')
args=parser.parse_args()
if args.mode=='fake' and not any(x in args.url for x in ['127.0.0.1','localhost','notify-hml.mibess.com.br']):
    raise SystemExit('Fake acceptance allowed only on local/HML endpoints')
env=dict(line.split('=',1) for line in pathlib.Path(args.env_file).read_text().splitlines() if '=' in line and not line.startswith('#'))
cookies=http.cookiejar.CookieJar()
context=ssl.create_default_context(cafile='/etc/ssl/cert.pem' if pathlib.Path('/etc/ssl/cert.pem').exists() else None)
client=urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cookies),urllib.request.HTTPSHandler(context=context))
base=args.url.rstrip('/')
def call(path,method='GET',body=None,headers=None):
    h={'Content-Type':'application/json',**(headers or {})}
    for cookie in cookies:
        if cookie.name=='XSRF-TOKEN': h['X-XSRF-TOKEN']=cookie.value
    request=urllib.request.Request(base+path,data=json.dumps(body).encode() if body is not None else None,headers=h,method=method)
    try:
        with client.open(request,timeout=30) as response:
            data=response.read();return json.loads(data) if data else None
    except urllib.error.HTTPError as e:
        raise RuntimeError(f'HTTP {e.code}: {method} {path.split("?")[0]} (response withheld)') from None
def eventually(check,expected,seconds=45):
    end=time.monotonic()+seconds
    while time.monotonic()<end:
        value=check()
        if value==expected:return
        time.sleep(.5)
    raise AssertionError(f'Expected {expected}; last state {value}')
def create(kind,code,name,spec):return call('/api/v1/admin/'+kind,'POST',{'code':code,'name':name,'enabled':True,'spec':spec})
def disable(kind,resource):
    spec=dict(resource['spec']);spec.pop('credentialsConfigured',None)
    call('/api/v1/admin/'+kind+'/'+resource['id'],'PUT',{'code':resource['code'],'name':resource['name'],'enabled':False,'spec':spec})

call('/api/v1/auth/csrf')
call('/api/v1/auth/login','POST',{'email':env['ADMIN_EMAIL'],'password':env['ADMIN_PASSWORD']})
call('/api/v1/auth/csrf')
suffix=uuid.uuid4().hex[:10]
app=create('applications','SMOKE_ARTGIAN_'+suffix,'Artgian · validação '+suffix,{'description':'Cenário de aceite controlado, sem mensagens reais'})
key=call('/api/v1/admin/applications/'+app['id']+'/keys','POST',{})
cleanup=[]
try:
    def publish(idempotency,event):return call('/api/v1/events','POST',event,{'X-API-Key':key['key'],'Idempotency-Key':suffix+'-'+idempotency})
    def event_state(event_id):
        return next((e['status'] for e in call('/api/v1/admin/events?applicationId='+app['id'])['items'] if e['id']==event_id),None)
    if args.mode=='no-send':
        event=publish('no-send',{'type':'CONTROLLED_VALIDATION','data':{'purpose':'deployment health'}})
        eventually(lambda:event_state(event['eventId']),'NO_MATCH')
        print('PASS: authenticated event → PostgreSQL → outbox → RabbitMQ → routing NO_MATCH; no recipient or provider invoked.')
    else:
        now=datetime.datetime.now(datetime.timezone.utc).isoformat().replace('+00:00','Z')
        phone='+5516'+str(int(suffix,16)).zfill(11)[-9:]
        contact=create('contacts','ANGELICA_'+suffix,'Angélica · teste',{'phone':phone,'type':'INTERNAL','whatsappOptIn':True,'whatsappOptInAt':now,'whatsappOptInSource':'Aceite automatizado com provider fake'})
        cleanup.append(('contacts',contact))
        group=create('groups','ARTGIAN_OPERATIONS_'+suffix,'Operações · teste',{'members':[contact['id']]});cleanup.append(('groups',group))
        channel=create('channels','MIBESS_TEST_'+suffix,'Mibess Notify · fake',{'provider':'FAKE'});cleanup.append(('channels',channel))
        template=create('templates','internal_order_'+suffix,'Nova venda · teste',{'channelConnectionId':channel['id'],'providerTemplateName':'internal_new_order','language':'pt_BR','category':'UTILITY','status':'APPROVED','body':'Pedido {{orderNumber}} de {{customerName}}','variables':['orderNumber','customerName']});cleanup.append(('templates',template))
        payload={'type':'ORDER_PAID','correlationId':'order-1842-'+suffix,'data':{'orderNumber':'1842','customerName':'Mariana','amount':189.90}}
        unmatched=publish('before-rule',payload)
        eventually(lambda:event_state(unmatched['eventId']),'NO_MATCH')
        detail=call('/api/v1/admin/events/'+unmatched['eventId'])
        assert detail['event']['error_code']=='NO_RULE_FOR_EVENT_TYPE'
        assert detail['event']['routing_diagnostics']['reason']=='NO_RULE_FOR_EVENT_TYPE'
        catalog=call('/api/v1/admin/applications/'+app['id']+'/event-catalog')
        assert any(e['eventType']=='ORDER_PAID' and 'orderNumber' in e['dataFields'] for e in catalog)
        rule=create('rules','ORDER_PAID_'+suffix,'Artgian ORDER_PAID · teste',{'applicationId':app['id'],'eventType':'ORDER_PAID','eventTypes':['ORDER_PAID','CRM_*'],'targetType':'CONTACT_GROUP','targetId':group['id'],'channelConnectionId':channel['id'],'templateId':template['id'],'priority':'NORMAL','eventPriorities':{'CRM_SYNC_FAILED':'HIGH'},'conditions':[]});cleanup.append(('rules',rule))
        before=call('/api/v1/admin/notifications?applicationId='+app['id'])['total']
        simulation=call('/api/v1/admin/rules/'+rule['id']+'/test','POST',{})
        assert simulation['matched'] and not simulation['problems'] and len(simulation['recipients'])==1
        assert simulation['renderedMessage']=='Pedido 1842 de Mariana'
        assert call('/api/v1/admin/notifications?applicationId='+app['id'])['total']==before
        rerouted=call('/api/v1/admin/events/'+unmatched['eventId']+'/reprocess','POST',{})
        assert rerouted['event']['status']=='PROCESSED' and len(rerouted['notifications'])==1
        assert any(t['status']=='REPROCESS_REQUESTED' for t in rerouted['timeline'])
        assert any(a['action']=='EVENT_REPROCESSED' and a['resource_id']==unmatched['eventId'] for a in call('/api/v1/admin/audit')['items'])
        try:
            call('/api/v1/admin/events/'+unmatched['eventId']+'/reprocess','POST',{})
            raise AssertionError('Processed event must reject reprocessing')
        except RuntimeError as error:
            assert 'HTTP 409:' in str(error)
        assert len(call('/api/v1/admin/events/'+unmatched['eventId'])['notifications'])==1
        checks=call('/api/v1/admin/applications/'+app['id']+'/health')['checks']
        assert any(c['code']=='CHANNEL_CONNECTION' and c['status']=='OK' for c in checks)
        accepted=publish('paid',payload);duplicate=publish('paid',payload)
        assert accepted['eventId']==duplicate['eventId'] and duplicate['duplicate']
        def notification(event_id):return next((n for n in call('/api/v1/admin/notifications?applicationId='+app['id'])['items'] if n['event_id']==event_id),{})
        eventually(lambda:notification(accepted['eventId']).get('status'),'SENT')
        n=notification(accepted['eventId']);call('/api/v1/admin/notifications/'+n['id']+'/simulate-delivery','POST',{})
        eventually(lambda:notification(accepted['eventId']).get('status'),'DELIVERED')
        detail=call('/api/v1/admin/notifications/'+n['id']);assert len(detail['attempts'])==1 and len(detail['timeline'])>=7
        recovered=notification(unmatched['eventId'])
        eventually(lambda:notification(unmatched['eventId']).get('status'),'SENT')
        call('/api/v1/admin/notifications/'+recovered['id']+'/simulate-delivery','POST',{})
        eventually(lambda:notification(unmatched['eventId']).get('status'),'DELIVERED')
        wildcard=publish('wildcard',{'type':'CRM_SYNC_FAILED','data':payload['data']})
        eventually(lambda:notification(wildcard['eventId']).get('status'),'SENT')
        assert notification(wildcard['eventId'])['priority']=='HIGH'
        tracking=create('rules','TRACKING_'+suffix,'Artgian entrega · teste',{'applicationId':app['id'],'eventType':'TRACKING_STATUS_CHANGED','targetType':'EVENT_RECIPIENT','channelConnectionId':channel['id'],'templateId':template['id'],'priority':'NORMAL','conditions':[{'field':'data.status','operator':'EQUALS','value':'OUT_FOR_DELIVERY'}]});cleanup.append(('rules',tracking))
        recipient={'name':'Mariana · teste','phone':phone,'whatsappOptIn':True,'whatsappOptInAt':now,'whatsappOptInSource':'Teste fake'}
        payload={'type':'TRACKING_STATUS_CHANGED','recipient':recipient,'data':{'status':'OUT_FOR_DELIVERY','orderNumber':'1842','customerName':'Mariana'}}
        tracked=publish('tracking',payload);eventually(lambda:notification(tracked['eventId']).get('status'),'SENT')
        call('/api/v1/admin/suppressions','POST',{'address':phone,'reason':'Opt-out do teste de aceite'})
        blocked=publish('suppressed',payload);eventually(lambda:notification(blocked['eventId']).get('status'),'CANCELLED')
        print('PASS: NO_MATCH reason → catalog → simulation without delivery → audited reprocess → one fake DELIVERED; multiple event types/prefix and priority; integration health.')
        print('PASS: ORDER_PAID → group → fake SENT → async DELIVERED; idempotency; timeline; OUT_FOR_DELIVERY → EVENT_RECIPIENT; suppression.')
        print('Delivered notification ID:',n['id'])
finally:
    cleanup_failures=[]
    try:call('/api/v1/admin/applications/'+app['id']+'/keys/'+key['id'],'DELETE')
    except Exception:cleanup_failures.append('test API key')
    for kind,resource in reversed(cleanup):
        try:disable(kind,resource)
        except Exception:cleanup_failures.append(kind+':'+resource['id'])
    try:disable('applications',app)
    except Exception:cleanup_failures.append('applications:'+app['id'])
    try:call('/api/v1/auth/logout','POST',{})
    except Exception:cleanup_failures.append('test session logout')
    if cleanup_failures:raise RuntimeError('Cleanup incomplete; disable these test resources: '+', '.join(cleanup_failures))
    print('Test API key revoked and test configuration disabled; event/attempt/audit history preserved.')
