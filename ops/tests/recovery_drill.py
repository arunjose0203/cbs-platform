"""Opt-in PostgreSQL drill. Creates and drops ONLY uniquely named loopback test databases.
Set CBS_DRILL_PG_BIN, CBS_DRILL_PASSWORD_FILE, optionally CBS_DRILL_PORT (55434).
Uses the existing local test administrator cbs_control; never contacts Neon.
"""
import copy
import json
import os
from pathlib import Path
import sys
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
    for d in targets['databases']:d['database']='cbs_restore_'+d['kind']+'_'+suffix
    admin=dict(config['databases'][0],database='cbs_control');created=[]
    try:
        for d in config['databases']+targets['databases']:
            r.query(config,admin,'CREATE DATABASE '+r.qi(d['database']));created.append(d['database'])
        control,bank,identity=config['databases']
        r.query(config,bank,f"CREATE SCHEMA bank; CREATE SCHEMA cbs_identity; CREATE TABLE cbs_identity.owner(singleton boolean PRIMARY KEY,tenant_id uuid NOT NULL); INSERT INTO cbs_identity.owner VALUES(true,'{tenant}');")
        for d,folder in ((control,'migration'),(bank,'bank-migration')):
            files=sorted((repo/'src/main/resources/db'/folder).glob('V*__*.sql'),key=lambda p:int(p.name.split('__')[0][1:]))
            for path in files:r.query(config,d,path.read_text(encoding='utf-8'))
        r.query(config,control,f"INSERT INTO platform.tenant(id,bank_code,display_name,default_language,status) VALUES('{tenant}','DRILL','Fictional recovery bank','en','ACTIVE'); INSERT INTO platform.database_binding(tenant_id,database_reference,schema_version) VALUES('{tenant}','drill','21');")
        for request in (jid,str(uuid.uuid4())):
            r.query(config,control,f"INSERT INTO platform.approval_request(id,issuer,maker_subject,idempotency_key,action,target_id,bank_id,target_label,expected_version,reason,payload,before_values,proposed_values,status,reviewer_subject,decided_at) VALUES('{request}','https://identity.invalid','maker','{request}','JOURNAL_POST','{request}','{tenant}','Fictional journal',0,'Recovery drill','{{}}','{{}}','{{}}','APPROVED','checker',now()); INSERT INTO platform.journal_delivery(request_id,tenant_id,reference,payload,maker_subject,reviewer_issuer,reviewer_subject,correlation_id) VALUES('{request}','{tenant}','{request}','{{}}','maker','https://identity.invalid','checker','{request}');")
        ledgers=[str(uuid.uuid4()),str(uuid.uuid4())]
        for index,lid in enumerate(ledgers):
            r.query(config,bank,f"INSERT INTO bank.ledger_account(id,tenant_id,code,name,chart_account_id,legal_entity_id,currency,active,version,last_request_id) VALUES('{lid}','{tenant}','DRILL_{index}','Fictional ledger','{uuid.uuid4()}','{entity}','KES',true,0,'{uuid.uuid4()}');")
        r.query(config,bank,f"BEGIN; INSERT INTO bank.journal(id,tenant_id,reference,business_date,legal_entity_id,currency,payload,maker_subject,reviewer_issuer,reviewer_subject,correlation_id) VALUES('{jid}','{tenant}','DRILL',current_date,'{entity}','KES','{{}}','maker','https://identity.invalid','checker','{jid}'); INSERT INTO bank.journal_line VALUES('{jid}',1,'{ledgers[0]}','DEBIT',100),('{jid}',2,'{ledgers[1]}','CREDIT',100); COMMIT;")
        r.query(config,identity,"CREATE TABLE fictional_identity(id bigserial PRIMARY KEY,subject text,mfa_required boolean); INSERT INTO fictional_identity(subject,mfa_required) VALUES('maker',true),('checker',true);")
        backup=r.backup(config,root/'backup')
        report=r.restore(config,root/'backup',targets,root/'restore-report.json')
        assert report['financial_checks']['bank']['pending_postings']==2
        assert report['financial_checks']['bank']['pending_already_posted']==1
        try:r.restore(config,root/'backup',targets,root/'second-report.json')
        except r.OpsError:pass
        else:raise AssertionError('Non-empty restore accepted')
        print(json.dumps({'status':'PASS','full_schema_restore':True,'pending_postings':2,'lost_acknowledgement_preserved':True,'report':str(root/'restore-report.json'),'restore_seconds':report['restore_seconds']}))
    finally:
        for name in reversed(created):
            if not (name.startswith('cbs_drill_') or name.startswith('cbs_restore_')) or not name.endswith('_'+suffix):raise RuntimeError('Unsafe cleanup target')
            r.query(config,admin,'DROP DATABASE '+r.qi(name)+' WITH (FORCE)')
        (root/'pgpass').unlink(missing_ok=True)

if __name__=='__main__':main()
