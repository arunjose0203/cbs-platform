"""Read-only monitoring checks; exit 2 on an alert, emit safe JSON and optional Prometheus text."""
import argparse
from datetime import datetime, timezone
import hmac
import json
import math
import os
from pathlib import Path
import urllib.request
from urllib.parse import urlparse
import recovery

def age(when):
    value = (datetime.now(timezone.utc) - datetime.fromisoformat(when)).total_seconds()
    if value < -60:
        raise recovery.OpsError('Evidence timestamp is in the future')
    return max(0, value)

def check(config):
    recovery.validate(config)
    limits = config['monitor']
    if any(not isinstance(limits[k], (int,float)) or isinstance(limits[k],bool) or not math.isfinite(limits[k]) or limits[k] <= 0 for k in ('max_backup_age_seconds','max_restore_age_seconds','max_pending_age_seconds')):
        raise recovery.OpsError('Set explicit positive monitoring thresholds')
    alerts, metrics = [], {}
    url = urlparse(limits['readiness_url'])
    if url.username or url.password or url.query or url.fragment or (url.scheme != 'https' and not (config.get('local_test') and url.scheme == 'http' and url.hostname in ('127.0.0.1','localhost'))):
        raise recovery.OpsError('Readiness URL requires verified HTTPS without credentials')
    try:
        with urllib.request.urlopen(limits['readiness_url'], timeout=10) as response:
            if json.load(response).get('status') != 'UP':
                raise ValueError()
        metrics['cbs_readiness_up'] = 1
    except Exception:
        metrics['cbs_readiness_up'] = 0; alerts.append('APPLICATION_NOT_READY')
    backup_directory = limits.get('latest_backup_directory')
    try:
        job = json.loads(Path(limits['last_backup_job']).read_text())
        if job['status'] != 'BACKUP_COMPLETE':
            alerts.append('BACKUP_JOB_FAILED')
        else:
            backup_directory = job['directory']
    except Exception:
        alerts.append('BACKUP_JOB_STATUS_MISSING')
    try:
        m, _ = recovery.authenticated_manifest(config, backup_directory, verify_files=False)
        metrics['cbs_backup_age_seconds'] = age(m['completed_at'])
        if metrics['cbs_backup_age_seconds'] > limits['max_backup_age_seconds']:
            alerts.append('BACKUP_STALE')
    except Exception:
        alerts.append('BACKUP_MISSING_OR_INVALID')
    try:
        envelope = json.loads(Path(limits['latest_restore_report']).read_text())
        r = envelope['report']
        if not hmac.compare_digest(envelope['hmac'],hmac.digest(recovery.keys(config)[1],recovery.canonical(r),'sha256').hex()) or r['status'] != 'RESTORE_VERIFIED':
            raise ValueError()
        metrics['cbs_restore_age_seconds'] = age(r['completed_at'])
        if metrics['cbs_restore_age_seconds'] > limits['max_restore_age_seconds']:
            alerts.append('RESTORE_DRILL_STALE')
    except Exception:
        alerts.append('RESTORE_EVIDENCE_MISSING_OR_INVALID')
    for d in config['databases']:
        try:
            recovery.query(config,d,'SELECT 1')
            metrics['cbs_database_up{database="'+d['name']+'"}'] = 1
            if d['kind'] == 'control':
                queues = json.loads(recovery.query(config,d,"SELECT coalesce(json_agg(table_name),'[]') FROM information_schema.columns WHERE table_schema='platform' AND column_name='completed' AND table_name IN (SELECT table_name FROM information_schema.columns WHERE table_schema='platform' AND column_name='created_at')"))
                for table in queues:
                    oldest = float(recovery.query(config,d,'SELECT coalesce(max(extract(epoch FROM (clock_timestamp()-created_at))),0) FROM platform.'+recovery.qi(table)+' WHERE NOT completed'))
                    metrics['cbs_pending_age_seconds{queue="'+table+'"}'] = max(0,oldest)
                    if oldest > limits['max_pending_age_seconds']:
                        alerts.append('POSTING_OVERDUE:'+table)
                # Compliance cases past their investigator due date (SLA). A count only: no customer data leaves the database.
                metrics['cbs_compliance_overdue_cases'] = int(recovery.query(config,d,"SELECT count(*) FROM platform.compliance_case WHERE status='OPEN' AND due_date<current_date"))
                # Customer messages that exhausted their retries in the last day, and the oldest message still queued. Counts only.
                metrics['cbs_notifications_failed_24h'] = int(recovery.query(config,d,"SELECT count(*) FROM platform.notification WHERE status='FAILED' AND last_attempt_at>clock_timestamp()-interval '24 hours'"))
                # Banks whose scheduled daily cycle is currently blocked (latest scheduler outcome). A count only.
                metrics['cbs_batch_blocked_banks'] = int(recovery.query(config,d,"SELECT count(*) FROM (SELECT DISTINCT ON (tenant_id) outcome FROM platform.batch_event ORDER BY tenant_id,recorded_at DESC) x WHERE outcome='BLOCKED'"))
                metrics['cbs_notifications_pending_age_seconds'] = max(0,float(recovery.query(config,d,"SELECT coalesce(max(extract(epoch FROM (clock_timestamp()-created_at))),0) FROM platform.notification WHERE status='PENDING'")))
                metrics['cbs_reconciliation_difference_detected'] = int(recovery.query(config,d,"SELECT count(*) FROM platform.migration_reconciliation WHERE difference <> 0"))
                metrics['cbs_delivery_failures_24h'] = int(recovery.query(config,d,"SELECT count(*) FROM platform.journal_delivery WHERE completed AND NOT success AND delivered_at > clock_timestamp()-interval '24 hours'"))
        except Exception:
            metrics['cbs_database_up{database="'+d['name']+'"}'] = 0
            alerts.append('DATABASE_CHECK_FAILED:'+d['name'])
    return {'status':'ALERT' if alerts else 'OK','alerts':alerts,'metrics':metrics,'checked_at':datetime.now(timezone.utc).isoformat()}

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--config',required=True);parser.add_argument('--metrics')
    args=parser.parse_args()
    try:
        report=check(json.loads(Path(args.config).read_text()))
        if args.metrics:
            path=Path(args.metrics);temp=path.with_suffix('.tmp')
            with open(temp,'w',encoding='utf-8') as output:
                output.write('cbs_monitor_success 1\n')
                output.write('cbs_monitor_alerts '+str(len(report['alerts']))+'\n')
                output.write('\n'.join(k+' '+str(v) for k,v in report['metrics'].items())+'\n')
                output.write('cbs_monitor_last_run_seconds '+str(datetime.now(timezone.utc).timestamp())+'\n')
            os.replace(temp,path)
        print(json.dumps(report));return 2 if report['alerts'] else 0
    except Exception:
        print(json.dumps({'status':'FAILED','alerts':['MONITOR_CHECK_FAILED']}));return 2

if __name__=='__main__':
    raise SystemExit(main())
