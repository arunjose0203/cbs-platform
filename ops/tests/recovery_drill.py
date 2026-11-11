"""Opt-in PostgreSQL drill. Creates and drops ONLY uniquely named loopback test databases.
Set CBS_DRILL_PG_BIN, CBS_DRILL_PASSWORD_FILE, optionally CBS_DRILL_PORT (55434).
Set CBS_DRILL_JAR and CBS_DRILL_JAVA to exercise the packaged offline migration launcher.
Uses the existing local test administrator cbs_control; never contacts Neon.
"""
import copy
import json
import os
from pathlib import Path
import sys
import subprocess
import uuid
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
import recovery as r

def main():
    repo=Path(__file__).resolve().parents[2]
    suffix=uuid.uuid4().hex[:12];root=repo/'.local'/('recovery-drill-'+suffix);root.mkdir(parents=True)
    password=Path(os.environ['CBS_DRILL_PASSWORD_FILE']).read_text().strip()
    port=int(os.environ.get('CBS_DRILL_PORT','55434'))
    r.private_write(root/'pgpass',f'127.0.0.1:{port}:*:cbs_control:{password.replace(chr(92),chr(92)*2).replace(":",chr(92)+":")}\n'.encode())
    r.private_write(root/'key',os.urandom(32));r.private_write(root/'identity.json',b'{"realm":"fictional-drill","mfaRequired":true}')
    tenant=str(uuid.uuid4());jid=str(uuid.uuid4());entity=str(uuid.uuid4())
    config={'local_test':True,'pg_bin':os.environ['CBS_DRILL_PG_BIN'],'pgpass_file':str(root/'pgpass'),'key_file':str(root/'key'),'scratch_dir':str(root/'scratch'),'files':[{'name':'identity','path':str(root/'identity.json')}],'databases':[]}
    for kind in ('control','bank','identity'):
        d={'name':kind,'kind':kind,'host':'127.0.0.1','port':port,'user':'cbs_control','database':'cbs_drill_'+kind+'_'+suffix}
        if kind=='bank':d['tenant_id']=tenant
        config['databases'].append(d)
    targets=copy.deepcopy(config)
    targets['recovered_files_directory']=str(root/'recovered-config')
    for d in targets['databases']:d['database']='cbs_restore_'+d['kind']+'_'+suffix
    admin=dict(config['databases'][0],database='cbs_control');created=[];roles=[]
    try:
        for d in config['databases']+targets['databases']:
            r.query(config,admin,'CREATE DATABASE '+r.qi(d['database']));created.append(d['database'])
        control,bank,identity=config['databases']
        if os.environ.get('CBS_DRILL_JAR'):
            def migration(d):
                return {'url':f"jdbc:postgresql://127.0.0.1:{port}/{d['database']}",'username':'cbs_control','passwordFile':str(Path(os.environ['CBS_DRILL_PASSWORD_FILE']).resolve()),'tenantId':d.get('tenant_id')}
            r.private_write(root/'migration.json',r.canonical({'control':migration(control),'banks':{'drill':migration(bank)}}))
            with (root/'migration.log').open('wb') as log:
                for _ in range(2):
                    result=subprocess.run([os.environ.get('CBS_DRILL_JAVA','java'),'-Dloader.main=com.cbs.operations.MigrationMain','-cp',os.environ['CBS_DRILL_JAR'],'org.springframework.boot.loader.launch.PropertiesLauncher',str(root/'migration.json'),'--local-test'],stdout=log,stderr=log,timeout=180)
                    if result.returncode:raise RuntimeError('Packaged migration failed; inspect private drill log')
        else:
            r.query(config,bank,f"CREATE SCHEMA bank; CREATE SCHEMA cbs_identity; CREATE TABLE cbs_identity.owner(singleton boolean PRIMARY KEY,tenant_id uuid NOT NULL); INSERT INTO cbs_identity.owner VALUES(true,'{tenant}');")
            for d,folder in ((control,'migration'),(bank,'bank-migration')):
                files=sorted((repo/'src/main/resources/db'/folder).glob('V*__*.sql'),key=lambda p:int(p.name.split('__')[0][1:]))
                for path in files:r.query(config,d,path.read_text(encoding='utf-8'))
        r.query(config,control,f"INSERT INTO platform.tenant(id,bank_code,display_name,default_language,status) VALUES('{tenant}','DRILL','Fictional recovery bank','en','ACTIVE'); INSERT INTO platform.database_binding(tenant_id,database_reference,schema_version) VALUES('{tenant}','drill','25');")
        for request in (jid,str(uuid.uuid4())):
            r.query(config,control,f"INSERT INTO platform.approval_request(id,issuer,maker_subject,idempotency_key,action,target_id,bank_id,target_label,expected_version,reason,payload,before_values,proposed_values,status,reviewer_subject,decided_at) VALUES('{request}','https://identity.invalid','maker','{request}','JOURNAL_POST','{request}','{tenant}','Fictional journal',0,'Recovery drill','{{}}','{{}}','{{}}','APPROVED','checker',now()); INSERT INTO platform.journal_delivery(request_id,tenant_id,reference,payload,maker_subject,reviewer_issuer,reviewer_subject,correlation_id) VALUES('{request}','{tenant}','{request}','{{}}','maker','https://identity.invalid','checker','{request}');")
        ledgers=[str(uuid.uuid4()),str(uuid.uuid4())]
        for index,lid in enumerate(ledgers):
            r.query(config,bank,f"INSERT INTO bank.ledger_account(id,tenant_id,code,name,chart_account_id,legal_entity_id,currency,active,version,last_request_id) VALUES('{lid}','{tenant}','DRILL_{index}','Fictional ledger','{uuid.uuid4()}','{entity}','KES',true,0,'{uuid.uuid4()}');")
        customer,account=str(uuid.uuid4()),str(uuid.uuid4())
        r.query(config,bank,f"INSERT INTO bank.customer(id,tenant_id,customer_number,document_key,version,payload) VALUES('{customer}','{tenant}','FICTIONAL','FICTIONAL',0,'{{}}'); INSERT INTO bank.account(id,tenant_id,account_number,customer_id,name,version,payload) VALUES('{account}','{tenant}','DRILL','{customer}','Fictional account',0,'{{\"state\":\"ACTIVE\"}}'); INSERT INTO bank.account_ledger VALUES('{account}','{ledgers[1]}');")
        r.query(config,bank,f"BEGIN; INSERT INTO bank.journal(id,tenant_id,reference,business_date,legal_entity_id,currency,payload,maker_subject,reviewer_issuer,reviewer_subject,correlation_id) VALUES('{jid}','{tenant}','DRILL',current_date,'{entity}','KES','{{}}','maker','https://identity.invalid','checker','{jid}'); INSERT INTO bank.journal_line VALUES('{jid}',1,'{ledgers[0]}','DEBIT',100),('{jid}',2,'{ledgers[1]}','CREDIT',100); INSERT INTO bank.funding(request_id,account_id,amount,payload) VALUES('{jid}','{account}',100,'{{}}'); COMMIT;")
        r.query(config,identity,"CREATE TABLE fictional_identity(id bigserial PRIMARY KEY,subject text,mfa_required boolean); INSERT INTO fictional_identity(subject,mfa_required) VALUES('maker',true),('checker',true);")
        for d in (control,bank):
            role='cbs_drill_runtime_'+d['kind']+'_'+suffix
            r.query(config,admin,'CREATE ROLE '+r.qi(role));roles.append(role)
            applied=subprocess.run([r.tool(config,'psql'),*r.connection(d),'-X','-v','owner=cbs_control','-v','runtime='+role,'-f',str(repo/'ops/runtime-privileges.sql')],env=r.environment(config),capture_output=True)
            if applied.returncode:raise RuntimeError('Runtime privilege script failed')
            schema='platform' if d['kind']=='control' else 'bank'
            assert r.query(config,d,'SET ROLE '+r.qi(role)+'; SELECT count(*) FROM '+schema+'.'+('tenant' if schema=='platform' else 'journal'))=='1'
            for sql in ('CREATE TABLE '+schema+'.unauthorized(id int)', 'TRUNCATE '+schema+'.'+('tenant' if schema=='platform' else 'journal')):
                try:r.query(config,d,'SET ROLE '+r.qi(role)+'; '+sql)
                except r.OpsError:pass
                else:raise AssertionError('Restricted runtime performed DDL')
            if schema=='bank':
                for sql in ('UPDATE cbs_identity.owner SET singleton=false','UPDATE bank.journal SET reference=reference'):
                    try:r.query(config,d,'SET ROLE '+r.qi(role)+'; '+sql)
                    except r.OpsError:pass
                    else:raise AssertionError('Protected records changed')
        assert r.query(config,admin,f"SELECT has_database_privilege('{roles[0]}','{bank['database']}','CONNECT') OR has_database_privilege('{roles[1]}','{control['database']}','CONNECT')")=='f'
        backup=r.backup(config,root/'backup')
        report=r.restore(config,root/'backup',targets,root/'restore-report.json')
        assert report['financial_checks']['bank']['pending_postings']==2
        assert report['financial_checks']['bank']['pending_already_posted']==1
        assert report['financial_checks']['bank']['customer_balances_reconciled']
        assert r.query(targets,targets['databases'][1],'SELECT posted=100 AND held=0 FROM bank.customer_position')=='t'
        assert (root/'recovered-config/0-identity').read_bytes()==(root/'identity.json').read_bytes()
        try:r.restore(config,root/'backup',targets,root/'second-report.json')
        except r.OpsError:pass
        else:raise AssertionError('Non-empty restore accepted')
        print(json.dumps({'status':'PASS','packaged_migrations':bool(os.environ.get('CBS_DRILL_JAR')),'full_schema_restore':True,'runtime_privileges_verified':True,'customer_balance':'100 KES','configuration_export_verified':True,'pending_postings':2,'lost_acknowledgement_preserved':True,'report':str(root/'restore-report.json'),'restore_seconds':report['restore_seconds']}))
    finally:
        for name in reversed(created):
            if not (name.startswith('cbs_drill_') or name.startswith('cbs_restore_')) or not name.endswith('_'+suffix):raise RuntimeError('Unsafe cleanup target')
            r.query(config,admin,'DROP DATABASE '+r.qi(name)+' WITH (FORCE)')
        for role in roles:r.query(config,admin,'DROP ROLE '+r.qi(role))
        (root/'pgpass').unlink(missing_ok=True)

if __name__=='__main__':main()
