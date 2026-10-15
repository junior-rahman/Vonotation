#!/usr/bin/env python3
"""Reproduce standalone Android lint over the fixed VoiceFlow CLI build.

Run the CLI Android build first. This uses the official lint31.7.3 engine and an
explicit complete source/resource/dependency descriptor, not Gradle lintPreview.
No warnings are suppressed and no source fixes are applied.
"""
import argparse, hashlib, json, os, subprocess, sys, xml.etree.ElementTree as ET
from pathlib import Path

parser=argparse.ArgumentParser()
parser.add_argument('--project',default=str(Path(__file__).resolve().parents[1]))
parser.add_argument('--cache',required=True)
parser.add_argument('--sdk',default=os.environ.get('ANDROID_SDK_ROOT'))
parser.add_argument('--jdk',default=os.environ.get('JAVA_HOME'))
args=parser.parse_args()
if not args.sdk or not args.jdk:
    parser.error('Set ANDROID_SDK_ROOT and JAVA_HOME, or pass --sdk and --jdk.')
project=Path(args.project).resolve()
cache=Path(args.cache).resolve()
sdk=Path(args.sdk).resolve()
jdk=Path(args.jdk).resolve()
output=project/'app/build/cli-lint'
output.mkdir(parents=True,exist_ok=True)
(output/'cache').mkdir(exist_ok=True)
build=project/'app/build/cli-preview'
tools=project/'tools'

def sha(path):return hashlib.sha256(Path(path).read_bytes()).hexdigest()
def local(a):
    return cache/a['group'].replace('.','/')/a['artifact']/a['version']/f"{a['artifact']}-{a['version']}.{a['type']}"

lint_lock=json.loads((tools/'android-lint-dependencies.lock.json').read_text())
if any(not local(a).is_file() for a in lint_lock['artifacts']):
    subprocess.run([sys.executable,str(tools/'resolve_lint_dependencies.py'),
                    '--cache',str(cache),'--lock',str(output/'resolved-dependencies.json')],check=True)
for artifact in lint_lock['artifacts']:
    if sha(local(artifact))!=artifact['sha256']:
        raise ValueError('Official lint dependency checksum mismatch: '+str(local(artifact)))
classpath=os.pathsep.join(str(local(a)) for a in lint_lock['artifacts'] if a['type']=='jar')

app_lock=json.loads((tools/'android-cli-dependencies.lock.json').read_text())
xml=ET.Element('project',android='true',incomplete='false',client='VoiceFlow Android CLI rebuild')
ET.SubElement(xml,'root',dir=str(project))
ET.SubElement(xml,'sdk',dir=str(sdk))
ET.SubElement(xml,'jdk',dir=str(jdk))
ET.SubElement(xml,'cache',dir=str(output/'cache'))
module=ET.SubElement(xml,'module',name='app',android='true',library='false',
                     **{'compile-sdk-version':'android-35','javaLanguage':'1.8'})
manifest=project/'app/src/main/AndroidManifest.xml'
merged=build/'AndroidManifest.xml'
if not merged.is_file():
    raise ValueError('Build the CLI preview app first; merged manifest is missing.')
ET.SubElement(module,'manifest',file=str(manifest))
ET.SubElement(module,'merged-manifest',file=str(merged))
sources=sorted((project/'app/src/main/java').rglob('*.java'))
resources=sorted(p for p in (project/'app/src/main/res').rglob('*') if p.is_file())
for path in sources:ET.SubElement(module,'src',file=str(path))
for path in sorted((build/'generated').rglob('*.java')):
    ET.SubElement(module,'src',file=str(path),generated='true')
for path in resources:ET.SubElement(module,'resource',file=str(path))
classes=project/'app/build/intermediates/javac/preview/compilePreviewJavaWithJavac/classes'
if not classes.is_dir():raise ValueError('Build the CLI preview app first; classes are missing.')
ET.SubElement(module,'classes',dir=str(classes))
for artifact in app_lock['artifacts']:
    path=local(artifact)
    if sha(path)!=artifact['sha256']:raise ValueError('App dependency checksum mismatch: '+str(path))
    kwargs={'file':str(path)}
    if artifact['type']=='aar':
        expanded=build/'aars'/f"{artifact['artifact']}-{artifact['version']}"
        if not expanded.is_dir():raise ValueError('Missing CLI-build AAR expansion: '+str(expanded))
        kwargs['extracted']=str(expanded)
    ET.SubElement(module,artifact['type'],**kwargs)
for path in sorted((build/'aars').rglob('lint.jar')):
    ET.SubElement(module,'lint-checks',file=str(path))
descriptor=output/'project.xml'
tree=ET.ElementTree(xml)
ET.indent(tree,space='  ')
tree.write(descriptor,encoding='utf-8',xml_declaration=True)
source_hashes={str(path):sha(path) for path in sources+resources+[manifest,merged]}
command=[str(jdk/'bin/java'),'-Xmx1600m','-cp',classpath,'com.android.tools.lint.Main',
         '--project',str(descriptor),'--exitcode','--stacktrace',
         '--xml',str(output/'report.xml'),'--text',str(output/'report.txt'),
         '--html',str(output/'report.html')]
with (output/'run.log').open('w') as stream:
    result=subprocess.run(command,stdout=stream,stderr=subprocess.STDOUT)
changed=[path for path,digest in source_hashes.items() if sha(path)!=digest]
receipt={'method':'Standalone official Android lint CLI31.7.3 / engine8.7.3',
         'gradle_lintPreview_run':False,'exit_code':result.returncode,
         'production_java_files':len(sources),'production_resource_files':len(resources),
         'public_app_dependency_artifacts':len(app_lock['artifacts']),
         'source_files_changed_during_analysis':changed,
         'metadata_limit':'Explicit manual descriptor does not supply the full Gradle lint model or Gradle-only checks; raw manifest MissingVersion warnings must be compared with the built APK.'}
(output/'receipt.json').write_text(json.dumps(receipt,indent=2)+'\n')
print(json.dumps(receipt,indent=2))
if changed:raise ValueError('Source changed during lint analysis; result is not a frozen-build receipt.')
sys.exit(result.returncode)
