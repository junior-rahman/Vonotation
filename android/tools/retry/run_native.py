#!/usr/bin/env python3
"""Disposable AOSP checks; starts emulator and ADB in one execution namespace."""
from pathlib import Path
import os,subprocess,time,json,hashlib
BASE=Path('/workspace/scratch/ed2af9abe854')
PROJECT=BASE/'recovered/source/VoiceFlow'
SDK=BASE/'toolchain/android-sdk'
OUT=PROJECT/'evidence/1.9.13-native';OUT.mkdir(parents=True,exist_ok=True)
env=os.environ.copy();env.update(ANDROID_SDK_ROOT=str(SDK),ANDROID_AVD_HOME=str(BASE/'toolchain/avd'),ADB_SERVER_SOCKET='tcp:127.0.0.1:5037')
ADB=SDK/'platform-tools/adb';SERIAL='emulator-5580'
results=[]
def status(state,**extra):
    (OUT/'status.json').write_text(json.dumps(dict(state=state,results=results,**extra),indent=2));print(state,extra,flush=True)
def adb(*args,timeout=120):
    return subprocess.run([str(ADB),'-s',SERIAL,*args],env=env,capture_output=True,timeout=timeout)
def require(p,label):
    if p.returncode:raise RuntimeError(label+': '+p.stdout.decode(errors='replace')+p.stderr.decode(errors='replace'))
    return p
serverlog=(OUT/'adb-server.log').open('wb');daemonenv=env.copy();daemonenv.pop('ADB_SERVER_SOCKET')
server=subprocess.Popen([str(ADB),'-L','tcp:5037','nodaemon','server'],env=daemonenv,stdout=serverlog,stderr=subprocess.STDOUT)
bootlog=(OUT/'emulator-boot.log').open('wb')
emulator=subprocess.Popen([str(SDK/'emulator/emulator'),'-avd','VoiceFlowRetry','-port','5580','-no-window','-no-audio','-no-boot-anim','-no-snapshot','-gpu','swiftshader_indirect','-accel','off'],env=env,stdout=bootlog,stderr=subprocess.STDOUT)
try:
    start=time.monotonic();boot=False
    while time.monotonic()-start<900 and emulator.poll() is None:
        try:
            p=adb('shell','getprop','sys.boot_completed',timeout=15)
            if p.returncode==0 and p.stdout.strip()==b'1':boot=True;break
        except subprocess.TimeoutExpired:pass
        status('booting',elapsed=round(time.monotonic()-start),probe=(p.stdout+p.stderr).decode(errors='replace') if 'p' in locals() else 'timeout');time.sleep(10)
    if not boot:raise RuntimeError('Emulator boot not confirmed')
    status('booted',elapsed=round(time.monotonic()-start))
    for args in [('shell','cmd','connectivity','airplane-mode','enable'),('shell','svc','wifi','disable'),('shell','input','keyevent','224'),('shell','wm','dismiss-keyguard'),('shell','settings','put','system','screen_off_timeout','1800000')]:require(adb(*args),'Setup')
    data=adb('shell','svc','data','disable');(OUT/'cellular-disable.txt').write_bytes(data.stdout+data.stderr)
    import re
    deadline=time.monotonic()+60;network=b''
    while time.monotonic()<deadline:
        network=adb('shell','dumpsys','connectivity').stdout
        m=re.search(rb'Active default network:\s*(\S+)',network)
        if m and m.group(1) in (b'none',b'null'):break
        time.sleep(3)
    else:raise RuntimeError('No-network test state could not be verified')
    (OUT/'network-state.txt').write_bytes(network)
    apks=[BASE/'deliverables/VoiceFlow-1.9.12.apk',PROJECT/'app/build/outputs/apk/preview/VoiceFlow-1.9.13.apk',PROJECT/'build/tests/input-regressions/VoiceFlow-input-regressions-fixture.apk']
    for apk in apks:
        require(adb('install','--no-incremental','-r',str(apk),timeout=180),'Install '+apk.name);status('installed',apk=apk.name,sha256=hashlib.sha256(apk.read_bytes()).hexdigest())
    (OUT/'installed-package.txt').write_bytes(adb('shell','dumpsys','package','app.voiceflow').stdout)
    require(adb('shell','pm','grant','app.voiceflow','android.permission.RECORD_AUDIO'),'Microphone grant')
    components=[('dictation-fidelity','app.voiceflow.fixture/app.voiceflow.ApiRequestTest')]
    for label,component in components:
        status('testing',test=label);adb('logcat','-c')
        p=adb('shell','am','instrument','-w',component,timeout=600)
        text=p.stdout.decode(errors='replace')+p.stderr.decode(errors='replace');(OUT/(label+'.txt')).write_text(text)
        log=adb('logcat','-b','all','-d','-v','threadtime',timeout=120).stdout;(OUT/(label+'-logcat.txt')).write_bytes(log)
        passed=p.returncode==0 and 'PASS:' in text and 'failure=' not in text and 'INSTRUMENTATION_FAILED' not in text
        results.append(dict(test=label,passed=passed,returncode=p.returncode));status('test-complete',test=label,passed=passed)
        if not passed:
            (OUT/'window-state.txt').write_bytes(adb('shell','dumpsys','window').stdout)
            raise RuntimeError('Native failure: '+label)
    status('complete',all_passed=True)
except Exception as exc:status('failed',error=str(exc))
finally:
    try:adb('emu','kill',timeout=15)
    except Exception:emulator.terminate()
    server.terminate();bootlog.close();serverlog.close()
