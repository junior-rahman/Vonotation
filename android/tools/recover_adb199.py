#!/usr/bin/env python3
"""Recover the existing authorized native namespace's local ADB transport.

This does not restart the emulator, install packages, or launch native tests.
The earlier keeper timeout is a transport observation, not guest OS proof.
"""
from pathlib import Path
import subprocess,os,json,time,datetime,re

ROOT=Path(os.environ.get('VOICEFLOW_TOOLCHAIN_ROOT', str(Path(__file__).resolve().parents[2] / 'toolchain'))).resolve()
EVIDENCE=ROOT/'evidence'
ADB=ROOT/'android-sdk/platform-tools/adb'
env=os.environ.copy()
env.pop('ADB_SERVER_SOCKET',None)
server_log=(EVIDENCE/'adb199-explicit-local-server.log').open('ab')
server=subprocess.Popen([str(ADB),'-L','tcp:127.0.0.1:5037','nodaemon','server'],
                        env=env,stdout=server_log,stderr=subprocess.STDOUT,start_new_session=True)
# Clients use the already-started daemon and do not silently fork a second one.
env['ADB_SERVER_SOCKET']='tcp:127.0.0.1:5037'
probes=[]

def stamp():return datetime.datetime.now(datetime.timezone.utc).isoformat()

def run(label,args,timeout=30):
    started=stamp()
    try:
        p=subprocess.run([str(ADB),*args],env=env,capture_output=True,timeout=timeout)
        result={'label':label,'argv':args,'code':p.returncode,'started_utc':started,
                'finished_utc':stamp(),'stdout':p.stdout.decode(errors='replace'),'stderr':p.stderr.decode(errors='replace')}
    except subprocess.TimeoutExpired as exc:
        result={'label':label,'argv':args,'code':None,'started_utc':started,'finished_utc':stamp(),
                'error':'Timed out','stdout':(exc.stdout or b'').decode(errors='replace'),'stderr':(exc.stderr or b'').decode(errors='replace')}
    probes.append(result)
    print(json.dumps(result),flush=True)
    return result

deadline=time.monotonic()+120
while time.monotonic()<deadline:
    result=run('devices',['devices','-l'],15)
    if re.search(r'^emulator-5554\s+device(?:\s|$)',result.get('stdout',''),re.MULTILINE):
        break
    time.sleep(2)

for label,command in [
    ('guest-uptime',['shell','uptime']),
    ('guest-boot-completed',['shell','getprop','sys.boot_completed']),
    ('guest-zygote',['shell','getprop','init.svc.zygote']),
    ('guest-zygote-secondary',['shell','getprop','init.svc.zygote_secondary']),
    ('guest-boot-animation',['shell','getprop','init.svc.bootanim']),
    ('guest-api',['shell','getprop','ro.build.version.sdk']),
    ('guest-abi',['shell','getprop','ro.product.cpu.abi']),
    ('guest-process-list',['shell','ps','-A']),
    ('guest-package-list',['shell','pm','list','packages']),
]:
    run(label,['-s','emulator-5554',*command],60)

log=run('guest-baseline-logcat',['-s','emulator-5554','logcat','-b','all','-d','-v','threadtime'],120)
(EVIDENCE/'boot-system-199-recovered-adb.log').write_text(log.get('stdout','')+log.get('stderr',''))
for result in probes:
    if result['label']=='guest-baseline-logcat':
        result['stdout_path']=str(EVIDENCE/'boot-system-199-recovered-adb.log')
        result.pop('stdout',None)
ready=any(p['label']=='guest-boot-completed' and p.get('code')==0 and p.get('stdout','').strip()=='1' for p in probes)
report={'time_utc':stamp(),'state':'guest-ready' if ready else 'transport-or-guest-not-ready',
        'earlier_keeper_timeout':'Remote-style ADB socket prevented automatic local daemon startup; original timeout alone does not prove OS boot failure',
        'emulator_restarted':False,'apk_installed':False,'native_tests_started':False,
        'server_pid':server.pid,'server_exit':server.poll(),'probes':probes}
(ROOT/'native199-recovered-status.json').write_text(json.dumps(report,indent=2))
print(json.dumps({k:v for k,v in report.items() if k!='probes'}),flush=True)
