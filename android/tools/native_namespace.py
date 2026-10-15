#!/usr/bin/env python3
"""Keep an authorized AOSP emulator/ADB namespace warm and run queued native checks.

Requests are filesystem-only coordination from the existing implementation task.
No installation or test script runs until root writes the saved-artifact marker.
"""
from pathlib import Path
import os, subprocess, time, json, datetime, hashlib, signal

ROOT = Path(os.environ.get('VOICEFLOW_TOOLCHAIN_ROOT', str(Path(__file__).resolve().parents[2] / 'toolchain'))).resolve()
SDK = ROOT / 'android-sdk'
ADB = SDK / 'platform-tools/adb'
QUEUE = ROOT / 'native199-queue'
EVIDENCE = ROOT / 'evidence'
QUEUE.mkdir(exist_ok=True)
EVIDENCE.mkdir(exist_ok=True)
env = os.environ.copy()
env['ADB_SERVER_SOCKET'] = 'tcp:127.0.0.1:5037'
env['ANDROID_SDK_ROOT'] = str(SDK)
env['ANDROID_HOME'] = str(SDK)
env['ANDROID_AVD_HOME'] = str(ROOT / 'avd')
env['PATH'] = str(SDK / 'platform-tools') + ':' + env.get('PATH', '')

def stamp():
    return datetime.datetime.now(datetime.timezone.utc).isoformat()

def adb(*args, timeout=30):
    return subprocess.run([str(ADB), *args], env=env, capture_output=True, timeout=timeout)

def write_json(path, value):
    tmp = path.with_suffix(path.suffix + '.tmp')
    tmp.write_text(json.dumps(value, indent=2))
    tmp.replace(path)

boot_log = (EVIDENCE / 'emulator199-boot.log').open('wb')
# Start the namespace's local daemon before any client uses the explicit remote
# client address. ADB rejects 127.0.0.1 as a listen-spec hostname; tcp:5037 is its
# supported local listener form and does not request all-interface listening.
daemon_env = env.copy()
daemon_env.pop('ADB_SERVER_SOCKET', None)
daemon_log = (EVIDENCE / 'adb199-local-server-startup.log').open('wb')
daemon = subprocess.Popen([str(ADB), '-L', 'tcp:5037', 'nodaemon', 'server'],
                          env=daemon_env, stdout=daemon_log, stderr=subprocess.STDOUT)
command = [str(SDK / 'emulator/emulator'), '-avd', 'VoiceFlow199', '-port', '5554',
           '-no-window', '-no-audio', '-no-boot-anim', '-accel', 'off', '-gpu', 'swiftshader',
           '-cores', '2', '-memory', '2048', '-no-snapshot-load', '-no-snapshot-save']
emulator = subprocess.Popen(command, env=env, stdout=boot_log, stderr=subprocess.STDOUT)
boot_start = time.monotonic()
booted = False
deadline = boot_start + 1500
print(json.dumps({'state': 'booting', 'emulator_pid': emulator.pid, 'time': stamp()}), flush=True)
while emulator.poll() is None and time.monotonic() < deadline:
    try:
        p = adb('-s', 'emulator-5554', 'shell', 'getprop', 'sys.boot_completed', timeout=15)
        if p.returncode == 0 and p.stdout.strip() == b'1':
            booted = True
            break
    except subprocess.TimeoutExpired:
        pass
    write_json(ROOT / 'native199-heartbeat.json', {'state': 'booting', 'elapsed_seconds': round(time.monotonic()-boot_start, 1), 'time': stamp()})
    time.sleep(10)

status = {'state': 'ready' if booted else 'boot-not-confirmed', 'time': stamp(),
          'elapsed_seconds': round(time.monotonic()-boot_start, 1), 'emulator_pid': emulator.pid,
          'emulator_exit': emulator.poll(), 'serial': 'emulator-5554', 'acceleration': 'TCG',
          'google_services': 'AOSP default; Google Play services unavailable',
          'installed_voiceflow': False, 'adb_local_daemon_exit': daemon.poll()}
if booted:
    for name, args in [('boot-system', ['logcat','-b','all','-d','-v','threadtime']),
                       ('boot-package-list', ['shell','pm','list','packages']),
                       ('boot-properties', ['shell','getprop'])]:
        p = adb('-s','emulator-5554',*args,timeout=120)
        (EVIDENCE / (name+'-199.log')).write_bytes(p.stdout+p.stderr)
    adb('-s','emulator-5554','shell','input','keyevent','82')
write_json(ROOT / 'native199-status.json', status)
print(json.dumps(status), flush=True)

# Root may keep this namespace alive while producing pinned artifacts. Commands
# are processed sequentially so native log intervals and instrumentation stay intact.
while emulator.poll() is None:
    write_json(ROOT / 'native199-heartbeat.json', {'state':'ready' if booted else 'boot-not-confirmed','time':stamp()})
    if (ROOT / 'native199-release-after-save.json').exists():
        print('Root final-save release marker observed; stopping emulator.', flush=True)
        adb('-s','emulator-5554','emu','kill')
        break
    for request_path in sorted(QUEUE.glob('*.request.json')):
        response_path = request_path.with_name(request_path.name.replace('.request.json','.response.json'))
        if response_path.exists():
            continue
        started = stamp()
        try:
            request = json.loads(request_path.read_text())
            argv = request['argv']
            if not isinstance(argv,list) or not all(isinstance(v,str) for v in argv):
                raise ValueError('argv must be an array of strings')
            if argv[0] == 'adb':
                argv[0] = str(ADB)
            is_install = argv[0] == str(ADB) and ('install' in argv or 'install-multiple' in argv)
            is_script = argv[0] in ('bash','/bin/bash','python3')
            if argv[0] != str(ADB) and not is_script:
                raise ValueError('Only ADB or task-owned native scripts may run here')
            if is_script and (len(argv)<2 or not Path(argv[1]).resolve().is_relative_to(ROOT)):
                raise ValueError('Native scripts must live in this toolchain directory')
            marker = ROOT / 'native199-install-authorized.json'
            if (is_install or is_script) and not marker.exists():
                raise ValueError('Root has not confirmed saved and pinned APK/source artifacts')
            if is_install:
                authorized = json.loads(marker.read_text()).get('sha256', {})
                for value in argv:
                    if value.endswith('.apk'):
                        apk=Path(value).resolve()
                        actual=hashlib.sha256(apk.read_bytes()).hexdigest()
                        if authorized.get(str(apk)) != actual:
                            raise ValueError('Install APK hash is not pinned in root save marker')
            timeout = min(3600,max(1,int(request.get('timeout',120))))
            output_path=Path(request.get('stdout_path',str(response_path.with_suffix('.stdout.log')))).resolve()
            error_path=Path(request.get('stderr_path',str(response_path.with_suffix('.stderr.log')))).resolve()
            workspace=ROOT.parent
            if not output_path.is_relative_to(workspace) or not error_path.is_relative_to(workspace):
                raise ValueError('Output paths must remain in workspace')
            with output_path.open('wb') as out,error_path.open('wb') as err:
                p=subprocess.run(argv,env=env,cwd=request.get('cwd',str(ROOT)),stdout=out,stderr=err,timeout=timeout)
            result={'code':p.returncode,'started_utc':started,'finished_utc':stamp(),'argv':argv,'stdout_path':str(output_path),'stderr_path':str(error_path)}
        except Exception as exc:
            result={'code':None,'started_utc':started,'finished_utc':stamp(),'error':str(exc)}
        write_json(response_path,result)
        print(json.dumps({'request':request_path.name,**result}),flush=True)
    time.sleep(1)

boot_log.close()
