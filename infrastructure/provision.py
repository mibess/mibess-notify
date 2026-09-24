#!/usr/bin/env python3
"""Run as root on the inspected VPS. Creates only Notify-owned resources.
Secrets are generated on the host, never emitted, and existing secrets are kept.
"""
import base64, json, os, pathlib, secrets, subprocess, time, urllib.request

BASE = pathlib.Path('/opt/mibess-notify')
os.umask(0o077)
def run(args, input=None, quiet=False):
    result = subprocess.run(args, input=input, text=True, capture_output=True)
    if result.returncode:
        raise RuntimeError('Command failed: ' + ' '.join(args[:3]) + ' (output withheld to protect credentials)')
    return result.stdout.strip()
def aws(*args):
    return json.loads(run(['aws', *args, '--output', 'json']) or '{}')
def env_file(name, values):
    path = BASE / 'secrets' / name
    if path.exists():
        return dict(line.split('=',1) for line in path.read_text().splitlines() if '=' in line)
    path.write_text(''.join(f'{k}={v}\n' for k,v in values.items()))
    path.chmod(0o600)
    return values
def sql(environment, query, database='postgres'):
    return run(['docker','exec','-i',f'postgres-{environment}','psql','-v','ON_ERROR_STOP=1','-U',f'user-{environment}','-d',database,'-At'],query)

for directory in ['secrets','bin','state','releases','backups','infrastructure']:
    (BASE/directory).mkdir(parents=True,exist_ok=True)
for name in ['postgres-prd','postgres-hml']:
    assert run(['docker','inspect','--format','{{.State.Running}}',name]) == 'true'
run(['docker','network','inspect','shared-db-network'])
for name in ['notify-edge','notify-broker']:
    if subprocess.run(['docker','network','inspect',name],capture_output=True).returncode:
        run(['docker','network','create',name])

# Project-local Compose plugin leaves the legacy installation untouched.
compose = BASE/'bin/docker-compose'
if not compose.exists():
    release=json.loads(urllib.request.urlopen('https://api.github.com/repos/docker/compose/releases/latest').read())
    version=release['tag_name']
    url=f'https://github.com/docker/compose/releases/download/{version}/docker-compose-linux-x86_64'
    run(['curl','--fail','--silent','--show-error','--location',url,'--output',str(compose)])
    checksum=run(['curl','--fail','--silent','--show-error','--location',url+'.sha256']).split()[0]
    import hashlib
    assert hashlib.sha256(compose.read_bytes()).hexdigest()==checksum, 'Compose checksum mismatch'
    compose.chmod(0o755)

broker=env_file('broker.env',{'RABBITMQ_DEFAULT_USER':'notify_broker_admin','RABBITMQ_DEFAULT_PASS':secrets.token_urlsafe(36)})
for environment in ['hml','prd']:
    database='mibess_notify_hml' if environment=='hml' else 'mibess_notify'
    username=database
    env=env_file(environment+'.env',{
        'DB_PASSWORD':secrets.token_urlsafe(36),'RABBITMQ_PASSWORD':secrets.token_urlsafe(36),
        'MASTER_ENCRYPTION_KEY':base64.b64encode(secrets.token_bytes(32)).decode(),
        'ADMIN_EMAIL':'admin@mibess.com.br','ADMIN_PASSWORD':secrets.token_urlsafe(28),
        'COOKIE_SECURE':'true','OPENAPI_ENABLED':'false','META_GRAPH_VERSION':'v26.0'
    })
    # Values are generated URL-safe secrets, never interpolated from user input.
    if sql(environment,f"SELECT 1 FROM pg_roles WHERE rolname='{username}';")!='1':
        sql(environment,f"CREATE ROLE {username} LOGIN PASSWORD '{env['DB_PASSWORD']}' NOSUPERUSER NOCREATEDB NOCREATEROLE;")
    if sql(environment,f"SELECT 1 FROM pg_database WHERE datname='{database}';")!='1':
        sql(environment,f'CREATE DATABASE {database} OWNER {username};')
    sql(environment,f'REVOKE ALL ON DATABASE {database} FROM PUBLIC; GRANT CONNECT,TEMPORARY ON DATABASE {database} TO {username};')

run([str(compose),'-f',str(BASE/'infrastructure/docker-compose.shared.yml'),'up','-d','rabbitmq'])
for attempt in range(60):
    if subprocess.run(['docker','exec','mibess-notify-rabbitmq','rabbitmq-diagnostics','-q','ping'],capture_output=True).returncode==0: break
    time.sleep(2)
else: raise RuntimeError('Notify broker did not become healthy')
run(['docker','exec','mibess-notify-rabbitmq','rabbitmqctl','await_startup','--timeout','120'])
users=run(['docker','exec','mibess-notify-rabbitmq','rabbitmqctl','list_users','--silent'])
vhosts=run(['docker','exec','mibess-notify-rabbitmq','rabbitmqctl','list_vhosts','--silent'])
for environment in ['hml','prd']:
    username=f'mibess_notify_{environment}'; vhost=f'/mibess-notify-{environment}'
    env=env_file(environment+'.env',{})
    if username not in users:run(['docker','exec','mibess-notify-rabbitmq','rabbitmqctl','add_user',username,env['RABBITMQ_PASSWORD']])
    if vhost not in vhosts:run(['docker','exec','mibess-notify-rabbitmq','rabbitmqctl','add_vhost',vhost])
    run(['docker','exec','mibess-notify-rabbitmq','rabbitmqctl','set_permissions','-p',vhost,username,'^notify.*','^notify.*','^notify.*'])

for service in ['backend','frontend']:
    name='mibess-notify-'+service
    exists=subprocess.run(['aws','ecr','describe-repositories','--region','us-east-1','--repository-names',name],capture_output=True).returncode==0
    if not exists:aws('ecr','create-repository','--region','us-east-1','--repository-name',name,'--image-tag-mutability','IMMUTABLE','--image-scanning-configuration','scanOnPush=true')

account=aws('sts','get-caller-identity')['Account']
provider=f'arn:aws:iam::{account}:oidc-provider/token.actions.githubusercontent.com'
providers=aws('iam','list-open-id-connect-providers')['OpenIDConnectProviderList']
if not any(p['Arn']==provider for p in providers):aws('iam','create-open-id-connect-provider','--url','https://token.actions.githubusercontent.com','--client-id-list','sts.amazonaws.com')
trust={'Version':'2012-10-17','Statement':[{'Effect':'Allow','Principal':{'Federated':provider},'Action':'sts:AssumeRoleWithWebIdentity','Condition':{'StringEquals':{'token.actions.githubusercontent.com:aud':'sts.amazonaws.com','token.actions.githubusercontent.com:sub':['repo:mibess@11463771/mibess-notify@1384348076:ref:refs/heads/main','repo:mibess@11463771/mibess-notify@1384348076:ref:refs/heads/develop']}}}]}
role='mibess-notify-github-deploy'
if subprocess.run(['aws','iam','get-role','--role-name',role],capture_output=True).returncode:
    aws('iam','create-role','--role-name',role,'--assume-role-policy-document',json.dumps(trust))
else:
    aws('iam','update-assume-role-policy','--role-name',role,'--policy-document',json.dumps(trust))
policy={'Version':'2012-10-17','Statement':[{'Effect':'Allow','Action':['ecr:GetAuthorizationToken'],'Resource':'*'},{'Effect':'Allow','Action':['ecr:BatchCheckLayerAvailability','ecr:CompleteLayerUpload','ecr:UploadLayerPart','ecr:InitiateLayerUpload','ecr:PutImage','ecr:BatchGetImage','ecr:GetDownloadUrlForLayer','ecr:DescribeImages'],'Resource':[f'arn:aws:ecr:us-east-1:{account}:repository/mibess-notify-backend',f'arn:aws:ecr:us-east-1:{account}:repository/mibess-notify-frontend']}]}
aws('iam','put-role-policy','--role-name',role,'--policy-name','NotifyImagesOnly','--policy-document',json.dumps(policy))

zone='Z0905217FGV625NGB2PY'
records=aws('route53','list-resource-record-sets','--hosted-zone-id',zone)['ResourceRecordSets']
changes=[]
for domain in ['notify.mibess.com.br.','notify-hml.mibess.com.br.']:
    existing=[r for r in records if r['Name']==domain and r['Type'] in ['A','AAAA','CNAME']]
    if existing:
        assert len(existing)==1 and existing[0]['Type']=='A' and existing[0].get('ResourceRecords')==[{'Value':'212.85.15.50'}], 'Existing DNS differs; no record overwritten'
    else:changes.append({'Action':'CREATE','ResourceRecordSet':{'Name':domain,'Type':'A','TTL':300,'ResourceRecords':[{'Value':'212.85.15.50'}]}})
if changes:aws('route53','change-resource-record-sets','--hosted-zone-id',zone,'--change-batch',json.dumps({'Comment':'Mibess Notify Hostinger endpoints','Changes':changes}))

run([str(compose),'-f',str(BASE/'infrastructure/docker-compose.shared.yml'),'up','-d','proxy'])
print('Notify databases/users, broker/vhosts, ECR, scoped OIDC role, DNS and HTTPS proxy provisioned. Shared applications untouched. Secrets omitted.')
