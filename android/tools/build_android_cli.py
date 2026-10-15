#!/usr/bin/env python3
"""Fresh supported-tool Android build for this fixed VoiceFlow dependency graph.
Uses official AAPT2, javac, D8, zipalign and apksigner. This is not a Gradle/lint
result. Never consumes classes.dex or compiled app classes from a baseline APK.
The strict finite-graph manifest assembler refuses unresolved/conflicting library
attributes and cannot substitute for Android's general-purpose manifest merger.
"""
import argparse, copy, hashlib, json, os, re, shutil, subprocess, sys, zipfile
from pathlib import Path
import xml.etree.ElementTree as ET

A='{http://schemas.android.com/apk/res/android}'
T='{http://schemas.android.com/tools}'
ET.register_namespace('android',A[1:-1])
parser=argparse.ArgumentParser()
parser.add_argument('--project',default=str(Path(__file__).resolve().parents[1]))
parser.add_argument('--cache',required=True)
parser.add_argument('--prepare-only',action='store_true')
args=parser.parse_args()
project=Path(args.project).resolve(); cache=Path(args.cache).resolve()
sdk=Path(os.environ['ANDROID_SDK_ROOT']); jdk=Path(os.environ['JAVA_HOME'])
bt=sdk/'build-tools/35.0.0'; android=sdk/'platforms/android-35/android.jar'
work=project/'app/build/cli-preview'; work.mkdir(parents=True,exist_ok=True)
lock=json.loads((project/'tools/android-cli-dependencies.lock.json').read_text())
records=[]; jars=[]; aars=[]
def sha(p):return hashlib.sha256(Path(p).read_bytes()).hexdigest()
def put(z,name,data):
    info=zipfile.ZipInfo(name,(1980,1,1,0,0,0));info.compress_type=zipfile.ZIP_DEFLATED;info.external_attr=(0o100644<<16);z.writestr(info,data)
def run(cmd,output=None):
    print('Run '+str(cmd[0])+' '+(' '.join(str(x) for x in cmd[1:5]))+' ...',flush=True)
    if output:
        with open(output,'w') as stream:subprocess.run([str(x) for x in cmd],check=True,stdout=stream)
    else:subprocess.run([str(x) for x in cmd],check=True)
def local(d):return cache/d['group'].replace('.','/')/d['artifact']/d['version']/(d['artifact']+'-'+d['version']+'.'+d['type'])
for d in lock['artifacts']:
    path=local(d)
    if sha(path)!=d['sha256']:raise ValueError('Dependency checksum mismatch: '+str(path))
    records.append({'coordinate':f"{d['group']}:{d['artifact']}:{d['version']}",'sha256':d['sha256']})
    if d['type']=='aar':
        dest=work/'aars'/f"{d['artifact']}-{d['version']}"; dest.mkdir(parents=True,exist_ok=True)
        with zipfile.ZipFile(path) as z:
            for n in z.namelist():
                if n.startswith('/') or '..' in Path(n).parts:raise ValueError('Unsafe AAR path')
            z.extractall(dest)
        aars.append((d,dest))
        if (dest/'classes.jar').exists():jars.append(dest/'classes.jar')
        jars.extend(sorted((dest/'libs').glob('*.jar')))
    else:jars.append(path)

# Compose the unchanged, finite set of library manifest declarations. Resolve relative
# names before merging; app-owned attributes win only over an identical library value.
# Any unsupported tools directive or conflicting value fails the build.
manifest=ET.parse(project/'app/src/main/AndroidManifest.xml').getroot()
manifest.set('package','app.voiceflow')
buildgradle=(project/'app/build.gradle').read_text()
version=re.search(r"versionName\s+'([^']+)'",buildgradle).group(1)
code=re.search(r'versionCode\s+(\d+)',buildgradle).group(1)
manifest.set(A+'versionCode',code);manifest.set(A+'versionName',version)
application=manifest.find('application');application.set(A+'extractNativeLibs','false')
packages=[]
def qualify(node,pkg):
    if node.tag in ('activity','activity-alias','service','receiver','provider','application'):
        for attr in (A+'name',A+'targetActivity'):
            value=node.get(attr)
            if value and value.startswith('.'):node.set(attr,pkg+value)
            elif value and '.' not in value:node.set(attr,pkg+'.'+value)
    for child in node:qualify(child,pkg)
qualify(manifest,'app.voiceflow')
def signature(node):
    return (node.tag,tuple(sorted(node.attrib.items())),tuple(signature(c) for c in node))
def merge_attributes(dest,src):
    for k,v in src.attrib.items():
        if k.startswith(T):raise ValueError('Unsupported manifest tools directive: '+k)
        if k in dest.attrib and dest.get(k)!=v:raise ValueError(f'Manifest attribute conflict {dest.tag} {k}: {dest.get(k)} != {v}')
        dest.set(k,v)
def add(parent,node):
    if node.tag in ('uses-permission','uses-feature','activity','activity-alias','service','receiver','provider','meta-data'):
        key=node.get(A+'name');old=next((e for e in parent if e.tag==node.tag and e.get(A+'name')==key),None)
        if old is None:parent.append(copy.deepcopy(node));return
        merge_attributes(old,node)
        for ch in node:
            if signature(ch) not in {signature(c) for c in old}:old.append(copy.deepcopy(ch))
    elif node.tag=='queries':
        dest=parent.find('queries')
        if dest is None:parent.append(copy.deepcopy(node));return
        for ch in node:
            if signature(ch) not in {signature(c) for c in dest}:dest.append(copy.deepcopy(ch))
    elif node.tag in ('uses-sdk',):
        if int(node.get(A+'minSdkVersion','1'))>26:raise ValueError('Library requires higher minSdk')
    else:raise ValueError('Unsupported library manifest node '+node.tag)
for d,dest in aars:
    lib=ET.parse(dest/'AndroidManifest.xml').getroot();pkg=lib.get('package')
    if not pkg:raise ValueError('AAR has no manifest package')
    packages.append(pkg);qualify(lib,pkg)
    for node in lib:
        if node.tag=='application':
            merge_attributes(application,node)
            for ch in node:add(application,ch)
        else:add(manifest,node)
merged=work/'AndroidManifest.xml';ET.ElementTree(manifest).write(merged,encoding='utf-8',xml_declaration=True)

# AAPT2 emits app R and public same-table aliases for each original AAR package.
# Values from libraries are overlaid before app resources. Every AAR is fully included.
resources=[]
for d,dest in aars:
    if (dest/'res').exists() and any((dest/'res').rglob('*.*')):
        out=work/'compiled'/f"{d['artifact']}-{d['version']}.zip";out.parent.mkdir(parents=True,exist_ok=True)
        run([bt/'aapt2','compile','--dir',dest/'res','-o',out]);resources.append(out)
appres=work/'compiled/app.zip';run([bt/'aapt2','compile','--dir',project/'app/src/main/res','-o',appres]);resources.append(appres)
generated=work/'generated';generated.mkdir(exist_ok=True)
linked=work/'resources.apk'
command=[bt/'aapt2','link','-o',linked,'-I',android,'--manifest',merged,'--java',generated,'--min-sdk-version','26','--target-sdk-version','35','--auto-add-overlay','--extra-packages',':'.join(sorted(set(packages))), '--output-text-symbols',work/'R.txt']
for res in resources:command.extend(['-R',res])
run(command)
# Published AAR R.txt contains symbol names, with non-final publication indexes.
# Runtime R.styleable arrays/indices must use the complete linked array, just as
# the baseline AGP APK does. Keep surviving per-package scalar names and expand
# linked styleable groups; do not add all resource names to every package alias.
def symbol_table(path):
    result={}
    for line in path.read_text().splitlines():
        match=re.fullmatch(r'(int\[\]|int) (\w+) (\w+) (.+)',line)
        if not match:raise ValueError('Unsupported resource symbol line: '+line)
        jtype,kind,name,value=match.groups()
        parsed=[int(v.strip(),0) for v in value.strip('{} ').split(',') if v.strip()] if jtype=='int[]' else int(value,0)
        result[(kind,name)]=(jtype,parsed)
    return result
linked_symbols=symbol_table(work/'R.txt')
global_arrays={name for (kind,name),(jtype,value) in linked_symbols.items() if kind=='styleable' and jtype=='int[]'}
styleable_checks=0;library_resource_checks=0;displaced_resource_symbols=[]
for d,dest in aars:
    symbols=dest/'R.txt';pkg=ET.parse(dest/'AndroidManifest.xml').getroot().get('package')
    declared=symbol_table(symbols) if symbols.exists() else {}
    fields={};done=set()
    for (kind,name),(jtype,value) in declared.items():
        if kind=='styleable' and jtype=='int':continue
        if (kind,name) not in linked_symbols:
            # Version mediation can displace obsolete transitive names in old R.txt.
            # Their absence must independently match the preserved APK R field table.
            displaced_resource_symbols.append(pkg+'.'+kind+'.'+name);continue
        linked_type,linked_value=linked_symbols[(kind,name)]
        if kind=='styleable' and jtype=='int[]':
            final='new int[]{'+','.join(hex(v) for v in linked_value)+'}'
            fields.setdefault(kind,[]).append('    public static final int[] '+name+' = '+final+';');done.add((kind,name))
            for (ik,inn),(it,iv) in linked_symbols.items():
                if ik!='styleable' or it!='int' or not inn.startswith(name+'_'):continue
                owner=max((an for an in global_arrays if inn.startswith(an+'_')),key=len)
                if owner!=name:continue
                if iv<0 or iv>=len(linked_value):raise ValueError('Invalid linked styleable index '+pkg+'.'+inn)
                fields.setdefault(kind,[]).append('    public static final int '+inn+' = '+str(iv)+';');done.add((kind,inn));styleable_checks+=1
        else:
            fields.setdefault(kind,[]).append('    public static final '+jtype+' '+name+' = '+hex(linked_value)+';');done.add((kind,name));library_resource_checks+=1
    lines=['package '+pkg+';','// Generated from original AAR symbol names + freshly linked resource IDs/styleables.','public final class R {']
    for kind,definitions in sorted(fields.items()):lines+=['  public static final class '+kind+' {']+definitions+['  }']
    lines+=['  private R() {}','}']
    rfile=generated/Path(pkg.replace('.','/'))/'R.java';rfile.parent.mkdir(parents=True,exist_ok=True);rfile.write_text('\n'.join(lines)+'\n')
run([bt/'aapt2','dump','resources',linked],work/'resource-table.txt')
run([bt/'aapt2','dump','xmltree',linked,'--file','AndroidManifest.xml'],work/'manifest-tree.txt')
receipt={'method':'fresh CLI aapt2+javac+D8+zipalign+apksigner; not Gradle or lint','version':version,'versionCode':int(code),'dependencyArtifacts':records,'libraryPackages':sorted(set(packages)),'mergedManifestSha256':sha(merged),'libraryStyleableIndexChecks':styleable_checks,'libraryResourceIdChecks':library_resource_checks,'displacedResourceSymbols':displaced_resource_symbols,'javaSources':{str(p.relative_to(project)):sha(p) for p in sorted((project/'app/src/main/java').rglob('*.java'))}}
(work/'build-inputs.json').write_text(json.dumps(receipt,indent=2)+'\n')
if args.prepare_only:
    print('Resources/finite-graph manifests prepared; app Java has not compiled.',flush=True);sys.exit(0)

classes=project/'app/build/intermediates/javac/preview/compilePreviewJavaWithJavac/classes'
if classes.exists():shutil.rmtree(classes)
classes.mkdir(parents=True)
classpath=os.pathsep.join(str(p) for p in [android]+jars)
sources=sorted((project/'app/src/main/java').rglob('*.java'))+sorted(generated.rglob('R.java'))
argfile=work/'javac-sources.txt';argfile.write_text('\n'.join('"'+str(p)+'"' for p in sources)+'\n')
compiler=[jdk/'bin/javac'] if (jdk/'bin/javac').exists() else [jdk/'bin/java','-m','jdk.compiler/com.sun.tools.javac.Main']
run(compiler+['-source','8','-target','8','-encoding','UTF-8','-bootclasspath',str(bt/'core-lambda-stubs.jar')+os.pathsep+str(android),'-classpath',classpath,'-d',classes,'@'+str(argfile)])
program=work/'app-classes.jar'
with zipfile.ZipFile(program,'w',zipfile.ZIP_DEFLATED) as z:
    for p in sorted(classes.rglob('*.class')):put(z,str(p.relative_to(classes)),p.read_bytes())
dex=work/'dex';dex.mkdir(exist_ok=True)
for p in dex.glob('classes*.dex'):p.unlink()
run([bt/'d8','--release','--lib',android,'--min-api','26','--output',dex,program]+jars)

unsigned=work/'unsigned.apk';shutil.copy2(linked,unsigned)
merged_resources={}
for jar in jars:
    with zipfile.ZipFile(jar) as z:
        for n in z.namelist():
            if n.endswith('/') or n.endswith(('.class','.kotlin_metadata')) or n in ('META-INF/MANIFEST.MF',):continue
            if re.match(r'META-INF/.*\.(SF|RSA|DSA|EC)$',n) or n.startswith(('META-INF/versions/','META-INF/LICENSE','META-INF/NOTICE','META-INF/com.android.tools/','META-INF/proguard/')):continue
            # Match normal Android packaging excludes for Kotlin module metadata.
            if n.endswith('.kotlin_module'):continue
            b=z.read(n)
            if n in merged_resources and merged_resources[n]!=b:
                if n.startswith('META-INF/services/'):merged_resources[n]+=b
                else:raise ValueError('Conflicting Java resource '+n)
            else:merged_resources[n]=b
with zipfile.ZipFile(unsigned,'a',zipfile.ZIP_DEFLATED) as z:
    for p in sorted(dex.glob('classes*.dex')):put(z,p.name,p.read_bytes())
    for n,b in sorted(merged_resources.items()):put(z,n,b)
    put(z,'META-INF/app.voiceflow.cli-build.json',json.dumps({'version':version,'versionCode':int(code),'method':'Android SDK35 supported CLI tools; Gradle and lint not executed'},sort_keys=True))
aligned=work/'aligned.apk';run([bt/'zipalign','-f','-p','4',unsigned,aligned])
out=project/'app/build/outputs/apk/preview'/f'VoiceFlow-{version}.apk';out.parent.mkdir(parents=True,exist_ok=True)
ks=Path(os.environ.get('VOICEFLOW_KEYSTORE',project/'preview-signing/voiceflow.keystore'))
# Inherited preview credential is a public development keystore, not a production signing key.
run([bt/'apksigner','sign','--ks',ks,'--ks-pass','pass:android','--key-pass','pass:android','--ks-key-alias','voiceflow','--v1-signing-enabled','false','--v2-signing-enabled','true','--v3-signing-enabled','false','--out',out,aligned])
run([bt/'apksigner','verify','--verbose','--print-certs',out],work/'signature.txt')
if '68:24:bc:2c:9f:cf:54:26:6d:db:53:da:b1:05:7d:00:17:82:3f:fc'.replace(':','') not in (work/'signature.txt').read_text().lower().replace(':',''):
    raise ValueError('Preview signing certificate fingerprint mismatch')
run([bt/'aapt2','dump','badging',out],work/'badging.txt')
receipt.update({'apk':str(out),'apkSha256':sha(out),'javacClassCount':len(list(classes.rglob('*.class'))),'dexFiles':[{'name':p.name,'sha256':sha(p)} for p in dex.glob('classes*.dex')],'javaSourcesUnchangedDuringBuild':all(sha(project/p)==h for p,h in receipt['javaSources'].items())})
if not receipt['javaSourcesUnchangedDuringBuild']:raise ValueError('Source changed during build')
(work/'build-result.json').write_text(json.dumps(receipt,indent=2)+'\n')
print('Fresh CLI signed APK: '+str(out)+' sha256='+receipt['apkSha256'],flush=True)
